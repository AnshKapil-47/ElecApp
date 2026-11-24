package com.elecsarthi.sale;

import com.elecsarthi.dto.request.*;
import com.elecsarthi.dto.response.*;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.repository.*;
import com.elecsarthi.service.EntitlementService;
import com.elecsarthi.service.ReportService;
import com.elecsarthi.service.SaleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SaleServiceIntegrationTest {

    @Autowired
    private SaleService saleService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private ElectionTypeRepository electionTypeRepository;

    @Autowired
    private AgentSaleRepository agentSaleRepository;

    @Autowired
    private AgentSaleLineRepository agentSaleLineRepository;

    @Autowired
    private EntitlementRepository entitlementRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AppUser owner;
    private AppUser subowner;
    private AppUser agent1;
    private AppUser agent2;
    private CustomerAccount customer1;
    private CustomerAccount customer2;
    private ElectionType vidhanSabha;
    private ElectionType nagarPalika;
    private Dataset datasetMaster;
    private Dataset datasetLongTerm;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
            "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; " +
            "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter CASCADE; " +
            "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;"
        );

        owner = userRepository.save(AppUser.builder()
                .username("sys_owner")
                .fullName("System Owner")
                .passwordHash(passwordEncoder.encode("OwnerPass123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        subowner = userRepository.save(AppUser.builder()
                .username("sub_owner")
                .fullName("Sub Owner")
                .passwordHash(passwordEncoder.encode("SubPass123!"))
                .role(Role.SUBOWNER)
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .build());

        agent1 = userRepository.save(AppUser.builder()
                .username("agent_one")
                .fullName("Agent One")
                .passwordHash(passwordEncoder.encode("AgentPass123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .build());

        agent2 = userRepository.save(AppUser.builder()
                .username("agent_two")
                .fullName("Agent Two")
                .passwordHash(passwordEncoder.encode("AgentPass123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .build());

        customer1 = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Candidate A")
                .contactPhone("+919876543210")
                .createdByUserId(agent1.getId())
                .owningAgentUserId(agent1.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());

        customer2 = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Candidate B")
                .contactPhone("+919876543211")
                .createdByUserId(agent2.getId())
                .owningAgentUserId(agent2.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());

        vidhanSabha = electionTypeRepository.findByCode(ElectionTypeCode.VIDHAN_SABHA)
                .orElseThrow(() -> new IllegalStateException("VIDHAN_SABHA not seeded"));

        nagarPalika = electionTypeRepository.findByCode(ElectionTypeCode.NAGAR_PALIKA)
                .orElseThrow(() -> new IllegalStateException("NAGAR_PALIKA not seeded"));

        // Setup MASTER dataset
        datasetMaster = datasetRepository.save(Dataset.builder()
                .name("Master Dataset 2026")
                .originalFileName("master_voters.xlsx")
                .fileSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .states(List.of("RAJASTHAN"))
                .deleteAfter(Instant.now().plus(60, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .build());

        jdbcTemplate.execute("SELECT create_dataset_partitions(" + datasetMaster.getId() + ");");

        // Setup LONG_TERM dataset
        datasetLongTerm = datasetRepository.save(Dataset.builder()
                .name("Long Term Dataset 2026")
                .originalFileName("lt_voters.xlsx")
                .fileSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .storageTier(DatasetTier.LONG_TERM)
                .status(DatasetStatus.IMPORTED)
                .states(List.of("RAJASTHAN"))
                .deleteAfter(Instant.now().plus(365, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .build());

        jdbcTemplate.execute("SELECT create_dataset_partitions(" + datasetLongTerm.getId() + ");");

        // Insert mock voters in MASTER dataset for Vidhan Sabha (state=RAJASTHAN, ac_no=12)
        jdbcTemplate.update(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, ac_no, part_no, v_fname_en, v_lname_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 1, 'EPIC001', 'RAJASTHAN', 12, 10, 'VIKRAM', 'RATHORE', 'enc1', '1111111111111111111111111111111111111111111111111111111111111111')",
                datasetMaster.getId());

        jdbcTemplate.update(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, ac_no, part_no, v_fname_en, v_lname_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 2, 'EPIC002', 'RAJASTHAN', 12, 10, 'SUNITA', 'RATHORE', 'enc2', '2222222222222222222222222222222222222222222222222222222222222222')",
                datasetMaster.getId());

        // Insert mock voters in LONG_TERM dataset for Vidhan Sabha (state=RAJASTHAN, ac_no=15)
        jdbcTemplate.update(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, ac_no, part_no, v_fname_en, v_lname_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 1, 'EPIC003', 'RAJASTHAN', 15, 20, 'ANIL', 'SHARMA', 'enc3', '3333333333333333333333333333333333333333333333333333333333333333')",
                datasetLongTerm.getId());
    }

    @Test
    @DisplayName("Agent Sale: Successful creation with back-to-back renewal and quantity-only verification")
    void testAgentSaleAndRenewal() {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("state", "Rajasthan");
        criteria.put("ac_no", 12);

        CreateSaleRequest request = CreateSaleRequest.builder()
                .customerAccountId(customer1.getId())
                .electionTypeId(vidhanSabha.getId())
                .storageTier(DatasetTier.MASTER)
                .idempotencyKey("sale-key-001")
                .lines(List.of(
                        SaleLineRequest.builder()
                                .criteria(criteria)
                                .durationValue(10)
                                .durationUnit(DurationUnit.DAY)
                                .build()
                ))
                .build();

        // 1. First sale
        SaleResponse saleResp1 = saleService.createSale(agent1.getId(), request);
        assertNotNull(saleResp1.getId());
        assertEquals(SaleStatus.COMPLETED, saleResp1.getStatus());
        assertEquals(1, saleResp1.getLines().size());
        SaleLineResponse line1 = saleResp1.getLines().get(0);
        assertEquals(2, line1.getVoterCountSnapshot());
        assertNotNull(line1.getEntitlementId());

        // Verify entitlement created with SALE source
        Entitlement ent1 = entitlementRepository.findById(line1.getEntitlementId()).orElseThrow();
        assertEquals(EntitlementSource.SALE, ent1.getSource());
        assertEquals(EntitlementStatus.ACTIVE, ent1.getStatus());
        assertFalse(ent1.isMobileAccess());

        // 2. Second sale (Back-to-back renewal for same customer & area)
        CreateSaleRequest renewalRequest = CreateSaleRequest.builder()
                .customerAccountId(customer1.getId())
                .electionTypeId(vidhanSabha.getId())
                .storageTier(DatasetTier.MASTER)
                .idempotencyKey("sale-key-002")
                .lines(List.of(
                        SaleLineRequest.builder()
                                .criteria(criteria)
                                .durationValue(1)
                                .durationUnit(DurationUnit.MONTH)
                                .build()
                ))
                .build();

        SaleResponse saleResp2 = saleService.createSale(agent1.getId(), renewalRequest);
        SaleLineResponse line2 = saleResp2.getLines().get(0);

        // Verification: validFrom of renewal MUST match validUntil of first entitlement!
        assertEquals(line1.getValidUntil().truncatedTo(ChronoUnit.MICROS), line2.getValidFrom().truncatedTo(ChronoUnit.MICROS), "Back-to-back renewal must start exactly when prior entitlement ends");
        assertTrue(line2.getValidUntil().isAfter(line2.getValidFrom()));
    }

    @Test
    @DisplayName("Idempotency: Replay returns identical sale; payload change throws ConflictException")
    void testIdempotencyHandling() {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("state", "RAJASTHAN");
        criteria.put("ac_no", 12);

        CreateSaleRequest request = CreateSaleRequest.builder()
                .customerAccountId(customer1.getId())
                .electionTypeId(vidhanSabha.getId())
                .storageTier(DatasetTier.MASTER)
                .idempotencyKey("idemp-key-xyz")
                .lines(List.of(
                        SaleLineRequest.builder()
                                .criteria(criteria)
                                .durationValue(7)
                                .durationUnit(DurationUnit.DAY)
                                .build()
                ))
                .build();

        SaleResponse first = saleService.createSale(agent1.getId(), request);
        assertNotNull(first.getId());

        // Replay with identical payload and same key
        SaleResponse replay = saleService.createSale(agent1.getId(), request);
        assertEquals(first.getId(), replay.getId(), "Replay must return existing sale");
        assertEquals(first.getLines().get(0).getId(), replay.getLines().get(0).getId());

        // Replay with DIFFERENT payload and SAME key -> must throw 409 ConflictException
        CreateSaleRequest conflictingRequest = CreateSaleRequest.builder()
                .customerAccountId(customer1.getId())
                .electionTypeId(vidhanSabha.getId())
                .storageTier(DatasetTier.MASTER)
                .idempotencyKey("idemp-key-xyz")
                .lines(List.of(
                        SaleLineRequest.builder()
                                .criteria(criteria)
                                .durationValue(14) // Changed duration
                                .durationUnit(DurationUnit.DAY)
                                .build()
                ))
                .build();

        assertThrows(ConflictException.class, () -> saleService.createSale(agent1.getId(), conflictingRequest));
    }

    @Test
    @DisplayName("Security & Access Rules: Owning agent check, subowner selling prohibition, suspended customer")
    void testSaleSecurityAndAccessRules() {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("state", "RAJASTHAN");
        criteria.put("ac_no", 12);

        CreateSaleRequest request = CreateSaleRequest.builder()
                .customerAccountId(customer1.getId()) // Owned by agent1
                .electionTypeId(vidhanSabha.getId())
                .storageTier(DatasetTier.MASTER)
                .idempotencyKey("cross-agent-key")
                .lines(List.of(
                        SaleLineRequest.builder()
                                .criteria(criteria)
                                .durationValue(5)
                                .durationUnit(DurationUnit.DAY)
                                .build()
                ))
                .build();

        // 1. Agent 2 attempts to sell to Customer 1 -> 403 FORBIDDEN
        ApiException ex1 = assertThrows(ApiException.class, () -> saleService.createSale(agent2.getId(), request));
        assertEquals(com.elecsarthi.exception.errorcode.ErrorCode.FORBIDDEN, ex1.getErrorCode());

        // 2. Subowner attempts to sell -> 403 FORBIDDEN
        ApiException ex2 = assertThrows(ApiException.class, () -> saleService.createSale(subowner.getId(), request));
        assertEquals(com.elecsarthi.exception.errorcode.ErrorCode.FORBIDDEN, ex2.getErrorCode());

        // 3. Selling to suspended customer -> 400 ACCOUNT_SUSPENDED
        customer1.setStatus(UserStatus.SUSPENDED);
        customerAccountRepository.save(customer1);

        ApiException ex3 = assertThrows(ApiException.class, () -> saleService.createSale(agent1.getId(), request));
        assertEquals(com.elecsarthi.exception.errorcode.ErrorCode.ACCOUNT_SUSPENDED, ex3.getErrorCode());
    }

    @Test
    @DisplayName("Ambiguity Guard: Detects and rejects areas with multiple distinct guard column values")
    void testAreaAmbiguityGuard() {
        // Nagar Palika requires: state, district, nagar_palika_ward_no
        // Guard column is: municipal_name_nagar_palika_en
        jdbcTemplate.update(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, district, nagar_palika_ward_no, municipal_name_nagar_palika_en, booth_no, v_fname_en, v_lname_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 101, 'NP001', 'RAJASTHAN', 'JAIPUR', 5, 'PALIKA_ALPHA', 1, 'RAM', 'LAL', 'enc', '4444444444444444444444444444444444444444444444444444444444444444')",
                datasetMaster.getId());

        jdbcTemplate.update(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, district, nagar_palika_ward_no, municipal_name_nagar_palika_en, booth_no, v_fname_en, v_lname_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 102, 'NP002', 'RAJASTHAN', 'JAIPUR', 5, 'PALIKA_BETA', 1, 'SHYAM', 'LAL', 'enc', '5555555555555555555555555555555555555555555555555555555555555555')",
                datasetMaster.getId());

        Map<String, Object> npCriteria = new HashMap<>();
        npCriteria.put("state", "RAJASTHAN");
        npCriteria.put("district", "JAIPUR");
        npCriteria.put("nagar_palika_ward_no", 5);

        CreateSaleRequest request = CreateSaleRequest.builder()
                .customerAccountId(customer1.getId())
                .electionTypeId(nagarPalika.getId())
                .storageTier(DatasetTier.MASTER)
                .idempotencyKey("ambig-sale-01")
                .lines(List.of(
                        SaleLineRequest.builder()
                                .criteria(npCriteria)
                                .durationValue(7)
                                .durationUnit(DurationUnit.DAY)
                                .build()
                ))
                .build();

        ApiException ex = assertThrows(ApiException.class, () -> saleService.createSale(agent1.getId(), request));
        assertEquals(com.elecsarthi.exception.errorcode.ErrorCode.AMBIGUOUS_AREA, ex.getErrorCode());
    }

    @Test
    @DisplayName("Direct Entitlement & Voiding: Owner direct grant, mobile access toggle, and sale voiding")
    void testDirectEntitlementAndVoiding() {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("state", "RAJASTHAN");
        criteria.put("ac_no", 12);

        // 1. Subowner attempts to grant direct entitlement with mobileAccess=true -> FORBIDDEN
        DirectEntitlementRequest directReqSubowner = DirectEntitlementRequest.builder()
                .customerAccountId(customer1.getId())
                .electionTypeId(vidhanSabha.getId())
                .storageTier(DatasetTier.MASTER)
                .criteria(criteria)
                .durationValue(7)
                .durationUnit(DurationUnit.DAY)
                .mobileAccess(true)
                .build();

        ApiException ex = assertThrows(ApiException.class, () ->
                entitlementService.createDirectEntitlement(subowner.getId(), subowner.getRole(), directReqSubowner));
        assertEquals(com.elecsarthi.exception.errorcode.ErrorCode.FORBIDDEN, ex.getErrorCode());

        // 2. Owner creates direct entitlement (with mobileAccess=true) -> Success
        DirectEntitlementRequest directReqOwner = DirectEntitlementRequest.builder()
                .customerAccountId(customer1.getId())
                .electionTypeId(vidhanSabha.getId())
                .storageTier(DatasetTier.MASTER)
                .criteria(criteria)
                .durationValue(7)
                .durationUnit(DurationUnit.DAY)
                .mobileAccess(true)
                .build();

        EntitlementResponse directEnt = entitlementService.createDirectEntitlement(
                owner.getId(), owner.getRole(), directReqOwner);
        assertNotNull(directEnt.getId());
        assertEquals(EntitlementSource.DIRECT, directEnt.getSource());
        assertNull(directEnt.getSaleLineId());
        assertTrue(directEnt.isMobileAccess());

        // 3. Create a normal Agent Sale and then Owner voids it
        Map<String, Object> ltCriteria = new HashMap<>();
        ltCriteria.put("state", "RAJASTHAN");
        ltCriteria.put("ac_no", 15);

        CreateSaleRequest saleReq = CreateSaleRequest.builder()
                .customerAccountId(customer1.getId())
                .electionTypeId(vidhanSabha.getId())
                .storageTier(DatasetTier.LONG_TERM)
                .idempotencyKey("sale-to-void-01")
                .lines(List.of(
                        SaleLineRequest.builder()
                                .criteria(ltCriteria)
                                .durationValue(14)
                                .durationUnit(DurationUnit.DAY)
                                .build()
                ))
                .build();

        SaleResponse saleResp = saleService.createSale(agent1.getId(), saleReq);
        Long saleId = saleResp.getId();
        Long entId = saleResp.getLines().get(0).getEntitlementId();

        int policyVersionBefore = customerAccountRepository.findById(customer1.getId()).orElseThrow().getMobileAccessPolicyVersion();

        // Agent attempts to void -> FORBIDDEN
        assertThrows(ApiException.class, () ->
                saleService.voidSale(agent1.getId(), saleId, VoidSaleRequest.builder().voidReason("Mistake").build()));

        // Owner voids sale -> Success
        SaleResponse voided = saleService.voidSale(owner.getId(), saleId,
                VoidSaleRequest.builder().voidReason("Customer canceled order").build());

        assertEquals(SaleStatus.VOIDED, voided.getStatus());

        // Entitlement must be REVOKED
        Entitlement revokedEnt = entitlementRepository.findById(entId).orElseThrow();
        assertEquals(EntitlementStatus.REVOKED, revokedEnt.getStatus());
        assertNotNull(revokedEnt.getRevokedAt());

        // Customer's mobile access policy version bumped
        int policyVersionAfter = customerAccountRepository.findById(customer1.getId()).orElseThrow().getMobileAccessPolicyVersion();
        assertEquals(policyVersionBefore + 1, policyVersionAfter);
    }

    @Test
    @DisplayName("Quantity-only Sales Report: Aggregates verified, Subowner forbidden, zero monetary fields")
    void testQuantityOnlySalesReport() {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("state", "RAJASTHAN");
        criteria.put("ac_no", 12);

        saleService.createSale(agent1.getId(), CreateSaleRequest.builder()
                .customerAccountId(customer1.getId())
                .electionTypeId(vidhanSabha.getId())
                .storageTier(DatasetTier.MASTER)
                .idempotencyKey("rep-sale-1")
                .lines(List.of(
                        SaleLineRequest.builder()
                                .criteria(criteria)
                                .durationValue(5)
                                .durationUnit(DurationUnit.DAY)
                                .build()
                ))
                .build());

        // Subowner is strictly forbidden from viewing sales reports
        ApiException exSubowner = assertThrows(ApiException.class, () ->
                reportService.getSalesReport(subowner.getId(), subowner.getRole(), null, null, null));
        assertEquals(com.elecsarthi.exception.errorcode.ErrorCode.FORBIDDEN, exSubowner.getErrorCode());

        // Owner gets full report
        SalesReportResponse ownerReport = reportService.getSalesReport(owner.getId(), owner.getRole(), null, null, null);
        assertNotNull(ownerReport);
        assertEquals(1, ownerReport.getTotalSales());
        assertEquals(1, ownerReport.getTotalLines());
        assertEquals(2, ownerReport.getTotalVoters());
        assertEquals(1, ownerReport.getTotalCustomersAcquired());
        assertFalse(ownerReport.getElectionTypeBreakdown().isEmpty());
        assertFalse(ownerReport.getScopeBreakdown().isEmpty());
        assertEquals(1, ownerReport.getRecords().size());

        // Agent gets own report
        SalesReportResponse agent1Report = reportService.getSalesReport(agent1.getId(), agent1.getRole(), null, null, null);
        assertEquals(1, agent1Report.getTotalSales());

        SalesReportResponse agent2Report = reportService.getSalesReport(agent2.getId(), agent2.getRole(), null, null, null);
        assertEquals(0, agent2Report.getTotalSales());
    }
}
