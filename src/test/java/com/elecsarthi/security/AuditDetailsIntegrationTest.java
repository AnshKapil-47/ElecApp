package com.elecsarthi.security;

import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.EntitlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit details are built from structured values: client text is always data, never JSON syntax. */
public class AuditDetailsIntegrationTest extends SecurityIntegrationTestSupport {

    @Autowired
    private AuditService auditService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private CustomerAccountRepository customerAccountRepository;
    @Autowired
    private DatasetRepository datasetRepository;
    @Autowired
    private ElectionTypeRepository electionTypeRepository;
    @Autowired
    private EntitlementRepository entitlementRepository;
    @Autowired
    private EntitlementService entitlementService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String keysOf(String targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT string_agg(k, ',' ORDER BY k) FROM audit_log, jsonb_object_keys(details) k WHERE target_id = ?",
                String.class, targetId);
    }

    private String valueOf(String targetId, String key) {
        return jdbcTemplate.queryForObject("SELECT details->>? FROM audit_log WHERE target_id = ?", String.class, key, targetId);
    }

    private void log(String targetId, AuditOutcome outcome, Map<String, Object> details) {
        auditService.logDetails(null, null, null, null, "AUDIT_DETAILS_PROBE", outcome, "probe", targetId, details, "127.0.0.1");
    }

    @Test
    @DisplayName("Quotes, backslashes, newlines, Unicode and injection attempts are stored verbatim as one value")
    void hostileTextIsStoredAsData() {
        String[] hostile = {
                "x\",\"role\":\"OWNER\",\"y\":\"",
                "}{\"forged\":true}",
                "back\\slash \\\" \\u0000literal \\n",
                "line1\nline2\r\n\ttab",
                "quote \" and ' mix \"\"\"",
                "नमस्ते 😀 عربي ‮",
                "a".repeat(20_000),
                ""
        };
        for (String text : hostile) {
            String targetId = UUID.randomUUID().toString();
            log(targetId, AuditOutcome.SUCCESS, AuditService.details("value", text, "count", 3));

            assertEquals("count,value", keysOf(targetId), "no key may be injected by client text: " + text);
            assertEquals(text, valueOf(targetId, "value"));
            assertEquals("3", valueOf(targetId, "count"));
        }
    }

    @Test
    @DisplayName("NUL and unpaired surrogates, which jsonb cannot store, are replaced instead of failing the audit insert")
    void textThatJsonbCannotStoreIsReplaced() {
        String withNul = "before\u0000after";
        String withLoneSurrogate = "bad\uD800pair";
        String nulTarget = UUID.randomUUID().toString();
        String surrogateTarget = UUID.randomUUID().toString();

        log(nulTarget, AuditOutcome.SUCCESS, AuditService.details("value", withNul));
        log(surrogateTarget, AuditOutcome.SUCCESS, AuditService.details("value", withLoneSurrogate, "nested", Map.of("inner", withNul)));

        assertEquals("before�after", valueOf(nulTarget, "value"));
        assertEquals("bad�pair", valueOf(surrogateTarget, "value"));
        assertEquals("before�after", jdbcTemplate.queryForObject(
                "SELECT details->'nested'->>'inner' FROM audit_log WHERE target_id = ?", String.class, surrogateTarget));
    }

    @Test
    @DisplayName("Null values are kept as explicit JSON nulls, not dropped and not the text 'null'")
    void nullValuesAreExplicit() {
        String targetId = UUID.randomUUID().toString();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", null);
        details.put("count", 0);
        log(targetId, AuditOutcome.SUCCESS, details);
        log(UUID.randomUUID().toString(), AuditOutcome.SUCCESS, AuditService.details("reason", null));

        assertEquals("count,reason", keysOf(targetId));
        assertEquals("null", jdbcTemplate.queryForObject(
                "SELECT jsonb_typeof(details->'reason') FROM audit_log WHERE target_id = ?", String.class, targetId));
        assertNull(valueOf(targetId, "reason"));
    }

    @Test
    @DisplayName("A DENIED audit survives a rolled-back caller transaction; a SUCCESS audit rolls back with its operation")
    void deniedAuditIsAutonomousAndSuccessAuditIsTransactional() {
        String deniedTarget = UUID.randomUUID().toString();
        String successTarget = UUID.randomUUID().toString();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            log(deniedTarget, AuditOutcome.DENIED, AuditService.details("reason", "bad \"input\""));
            log(successTarget, AuditOutcome.SUCCESS, AuditService.details("reason", "will be rolled back"));
            status.setRollbackOnly();
        });

        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE target_id = ?", Integer.class, deniedTarget));
        assertEquals("bad \"input\"", valueOf(deniedTarget, "reason"));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE target_id = ?", Integer.class, successTarget));
    }

    @Test
    @DisplayName("A hostile revoke reason sent through a real service ends up as a single audit value")
    void hostileReasonThroughRealServiceIsStoredAsData() {
        Instant now = Instant.now();
        AppUser owner = userRepository.save(AppUser.builder()
                .username("audit_owner")
                .fullName("Audit Owner")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());
        CustomerAccount customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Audit Customer")
                .contactPhone("+919800000077")
                .createdByUserId(owner.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());
        Dataset dataset = datasetRepository.save(Dataset.builder()
                .name("Audit Dataset")
                .originalFileName("audit.xlsx")
                .fileSha256("f".repeat(64))
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(now.plus(60, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .build());
        ElectionType electionType = electionTypeRepository.findAll().get(0);
        Entitlement entitlement = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customer.getId())
                .electionTypeId(electionType.getId())
                .datasetId(dataset.getId())
                .storageTier(DatasetTier.MASTER)
                .criteria("{\"ac_no\":1}")
                .criteriaHash("9".repeat(64))
                .scopeLabel("AC 1")
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.DAYS))
                .validUntil(now.plus(30, ChronoUnit.DAYS))
                .mobileAccess(false)
                .createdByUserId(owner.getId())
                .createdAt(now)
                .build());

        String hostileReason = "done\",\"revokedByUserId\":1,\"x\":\"\n\\ न";
        entitlementService.revokeEntitlement(owner.getId(), Role.OWNER, entitlement.getId(), hostileReason);

        String targetId = entitlement.getId().toString();
        assertEquals("reason", keysOf(targetId), "the reason must stay a single value");
        assertTrue(valueOf(targetId, "reason").startsWith("done\",\"revokedByUserId\":1,\"x\":\"\n\\ "));
        assertEquals("DIRECT_ENTITLEMENT_REVOKED", jdbcTemplate.queryForObject(
                "SELECT action_type FROM audit_log WHERE target_id = ?", String.class, targetId));
    }
}
