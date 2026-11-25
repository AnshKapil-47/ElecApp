package com.elecsarthi.voter;

import com.elecsarthi.dto.request.MarkTurnoutRequest;
import com.elecsarthi.dto.request.ResetTurnoutRequest;
import com.elecsarthi.dto.response.TurnoutStatisticsResponse;
import com.elecsarthi.dto.response.VoterDetailResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.BoothAssignmentStatus;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.BoothAssignmentRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.service.TurnoutService;
import com.elecsarthi.service.VoterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class VoterScopeGeographicIsolationIntegrationTest {

    @Autowired
    private VoterService voterService;

    @Autowired
    private TurnoutService turnoutService;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private EntitlementRepository entitlementRepository;

    @Autowired
    private BoothAssignmentRepository boothAssignmentRepository;

    @Autowired
    private ElectionTypeRepository electionTypeRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AppUser admin;
    private CustomerAccount customer;
    private AppUser customerUser;
    private AppUser subuserUser;
    private Dataset dataset;
    private Entitlement entAc12;
    private Entitlement entAc13;
    private Long voterAc12Booth1Id;
    private Long voterAc12Booth2Id;
    private Long voterAc13Booth1Id;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; " +
                "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter RESTART IDENTITY CASCADE; ALTER SEQUENCE voter_id_seq RESTART WITH 1; " +
                "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;"
        );

        Instant now = Instant.now();

        admin = userRepository.save(AppUser.builder()
                .username("admin_seed")
                .fullName("Admin Seed")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("AC 12 Customer")
                .contactPhone("+919123456780")
                .status(UserStatus.ACTIVE)
                .createdByUserId(admin.getId())
                .build());

        customerUser = userRepository.save(AppUser.builder()
                .username("cust_ac12")
                .fullName("AC12 Customer User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .customerAccountId(customer.getId())
                .build());

        subuserUser = userRepository.save(AppUser.builder()
                .username("sub_ac12_b1")
                .fullName("Subuser Booth 1")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.SUBUSER)
                .status(UserStatus.ACTIVE)
                .customerAccountId(customer.getId())
                .build());

        dataset = datasetRepository.save(Dataset.builder()
                .name("State Multi-AC Dataset")
                .originalFileName("voters_all.xlsx")
                .fileSha256("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210")
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(now.plus(60, ChronoUnit.DAYS))
                .importedByUserId(admin.getId())
                .createdAt(now)
                .build());

        // Create partitions
        jdbcTemplate.execute("SELECT create_dataset_partitions(" + dataset.getId() + ");");

        // Insert voters directly into dataset partition:
        // Voter 1: AC 12, Booth 1, House 101, Serial 1
        String insSql = "INSERT INTO voter_d" + dataset.getId() + " (" +
                "dataset_id, source_record_key_hash, state, district, ac_no, part_no, booth_no, serial_no, voter_id, " +
                "v_fname_en, v_lname_en, vr_fname_en, vr_lname_en, house_no, age, sex" +
                ") VALUES (?, ?, 'MAHARASHTRA', 'PUNE', ?, ?, ?, ?, ?, ?, 'KAPIL', 'RAM', 'KAPIL', ?, 30, 'M') RETURNING id";

        voterAc12Booth1Id = jdbcTemplate.queryForObject(insSql, Long.class,
                dataset.getId(), "hash-ac12-b1-s1", 12, 1, 1, 1, "EPIC1201", "ANSH", "101");

        // Voter 2: AC 12, Booth 2, House 102, Serial 2
        voterAc12Booth2Id = jdbcTemplate.queryForObject(insSql, Long.class,
                dataset.getId(), "hash-ac12-b2-s2", 12, 2, 2, 2, "EPIC1202", "ROHIT", "102");

        // Voter 3: AC 13, Booth 1, House 101, Serial 1 (SAME BOOTH NUMBER AND HOUSE AS VOTER 1, BUT IN AC 13!)
        voterAc13Booth1Id = jdbcTemplate.queryForObject(insSql, Long.class,
                dataset.getId(), "hash-ac13-b1-s1", 13, 1, 1, 1, "EPIC1301", "VIKAS", "101");

        // Entitlement for AC 12
        entAc12 = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customer.getId())
                .datasetId(dataset.getId())
                .electionTypeId((short) 1)
                .storageTier(DatasetTier.MASTER)
                .scopeLabel("AC 12")
                .source(EntitlementSource.DIRECT)
                .createdByUserId(admin.getId())
                .criteria("{\"ac_no\":12}")
                .criteriaHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.HOURS))
                .validUntil(now.plus(30, ChronoUnit.DAYS))
                .mobileAccess(true)
                .mobileAccessChangedAt(now)
                .mobileAccessChangedBy(admin.getId())
                .build());

        // Second customer for AC 13
        CustomerAccount customerAc13 = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("AC 13 Customer")
                .contactPhone("+919123456789")
                .status(UserStatus.ACTIVE)
                .createdByUserId(admin.getId())
                .build());

        // Entitlement for AC 13 (belongs to another customer)
        entAc13 = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customerAc13.getId())
                .datasetId(dataset.getId())
                .electionTypeId((short) 1)
                .storageTier(DatasetTier.MASTER)
                .scopeLabel("AC 13")
                .source(EntitlementSource.DIRECT)
                .createdByUserId(admin.getId())
                .criteria("{\"ac_no\":13}")
                .criteriaHash("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210")
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.HOURS))
                .validUntil(now.plus(30, ChronoUnit.DAYS))
                .mobileAccess(false)
                .build());

        // Subuser assigned only to Booth 1 in AC 12
        boothAssignmentRepository.save(BoothAssignment.builder()
                .subuserId(subuserUser.getId())
                .customerAccountId(customer.getId())
                .entitlementId(entAc12.getId())
                .boothValue(1)
                .slot((short) 1)
                .assignedByUserId(admin.getId())
                .status(BoothAssignmentStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.HOURS))
                .validUntil(now.plus(6, ChronoUnit.DAYS))
                .build());
    }

    @Test
    @DisplayName("Finding 2: Geographical Isolation - Customer cannot read voters outside entitled AC via detail, family or slip")
    void testGeographicalIsolationAcrossConstituencies() {
        // Customer can read voter in their own entitled AC (AC 12)
        VoterDetailResponse detailAc12 = voterService.getVoterDetail(
                customerUser.getId(), Role.CUSTOMER, customer.getId(), entAc12.getId(), voterAc12Booth1Id);
        assertNotNull(detailAc12);
        assertEquals(voterAc12Booth1Id, detailAc12.getId());
        assertEquals("EPIC1201", detailAc12.getVoterId());

        // CRITICAL CHECK: Customer attempts to read voter in AC 13 using their AC 12 entitlement
        // Must throw ResourceNotFoundException (404), completely preventing data leakage across ACs!
        assertThrows(ResourceNotFoundException.class, () ->
                voterService.getVoterDetail(customerUser.getId(), Role.CUSTOMER, customer.getId(), entAc12.getId(), voterAc13Booth1Id));

        // CRITICAL CHECK: Family members query for AC 13 voter must fail with 404
        assertThrows(ResourceNotFoundException.class, () ->
                voterService.getFamilyMembers(customerUser.getId(), Role.CUSTOMER, customer.getId(), entAc12.getId(), voterAc13Booth1Id));

        // CRITICAL CHECK: Voter slip for AC 13 voter must fail with 404
        assertThrows(ResourceNotFoundException.class, () ->
                voterService.getVoterSlip(customerUser.getId(), Role.CUSTOMER, customer.getId(), entAc12.getId(), voterAc13Booth1Id));
    }

    @Test
    @DisplayName("Finding 2: Subuser Booth Isolation - Subuser assigned to Booth 1 cannot access Booth 2 or Booth 1 in another AC")
    void testSubuserBoothIsolation() {
        // Subuser can read voter in assigned Booth 1 of AC 12
        VoterDetailResponse b1Detail = voterService.getVoterDetail(
                subuserUser.getId(), Role.SUBUSER, customer.getId(), entAc12.getId(), voterAc12Booth1Id);
        assertNotNull(b1Detail);
        assertEquals(voterAc12Booth1Id, b1Detail.getId());

        // Subuser attempts to read voter in Booth 2 of AC 12 -> 404 / access denied
        assertThrows(ResourceNotFoundException.class, () ->
                voterService.getVoterDetail(subuserUser.getId(), Role.SUBUSER, customer.getId(), entAc12.getId(), voterAc12Booth2Id));

        // Subuser attempts to read voter in Booth 1 of AC 13 (same booth number, different AC) -> 404!
        assertThrows(ResourceNotFoundException.class, () ->
                voterService.getVoterDetail(subuserUser.getId(), Role.SUBUSER, customer.getId(), entAc12.getId(), voterAc13Booth1Id));
    }

    @Test
    @DisplayName("Finding 3: Fail-Closed Criteria - Corrupt, empty, or unapproved criteria rejects queries with 403")
    void testFailClosedEntitlementCriteria() {
        // 1. Non-object / malformed criteria (e.g. JSON array instead of JSON object)
        Entitlement corruptJsonEnt = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customer.getId())
                .datasetId(dataset.getId())
                .electionTypeId((short) 1)
                .storageTier(DatasetTier.MASTER)
                .scopeLabel("Corrupt Criteria")
                .source(EntitlementSource.DIRECT)
                .createdByUserId(admin.getId())
                .criteria("[1, 2, 3]")
                .criteriaHash("1111111111111111111111111111111111111111111111111111111111111111")
                .status(EntitlementStatus.ACTIVE)
                .validFrom(Instant.now().minus(1, ChronoUnit.HOURS))
                .validUntil(Instant.now().plus(1, ChronoUnit.DAYS))
                .build());

        ApiException ex1 = assertThrows(ApiException.class, () ->
                voterService.getVoterDetail(customerUser.getId(), Role.CUSTOMER, customer.getId(), corruptJsonEnt.getId(), voterAc12Booth1Id));
        assertEquals(403, ex1.getStatus().value(), "Non-object criteria JSON must fail-closed with 403 Forbidden");

        // 2. Empty JSON map
        Entitlement emptyMapEnt = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customer.getId())
                .datasetId(dataset.getId())
                .electionTypeId((short) 1)
                .storageTier(DatasetTier.MASTER)
                .scopeLabel("Empty Criteria")
                .source(EntitlementSource.DIRECT)
                .createdByUserId(admin.getId())
                .criteria("{}")
                .criteriaHash("2222222222222222222222222222222222222222222222222222222222222222")
                .status(EntitlementStatus.ACTIVE)
                .validFrom(Instant.now().minus(1, ChronoUnit.HOURS))
                .validUntil(Instant.now().plus(1, ChronoUnit.DAYS))
                .build());

        ApiException ex2 = assertThrows(ApiException.class, () ->
                voterService.getVoterDetail(customerUser.getId(), Role.CUSTOMER, customer.getId(), emptyMapEnt.getId(), voterAc12Booth1Id));
        assertEquals(403, ex2.getStatus().value(), "Empty criteria must fail-closed with 403 Forbidden");

        // 3. Unapproved column in criteria (SQL injection or unauthorized column probe)
        Entitlement unapprovedColEnt = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customer.getId())
                .datasetId(dataset.getId())
                .electionTypeId((short) 1)
                .storageTier(DatasetTier.MASTER)
                .scopeLabel("Unapproved Column")
                .source(EntitlementSource.DIRECT)
                .createdByUserId(admin.getId())
                .criteria("{\"unapproved_col\":\"EVIL\"}")
                .criteriaHash("3333333333333333333333333333333333333333333333333333333333333333")
                .status(EntitlementStatus.ACTIVE)
                .validFrom(Instant.now().minus(1, ChronoUnit.HOURS))
                .validUntil(Instant.now().plus(1, ChronoUnit.DAYS))
                .build());

        ApiException ex3 = assertThrows(ApiException.class, () ->
                voterService.getVoterDetail(customerUser.getId(), Role.CUSTOMER, customer.getId(), unapprovedColEnt.getId(), voterAc12Booth1Id));
        assertEquals(403, ex3.getStatus().value(), "Unapproved column in criteria must fail-closed with 403 Forbidden");
    }

    @Test
    @DisplayName("Finding 16: Turnout Scoping - Turnout mark and reset are strictly scoped to the entitlement AC")
    void testTurnoutScopingAcrossConstituencies() {
        // Customer marks turnout for voter in AC 12
        MarkTurnoutRequest markReqAc12 = MarkTurnoutRequest.builder()
                .entitlementId(entAc12.getId())
                .voterRowId(voterAc12Booth1Id)
                .build();
        assertNotNull(turnoutService.markVoted(customerUser.getId(), Role.CUSTOMER, customer.getId(), markReqAc12));

        // Turnout mark for voter in AC 13 using AC 12 entitlement must fail with 404!
        MarkTurnoutRequest markReqAc13 = MarkTurnoutRequest.builder()
                .entitlementId(entAc12.getId())
                .voterRowId(voterAc13Booth1Id)
                .build();
        assertThrows(ResourceNotFoundException.class, () ->
                turnoutService.markVoted(customerUser.getId(), Role.CUSTOMER, customer.getId(), markReqAc13));

        // Subuser in Booth 1 marks turnout for Booth 1 voter in AC 12 -> succeeds
        assertNotNull(turnoutService.markVoted(subuserUser.getId(), Role.SUBUSER, customer.getId(), markReqAc12));

        // Subuser attempts to mark turnout for Booth 2 voter in AC 12 -> rejected with 404/403
        MarkTurnoutRequest markReqAc12B2 = MarkTurnoutRequest.builder()
                .entitlementId(entAc12.getId())
                .voterRowId(voterAc12Booth2Id)
                .build();
        assertThrows(ResourceNotFoundException.class, () ->
                turnoutService.markVoted(subuserUser.getId(), Role.SUBUSER, customer.getId(), markReqAc12B2));

        // Check statistics strictly within AC 12
        TurnoutStatisticsResponse stats = turnoutService.getTurnoutStatistics(
                customerUser.getId(), Role.CUSTOMER, customer.getId(), entAc12.getId(), null);
        assertEquals(2, stats.getTotalVoters(), "AC 12 has exactly 2 voters");
        assertEquals(1, stats.getTotalVoted(), "AC 12 has exactly 1 voted");

        // Reset turnout for Booth 1 in AC 12
        ResetTurnoutRequest resetReq = ResetTurnoutRequest.builder()
                .entitlementId(entAc12.getId())
                .boothNo(1)
                .build();
        turnoutService.resetTurnout(customerUser.getId(), Role.CUSTOMER, customer.getId(), resetReq);

        TurnoutStatisticsResponse statsAfterReset = turnoutService.getTurnoutStatistics(
                customerUser.getId(), Role.CUSTOMER, customer.getId(), entAc12.getId(), null);
        assertEquals(0, statsAfterReset.getTotalVoted(), "Turnout in Booth 1 of AC 12 reset to 0");
    }
}
