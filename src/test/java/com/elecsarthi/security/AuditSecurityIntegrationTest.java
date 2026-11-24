package com.elecsarthi.security;

import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.repository.*;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Audit log: JSON resilience, DENIED events surviving rollbacks, supporting indexes. */
public class AuditSecurityIntegrationTest extends SecurityIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Finding 12: Audit JSON Resilience - Special characters in void reason do not break JSON serialization")
    void testAuditJsonSerializationResilience() {
        AppUser creatorAud = userRepository.save(AppUser.builder()
                .username("creator_user_audit")
                .fullName("Creator User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Audit Test Customer")
                .contactPhone("+919112233446")
                .status(UserStatus.ACTIVE)
                .createdByUserId(creatorAud.getId())
                .build());

        // Log an audit with tricky characters (quotes, backslashes, newlines)
        String maliciousReason = "customer said: \"cancel my subscription\" and \n dropped connection \\ null";
        assertDoesNotThrow(() ->
                auditService.logDetails(creatorAud.getId(), customer.getId(), null, null,
                        "TEST_REASON", AuditOutcome.SUCCESS, "test_entity", "1",
                        Map.of("reason", maliciousReason), "127.0.0.1")
        );

        // Verify row was committed into audit_log
        String detailsInDb = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_log WHERE action_type = 'TEST_REASON' ORDER BY id DESC LIMIT 1",
                String.class
        );
        assertNotNull(detailsInDb);
        assertTrue(detailsInDb.contains("cancel my subscription"));
    }

    @Test
    @DisplayName("Finding 5 remainder: Autonomous DENIED audit persistence across caller rollbacks")
    void testAutonomousDeniedAuditPersistence() {
        String testUser = "denied_user_" + UUID.randomUUID();
        AppUser user = userRepository.save(AppUser.builder()
                .username(testUser)
                .fullName("Denied Test User")
                .passwordHash(passwordEncoder.encode("RealSecret@123"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build());

        // Attempt login with bad password
        LoginRequest badPassLogin = LoginRequest.builder()
                .identifier(testUser)
                .password("WrongPassword@999")
                .clientType(ClientType.MOBILE)
                .installationId("inst-audit-test")
                .build();

        assertThrows(ApiException.class, () -> authService.login(badPassLogin, "127.0.0.1"));

        // Verify DENIED audit record was persisted in database despite exception
        List<Map<String, Object>> deniedRows = jdbcTemplate.queryForList(
                "SELECT * FROM audit_log WHERE target_id = ? AND action_type = 'LOGIN' AND outcome = 'DENIED'",
                testUser
        );
        assertFalse(deniedRows.isEmpty(), "DENIED audit log must be persisted in database");
    }

    @Test
    @DisplayName("V7 audit_log partial indexes exist and are usable by the planner")
    void testAuditLogPartialIndexesExistAndArePlanned() {
        // The indexes must exist and be planned by EXPLAIN when sequential scans are disabled
        List<String> indexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'audit_log' AND indexname IN ('idx_audit_log_registration_jti', 'idx_audit_log_global_mobile_toggle')",
                String.class);
        assertEquals(2, indexNames.size(), "Both V7 audit_log partial indexes must exist");

        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            List<String> jtiPlan = jdbcTemplate.queryForList(
                    "EXPLAIN SELECT EXISTS (SELECT 1 FROM audit_log WHERE action_type = 'REGISTRATION_JTI_INVALIDATED' AND details->>'jti' = 'probe-jti')",
                    String.class);
            assertTrue(jtiPlan.stream().anyMatch(line -> line.contains("idx_audit_log_registration_jti")),
                    "Planner must be able to use idx_audit_log_registration_jti, plan was: " + jtiPlan);

            List<String> togglePlan = jdbcTemplate.queryForList(
                    "EXPLAIN SELECT details FROM audit_log WHERE action_type = 'GLOBAL_MOBILE_TOGGLE' ORDER BY created_at DESC LIMIT 1",
                    String.class);
            assertTrue(togglePlan.stream().anyMatch(line -> line.contains("idx_audit_log_global_mobile_toggle")),
                    "Planner must be able to use idx_audit_log_global_mobile_toggle, plan was: " + togglePlan);
        });
    }
}
