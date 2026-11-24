package com.elecsarthi.security;

import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.dto.response.LoginResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.entity.UserSession;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SessionStatus;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Concurrent-session limits and device-bound login rules enforced by {@code AuthService.login}. */
@SpringBootTest
@ActiveProfiles("test")
class SessionLimitIntegrationTest {

    private static final String PASSWORD = "Passw0rd!123";

    @Autowired
    private AuthService authService;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private UserDeviceRepository deviceRepository;
    @Autowired
    private UserSessionRepository sessionRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute(
                "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; "
                + "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, "
                + "agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter CASCADE; "
                + "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;");
    }

    private AppUser createUser(String username, Role role) {
        return userRepository.save(AppUser.builder()
                .username(username)
                .fullName(username)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(role)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private UserDevice activeDevice(AppUser user, String installationId) {
        return deviceRepository.save(UserDevice.builder()
                .userId(user.getId())
                .userRole(user.getRole())
                .installationId(installationId)
                .platform(DevicePlatform.ANDROID)
                .status(DeviceStatus.ACTIVE)
                .build());
    }

    private LoginResponse webLogin(String username) {
        return authService.login(LoginRequest.builder()
                .identifier(username).password(PASSWORD).clientType(ClientType.WEB).build(), "127.0.0.1");
    }

    private LoginResponse mobileLogin(String username, String installationId) {
        return authService.login(LoginRequest.builder()
                .identifier(username).password(PASSWORD).clientType(ClientType.MOBILE).installationId(installationId).build(), "127.0.0.1");
    }

    private long activeSessions(AppUser user) {
        return sessionRepository.countByUserIdAndStatus(user.getId(), SessionStatus.ACTIVE);
    }

    @Test
    @DisplayName("Owner may hold three web sessions; a fourth login is rejected and evicts nobody")
    void ownerFourthSessionRejectedWithoutEviction() {
        AppUser owner = createUser("limit_owner", Role.OWNER);
        webLogin("limit_owner");
        webLogin("limit_owner");
        webLogin("limit_owner");

        ConflictException ex = assertThrows(ConflictException.class, () -> webLogin("limit_owner"));

        assertEquals(ErrorCode.SESSION_LIMIT_EXCEEDED, ex.getErrorCode());
        assertEquals(3, activeSessions(owner), "The rejected login must not terminate any existing session");
    }

    @Test
    @DisplayName("Lapsed sessions are closed with their expiry reason and do not count towards the limit")
    void lapsedSessionsDoNotCountTowardsLimit() {
        AppUser owner = createUser("lapsed_owner", Role.OWNER);
        webLogin("lapsed_owner");
        webLogin("lapsed_owner");
        LoginResponse idleSession = webLogin("lapsed_owner");
        jdbcTemplate.update("UPDATE user_session SET idle_expires_at = now() - interval '1 minute' WHERE id = ?::uuid",
                idleSession.getSessionId().toString());

        LoginResponse fourth = webLogin("lapsed_owner");

        assertNotNull(fourth.getAccessToken());
        UserSession lapsed = sessionRepository.findById(idleSession.getSessionId()).orElseThrow();
        assertEquals(SessionStatus.TERMINATED, lapsed.getStatus());
        assertEquals("IDLE_EXPIRED", lapsed.getTerminationReason());
        assertEquals(3, activeSessions(owner));
    }

    @Test
    @DisplayName("A mobile login proving the same ACTIVE device replaces that device's session")
    void sameDeviceLoginReplacesItsSession() {
        AppUser agent = createUser("limit_agent", Role.AGENT);
        activeDevice(agent, "inst-1");
        LoginResponse first = mobileLogin("limit_agent", "inst-1");

        LoginResponse second = mobileLogin("limit_agent", "inst-1");

        UserSession replaced = sessionRepository.findById(first.getSessionId()).orElseThrow();
        assertEquals(SessionStatus.TERMINATED, replaced.getStatus());
        assertEquals("REPLACED_SAME_DEVICE", replaced.getTerminationReason());
        assertEquals(agent.getId(), replaced.getTerminatedByUserId());
        assertEquals(SessionStatus.ACTIVE, sessionRepository.findById(second.getSessionId()).orElseThrow().getStatus());
        assertEquals(1, activeSessions(agent));
    }

    @Test
    @DisplayName("A mobile login from another installation is denied and audited with the attempted ID kept as data")
    void loginFromDifferentInstallationDeniedAndAudited() {
        AppUser agent = createUser("mismatch_agent", Role.AGENT);
        activeDevice(agent, "inst-registered");
        String hostileInstallationId = "x\",\"reason\":\"forged";

        ApiException ex = assertThrows(ApiException.class, () -> mobileLogin("mismatch_agent", hostileInstallationId));

        assertEquals(ErrorCode.DEVICE_CHANGE_NOT_ALLOWED, ex.getErrorCode());
        assertEquals(0, activeSessions(agent));
        List<String> recorded = jdbcTemplate.queryForList(
                "SELECT details->>'attemptedInstallationId' FROM audit_log "
                        + "WHERE action_type = 'LOGIN_DEVICE_MISMATCH' AND outcome = 'DENIED'", String.class);
        assertEquals(List.of(hostileInstallationId), recorded);
        String reason = jdbcTemplate.queryForObject(
                "SELECT details->>'reason' FROM audit_log WHERE action_type = 'LOGIN_DEVICE_MISMATCH'", String.class);
        assertEquals("Attempted login from new installation while active device exists", reason);
    }
}
