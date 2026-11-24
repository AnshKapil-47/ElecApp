package com.elecsarthi.security;

import com.elecsarthi.dto.request.CustomerActivateRequest;
import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.dto.request.RefreshTokenRequest;
import com.elecsarthi.dto.response.LoginResponse;
import com.elecsarthi.dto.response.TokenRefreshResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DeviceNonceRepository;
import com.elecsarthi.repository.OfflineLeaseRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class AuthServiceIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private UserDeviceRepository deviceRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private DeviceNonceRepository nonceRepository;

    @Autowired
    private OfflineLeaseRepository leaseRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
            "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; " +
            "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter CASCADE; " +
            "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;"
        );
    }

    @Test
    @DisplayName("Owner can log in via WEB client and receives no refresh token")
    void testOwnerWebLoginSuccess() {
        AppUser owner = AppUser.builder()
                .username("test_owner")
                .fullName("System Owner")
                .passwordHash(passwordEncoder.encode("OwnerPass123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(owner);

        LoginRequest req = LoginRequest.builder()
                .identifier("test_owner")
                .password("OwnerPass123!")
                .clientType(ClientType.WEB)
                .build();

        LoginResponse resp = authService.login(req, "127.0.0.1");

        assertNotNull(resp);
        assertNotNull(resp.getAccessToken());
        assertNull(resp.getRefreshToken(), "Web logins must never receive refresh tokens");
        assertEquals(Role.OWNER, resp.getRole());
        assertEquals(ClientType.WEB, resp.getClientType());
    }

    @Test
    @DisplayName("Owner attempting to log in via MOBILE is rejected with 403 FORBIDDEN")
    void testOwnerMobileLoginRejected() {
        AppUser owner = AppUser.builder()
                .username("test_owner_mob")
                .fullName("System Owner")
                .passwordHash(passwordEncoder.encode("OwnerPass123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(owner);

        LoginRequest req = LoginRequest.builder()
                .identifier("test_owner_mob")
                .password("OwnerPass123!")
                .clientType(ClientType.MOBILE)
                .installationId("inst-test")
                .build();

        ApiException ex = assertThrows(ApiException.class, () -> authService.login(req, "127.0.0.1"));
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    @DisplayName("Agent attempting to log in via WEB is rejected with 403 FORBIDDEN")
    void testAgentWebLoginRejected() {
        AppUser agent = AppUser.builder()
                .username("test_agent")
                .fullName("Agent Smith")
                .passwordHash(passwordEncoder.encode("AgentPass123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(agent);

        LoginRequest req = LoginRequest.builder()
                .identifier("test_agent")
                .password("AgentPass123!")
                .clientType(ClientType.WEB)
                .build();

        ApiException ex = assertThrows(ApiException.class, () -> authService.login(req, "127.0.0.1"));
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    @DisplayName("Agent logs in on MOBILE with registered device and receives refresh token")
    void testAgentMobileLoginSuccess() {
        AppUser agent = AppUser.builder()
                .username("test_agent_mob")
                .fullName("Agent Smith")
                .passwordHash(passwordEncoder.encode("AgentPass123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build();
        agent = userRepository.save(agent);

        UserDevice device = UserDevice.builder()
                .userId(agent.getId())
                .userRole(Role.AGENT)
                .installationId("inst-agent-1")
                .platform(DevicePlatform.ANDROID)
                .status(DeviceStatus.ACTIVE)
                .build();
        deviceRepository.save(device);

        LoginRequest req = LoginRequest.builder()
                .identifier("test_agent_mob")
                .password("AgentPass123!")
                .clientType(ClientType.MOBILE)
                .installationId("inst-agent-1")
                .build();

        LoginResponse resp = authService.login(req, "127.0.0.1");

        assertNotNull(resp.getAccessToken());
        assertNotNull(resp.getRefreshToken(), "Mobile logins must receive refresh tokens");
        assertTrue(resp.getRefreshToken().startsWith(resp.getSessionId().toString()));
    }

    @Test
    @DisplayName("Mobile refresh token rotation and reuse detection terminates compromised session")
    void testRefreshTokenRotationAndReuseDetection() {
        AppUser agent = AppUser.builder()
                .username("test_agent_rot")
                .fullName("Agent Smith")
                .passwordHash(passwordEncoder.encode("AgentPass123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build();
        agent = userRepository.save(agent);

        UserDevice device = UserDevice.builder()
                .userId(agent.getId())
                .userRole(Role.AGENT)
                .installationId("inst-agent-rot")
                .platform(DevicePlatform.ANDROID)
                .status(DeviceStatus.ACTIVE)
                .build();
        deviceRepository.save(device);

        LoginResponse loginResp = authService.login(LoginRequest.builder()
                .identifier("test_agent_rot")
                .password("AgentPass123!")
                .clientType(ClientType.MOBILE)
                .installationId("inst-agent-rot")
                .build(), "127.0.0.1");

        String originalRefreshToken = loginResp.getRefreshToken();

        // 1. First valid refresh -> succeeds and rotates token
        TokenRefreshResponse refresh1 = authService.refreshToken(
                new RefreshTokenRequest(originalRefreshToken), "127.0.0.1");
        assertNotNull(refresh1.getAccessToken());
        assertNotNull(refresh1.getRefreshToken());
        assertNotEquals(originalRefreshToken, refresh1.getRefreshToken());

        // 2. Attacker attempts to reuse originalRefreshToken (lower generation)
        ApiException reuseEx = assertThrows(ApiException.class, () ->
                authService.refreshToken(new RefreshTokenRequest(originalRefreshToken), "127.0.0.1"));
        assertEquals(401, reuseEx.getStatus().value());

        // 3. Compromised session is now TERMINATED, so even the new refresh token is revoked
        ApiException terminatedEx = assertThrows(ApiException.class, () ->
                authService.refreshToken(new RefreshTokenRequest(refresh1.getRefreshToken()), "127.0.0.1"));
        assertEquals(401, terminatedEx.getStatus().value());
    }

    @Test
    @DisplayName("Customer activation with Crockford Base32 code activates account and clears hash")
    void testCustomerActivation() {
        // Setup Owner provenance
        AppUser owner = userRepository.save(AppUser.builder()
                .username("owner_prov")
                .fullName("Owner Prov")
                .passwordHash("x")
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        String pepper = "elecsarthi-activation-dev-pepper-2026";
        String rawCode = "ABCDEFGHJK";
        String hash = CryptoUtils.hmacSha256Hex(rawCode, pepper);

        CustomerAccount cust = CustomerAccount.builder()
                .customerName("Candidate Campaign")
                .contactPhone("+919876543210")
                .createdByUserId(owner.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.PENDING)
                .activationCodeHash(hash)
                .activationCodeExpiresAt(Instant.now().plus(72, ChronoUnit.HOURS))
                .activationAttemptCount((short) 0)
                .build();
        cust = customerAccountRepository.save(cust);

        AppUser primaryUser = AppUser.builder()
                .customerAccountId(cust.getId())
                .createdByUserId(owner.getId())
                .username("cand_primary")
                .fullName("Candidate Admin")
                .role(Role.CUSTOMER)
                .status(UserStatus.PENDING)
                .build();
        userRepository.save(primaryUser);

        // Activate
        CustomerActivateRequest actReq = CustomerActivateRequest.builder()
                .contactPhone("+919876543210")
                .activationCode("ABCDEFGHJK")
                .password("CandidatePass123!")
                .build();

        authService.activateCustomer(actReq, "127.0.0.1");

        CustomerAccount updatedCust = customerAccountRepository.findById(cust.getId()).orElseThrow();
        assertEquals(ActivationStatus.ACTIVATED, updatedCust.getActivationStatus());
        assertNull(updatedCust.getActivationCodeHash(), "Hash must be cleared upon activation");

        AppUser updatedUser = userRepository.findByUsername("cand_primary").orElseThrow();
        assertEquals(UserStatus.ACTIVE, updatedUser.getStatus());
        assertTrue(passwordEncoder.matches("CandidatePass123!", updatedUser.getPasswordHash()));
    }

    @Test
    @DisplayName("Concurrent session limit is enforced: Agent max 1 active session")
    void testAgentSessionLimitEnforced() {
        AppUser agent = userRepository.save(AppUser.builder()
                .username("agent_limit")
                .fullName("Agent Limit")
                .passwordHash(passwordEncoder.encode("AgentPass123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build());

        deviceRepository.save(UserDevice.builder()
                .userId(agent.getId())
                .userRole(Role.AGENT)
                .installationId("inst-limit-1")
                .platform(DevicePlatform.ANDROID)
                .status(DeviceStatus.ACTIVE)
                .build());

        // First login -> succeeds
        authService.login(LoginRequest.builder()
                .identifier("agent_limit")
                .password("AgentPass123!")
                .clientType(ClientType.MOBILE)
                .installationId("inst-limit-1")
                .build(), "127.0.0.1");

        // Register second device for same agent to attempt concurrent login
        // Notice: One user can only have 1 ACTIVE device in DB, so if user has 1 active session and another login is attempted without same device replacement, it is rejected!
        // Attempting a second login with the same device replaces the old session:
        LoginResponse secondLoginSameDevice = authService.login(LoginRequest.builder()
                .identifier("agent_limit")
                .password("AgentPass123!")
                .clientType(ClientType.MOBILE)
                .installationId("inst-limit-1")
                .build(), "127.0.0.1");

        assertNotNull(secondLoginSameDevice);
        assertEquals(1, sessionRepository.countByUserIdAndStatus(agent.getId(), SessionStatus.ACTIVE),
                "Old session was replaced, active session count stays at 1");
    }
}
