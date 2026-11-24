package com.elecsarthi.security;

import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.entity.UserSession;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.SessionStatus;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared fixture for the security integration tests: a clean database per test and device/session helpers. */
@SpringBootTest
@ActiveProfiles("test")
abstract class SecurityIntegrationTestSupport {

    @Autowired
    protected JwtTokenProvider tokenProvider;

    @Autowired
    protected UserDeviceRepository deviceRepository;

    @Autowired
    protected UserSessionRepository sessionRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute(
                "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; " +
                "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter CASCADE; " +
                "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;"
        );
    }

    protected UserDevice createActiveDevice(AppUser user, Long customerAccountId, String installationId) {
        Instant now = Instant.now();
        return deviceRepository.save(UserDevice.builder()
                .userId(user.getId())
                .customerAccountId(customerAccountId)
                .userRole(user.getRole())
                .platform(DevicePlatform.ANDROID)
                .deviceName("Device-" + user.getRole().name())
                .installationId(installationId + "-" + UUID.randomUUID())
                .publicKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest")
                .status(DeviceStatus.ACTIVE)
                .activatedAt(now)
                .lastUsedAt(now)
                .lastRequestCounter(0L)
                .createdAt(now)
                .build());
    }

    protected String createActiveSessionAndToken(AppUser user, ClientType clientType, UUID deviceId) {
        UUID sessId = UUID.randomUUID();
        Instant now = Instant.now();
        sessionRepository.save(UserSession.builder()
                .id(sessId)
                .userId(user.getId())
                .userRole(user.getRole())
                .deviceId(deviceId)
                .clientType(clientType)
                .status(SessionStatus.ACTIVE)
                .issuedAt(now)
                .lastSeenAt(now)
                .idleExpiresAt(clientType == ClientType.WEB ? now.plus(30, ChronoUnit.MINUTES) : null)
                .absoluteExpiresAt(now.plus(12, ChronoUnit.HOURS))
                .build());
        return tokenProvider.generateAccessToken(sessId, user.getId(), user.getUsername(), user.getRole(), user.getCustomerAccountId());
    }

    protected KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate EC key pair", e);
        }
    }

    protected String signRefresh(PrivateKey privateKey, String refreshToken, long ts, String nonce, long counter) throws Exception {
        String bodySha256 = CryptoUtils.sha256Hex(refreshToken.getBytes(StandardCharsets.UTF_8));
        String payload = String.join("\n",
                "POST",
                "/api/v1/auth/refresh",
                bodySha256,
                String.valueOf(ts),
                nonce,
                String.valueOf(counter),
                ""
        );
        return signData(privateKey, payload.getBytes(StandardCharsets.UTF_8));
    }

    protected String signData(PrivateKey privateKey, byte[] data) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(data);
        return Base64.getEncoder().encodeToString(sig.sign());
    }
}
