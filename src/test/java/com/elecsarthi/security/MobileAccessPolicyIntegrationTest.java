package com.elecsarthi.security;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.repository.*;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The global mobile-number switch is persisted, audited and fails closed. */
public class MobileAccessPolicyIntegrationTest extends SecurityIntegrationTestSupport {

    @Autowired
    private com.elecsarthi.service.VoterService voterService;

    @Autowired
    private EntitlementRepository entitlementRepository;

    @Autowired
    private ElectionTypeRepository electionTypeRepository;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private AuditService auditService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.elecsarthi.security.authorization.VoterAccessAuthorizer voterAccessAuthorizer;

    @Autowired
    private com.elecsarthi.security.authorization.VoterScopeAuthorizer voterScopeAuthorizer;

    @Autowired
    private ElectionTypeFieldRepository fieldRepository;

    @Autowired
    private com.elecsarthi.mapper.VoterMapper voterMapper;

    @Autowired
    private java.time.Clock clock;

    @Test
    @DisplayName("Finding 13 & Point 6: Global mobile toggle consistency and fail-closed masking on DB outage")
    void testGlobalMobilePolicyDatabaseConsistency() {
        AppUser owner = userRepository.save(AppUser.builder()
                .username("mob_toggle_owner_" + UUID.randomUUID())
                .fullName("Toggle Owner")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());
        CustomerAccount custAcc = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Toggle Cust Org")
                .contactPhone("+9195" + String.format("%08d", Math.abs(new java.util.Random().nextInt(100000000))))
                .createdByUserId(owner.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());
        AppUser custUser = userRepository.save(AppUser.builder()
                .username("mob_toggle_cust_" + UUID.randomUUID())
                .fullName("Toggle Cust")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.CUSTOMER)
                .customerAccountId(custAcc.getId())
                .status(UserStatus.ACTIVE)
                .build());

        ElectionType vidhanSabha = electionTypeRepository.findByCode(ElectionTypeCode.VIDHAN_SABHA)
                .orElseThrow(() -> new IllegalStateException("VIDHAN_SABHA not seeded"));
        Dataset ds = datasetRepository.save(Dataset.builder()
                .name("Toggle Test DS")
                .originalFileName("toggle.xlsx")
                .fileSha256("sha_tog_" + UUID.randomUUID())
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .states(List.of("RAJASTHAN"))
                .deleteAfter(Instant.now().plus(30, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .build());
        jdbcTemplate.execute("SELECT create_dataset_partitions(" + ds.getId() + ")");

        byte[] aesKey = java.util.HexFormat.of().parseHex(appProperties.getSecurity().getVoterMobileAesKeyHex());
        byte[] encMobile = CryptoUtils.aesGcmEncrypt("+919876543210".getBytes(StandardCharsets.UTF_8), aesKey);
        jdbcTemplate.update(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, ac_no, part_no, v_fname_en, v_lname_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 1, 'EPIC_TOG_1', 'RAJASTHAN', 12, 1, 'AMIT', 'SHARMA', ?, ?)",
                ds.getId(), encMobile, "hash_tog_" + UUID.randomUUID());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Entitlement ent = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(custAcc.getId())
                .electionTypeId(vidhanSabha.getId())
                .datasetId(ds.getId())
                .storageTier(DatasetTier.MASTER)
                .criteria("{\"ac_no\":12,\"state\":\"RAJASTHAN\"}")
                .criteriaHash("crit_tog_" + UUID.randomUUID())
                .scopeLabel("AC 12")
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.DAYS))
                .validUntil(now.plus(5, ChronoUnit.DAYS))
                .mobileAccess(true)
                .mobileAccessChangedAt(now)
                .mobileAccessChangedBy(owner.getId())
                .createdByUserId(owner.getId())
                .createdAt(now)
                .build());

        // 1. Toggle global mobile numbers enabled -> persists GLOBAL_MOBILE_TOGGLE and returns unmasked
        voterService.updateGlobalMobileNumbersEnabled(owner.getId(), Role.OWNER, true);
        assertTrue(voterService.isGlobalMobileNumbersEnabled());

        Long toggleAuditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action_type = 'GLOBAL_MOBILE_TOGGLE'", Long.class);
        assertEquals(1L, toggleAuditCount);

        var searchReq = com.elecsarthi.dto.request.VoterSearchRequest.builder()
                .entitlementId(ent.getId()).page(0).limit(10).build();
        var unmaskedPage = voterService.searchVoters(custUser.getId(), Role.CUSTOMER, custAcc.getId(), searchReq);
        assertEquals("+919876543210", unmaskedPage.getContent().get(0).getMobileNumber());

        // 2. Simulate DB error when querying audit_log -> VoterService MUST fail closed (false) & mask voter mobile
        JdbcTemplate failingAuditJdbc = new JdbcTemplate(jdbcTemplate.getDataSource()) {
            @Override
            public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper) {
                if (sql.contains("audit_log")) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("Simulated audit_log outage");
                }
                return super.query(sql, rowMapper);
            }
        };
        com.elecsarthi.service.impl.VoterServiceImpl failingVoterService = new com.elecsarthi.service.impl.VoterServiceImpl(
                entitlementRepository,
                electionTypeRepository,
                fieldRepository,
                voterAccessAuthorizer,
                voterScopeAuthorizer,
                voterMapper,
                auditService,
                appProperties,
                failingAuditJdbc,
                clock
        );
        assertFalse(failingVoterService.isGlobalMobileNumbersEnabled(),
                "Database error checking GLOBAL_MOBILE_TOGGLE must fail closed (false)");
        var maskedPage = failingVoterService.searchVoters(custUser.getId(), Role.CUSTOMER, custAcc.getId(), searchReq);
        assertEquals("+91*****3210", maskedPage.getContent().get(0).getMobileNumber(),
                "Database outage must keep voter mobile numbers masked");

        // Reset global mobile numbers setting back to false
        voterService.updateGlobalMobileNumbersEnabled(owner.getId(), Role.OWNER, false);
    }
}
