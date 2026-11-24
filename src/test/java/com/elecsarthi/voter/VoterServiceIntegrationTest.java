package com.elecsarthi.voter;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.MarkTurnoutRequest;
import com.elecsarthi.dto.request.StartSyncSessionRequest;
import com.elecsarthi.dto.request.UnmarkTurnoutRequest;
import com.elecsarthi.dto.request.VoterSearchRequest;
import com.elecsarthi.dto.response.SyncChunkResponse;
import com.elecsarthi.dto.response.SyncSessionResponse;
import com.elecsarthi.dto.response.TurnoutResponse;
import com.elecsarthi.dto.response.TurnoutStatisticsResponse;
import com.elecsarthi.dto.response.VoterDetailResponse;
import com.elecsarthi.dto.response.VoterSlipResponse;
import com.elecsarthi.dto.response.VoterSummaryResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.BoothAssignmentStatus;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.TurnoutSource;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.BoothAssignmentRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.TurnoutService;
import com.elecsarthi.service.VoterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class VoterServiceIntegrationTest {

    @Autowired
    private VoterService voterService;

    @Autowired
    private TurnoutService turnoutService;

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
    private BoothAssignmentRepository boothAssignmentRepository;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AppUser owner;
    private AppUser subowner;
    private AppUser agent;
    private CustomerAccount customer1;
    private AppUser customerUser1;
    private AppUser subuser1;
    private ElectionType vidhanSabha;
    private Dataset dataset;
    private Entitlement entitlement;
    private Long voter1Id;
    private Long voter2Id;
    private Long voter3Id;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
            "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; " +
            "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter, voter_turnout CASCADE; " +
            "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;"
        );

        appProperties.setMobileNumbersEnabled(false);

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

        agent = userRepository.save(AppUser.builder()
                .username("agent_user")
                .fullName("Agent User")
                .passwordHash(passwordEncoder.encode("AgentPass123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .build());

        customer1 = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Candidate Jaipur")
                .contactPhone("+919876543210")
                .createdByUserId(agent.getId())
                .owningAgentUserId(agent.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());

        customerUser1 = userRepository.save(AppUser.builder()
                .username("cust_user1")
                .fullName("Candidate User")
                .passwordHash(passwordEncoder.encode("CustPass123!"))
                .role(Role.CUSTOMER)
                .customerAccountId(customer1.getId())
                .status(UserStatus.ACTIVE)
                .build());

        subuser1 = userRepository.save(AppUser.builder()
                .username("sub_user1")
                .fullName("Booth Worker 1")
                .passwordHash(passwordEncoder.encode("SubUserPass123!"))
                .role(Role.SUBUSER)
                .customerAccountId(customer1.getId())
                .status(UserStatus.ACTIVE)
                .createdByUserId(customerUser1.getId())
                .build());

        vidhanSabha = electionTypeRepository.findByCode(ElectionTypeCode.VIDHAN_SABHA)
                .orElseThrow(() -> new IllegalStateException("VIDHAN_SABHA not seeded"));

        dataset = datasetRepository.save(Dataset.builder()
                .name("Vidhan Sabha Dataset 2026")
                .originalFileName("vs_data.xlsx")
                .fileSha256("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .states(List.of("RAJASTHAN"))
                .deleteAfter(Instant.now().plus(60, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .build());

        jdbcTemplate.execute("SELECT create_dataset_partitions(" + dataset.getId() + ");");

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Active entitlement: RAJASTHAN, ac_no=12
        entitlement = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customer1.getId())
                .electionTypeId(vidhanSabha.getId())
                .datasetId(dataset.getId())
                .storageTier(DatasetTier.MASTER)
                .criteria("{\"ac_no\":12,\"state\":\"RAJASTHAN\"}")
                .criteriaHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .scopeLabel("Rajasthan, AC 12")
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.DAYS))
                .validUntil(now.plus(10, ChronoUnit.DAYS))
                .mobileAccess(true)
                .mobileAccessChangedAt(now)
                .mobileAccessChangedBy(owner.getId())
                .createdByUserId(owner.getId())
                .createdAt(now)
                .build());

        // Assign Subuser1 to Part 10 (Slot 1)
        boothAssignmentRepository.save(BoothAssignment.builder()
                .customerAccountId(customer1.getId())
                .subuserId(subuser1.getId())
                .entitlementId(entitlement.getId())
                .boothValue(10) // Part 10
                .slot((short) 1)
                .status(BoothAssignmentStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.DAYS))
                .validUntil(now.plus(5, ChronoUnit.DAYS))
                .assignedByUserId(customerUser1.getId())
                .createdAt(now)
                .build());

        // Encrypt test mobile number using AES-GCM
        byte[] aesKey = HexFormat.of().parseHex(appProperties.getSecurity().getVoterMobileAesKeyHex());
        byte[] encMobile1 = CryptoUtils.aesGcmEncrypt("9876543210".getBytes(StandardCharsets.UTF_8), aesKey);
        byte[] encMobile2 = CryptoUtils.aesGcmEncrypt("9876543211".getBytes(StandardCharsets.UTF_8), aesKey);

        // Insert Voter 1: Part 10, House 101, Male, Age 35
        voter1Id = jdbcTemplate.queryForObject(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, ac_no, part_no, v_fname_en, v_lname_en, vr_fname_en, house_no, age, sex, booth_name_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 1, 'EPIC1001', 'RAJASTHAN', 12, 10, 'AMIT', 'SHARMA', 'RAMESH SHARMA', '101', 35, 'M', 'Govt School Room 1', ?, '1111111111111111111111111111111111111111111111111111111111111111') RETURNING id",
                Long.class, dataset.getId(), encMobile1);

        // Insert Voter 2: Part 10, House 101 (Family of Voter 1), Female, Age 32
        voter2Id = jdbcTemplate.queryForObject(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, ac_no, part_no, v_fname_en, v_lname_en, vr_fname_en, house_no, age, sex, booth_name_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 2, 'EPIC1002', 'RAJASTHAN', 12, 10, 'PRIYA', 'SHARMA', 'AMIT SHARMA', '101', 32, 'F', 'Govt School Room 1', ?, '2222222222222222222222222222222222222222222222222222222222222222') RETURNING id",
                Long.class, dataset.getId(), encMobile2);

        // Insert Voter 3: Part 20 (Unassigned to Subuser 1), House 205, Male, Age 40
        voter3Id = jdbcTemplate.queryForObject(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, ac_no, part_no, v_fname_en, v_lname_en, vr_fname_en, house_no, age, sex, booth_name_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 1, 'EPIC2001', 'RAJASTHAN', 12, 20, 'RAJESH', 'VERMA', 'SURESH VERMA', '205', 40, 'M', 'Govt School Room 2', ?, '3333333333333333333333333333333333333333333333333333333333333333') RETURNING id",
                Long.class, dataset.getId(), encMobile1);
    }

    @Test
    @DisplayName("Central Voter Access Gate: Admins and Agents strictly forbidden; Customer & Subuser authorized")
    void testCentralAccessGate() {
        VoterSearchRequest searchReq = VoterSearchRequest.builder()
                .entitlementId(entitlement.getId())
                .build();

        // 1. Owner forbidden
        ApiException exOwner = assertThrows(ApiException.class, () ->
                voterService.searchVoters(owner.getId(), Role.OWNER, null, searchReq));
        assertEquals(ErrorCode.FORBIDDEN, exOwner.getErrorCode());

        // 2. Subowner forbidden
        ApiException exSubowner = assertThrows(ApiException.class, () ->
                voterService.searchVoters(subowner.getId(), Role.SUBOWNER, null, searchReq));
        assertEquals(ErrorCode.FORBIDDEN, exSubowner.getErrorCode());

        // 3. Agent forbidden
        ApiException exAgent = assertThrows(ApiException.class, () ->
                voterService.searchVoters(agent.getId(), Role.AGENT, null, searchReq));
        assertEquals(ErrorCode.FORBIDDEN, exAgent.getErrorCode());

        // 4. Customer permitted
        Page<VoterSummaryResponse> custResult = voterService.searchVoters(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), searchReq);
        assertEquals(3, custResult.getTotalElements(), "Customer sees all voters across entitlement area");

        // 5. Subuser permitted but restricted to assigned booth (Part 10 only)
        Page<VoterSummaryResponse> subuserResult = voterService.searchVoters(
                subuser1.getId(), Role.SUBUSER, customer1.getId(), searchReq);
        assertEquals(2, subuserResult.getTotalElements(), "Subuser only sees voters in assigned booth 10");
        assertTrue(subuserResult.getContent().stream().allMatch(v -> v.getPartNo() == 10));
    }

    @Test
    @DisplayName("Mobile Number Protection: 3-gate condition (global enabled + Customer role + mobile_access flag)")
    void testMobileNumberProtection() {
        // Gate 1: Default (global switch false) -> Masked mobile number for Customer
        appProperties.setMobileNumbersEnabled(false);
        VoterDetailResponse detailMasked = voterService.getVoterDetail(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), entitlement.getId(), voter1Id);
        assertNotNull(detailMasked.getMobileNumber());
        assertTrue(detailMasked.getMobileNumber().contains("*****"), "Should be masked when global switch is false");
        assertEquals("+91*****3210", detailMasked.getMobileNumber());

        // Gate 2: Global switch enabled + Customer role + mobile_access = true -> Unmasked decrypted plaintext
        appProperties.setMobileNumbersEnabled(true);
        VoterDetailResponse detailUnmasked = voterService.getVoterDetail(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), entitlement.getId(), voter1Id);
        assertEquals("9876543210", detailUnmasked.getMobileNumber(), "Customer sees decrypted mobile number when all 3 gates are open");

        // Gate 3: Subuser NEVER sees unmasked mobile number, even when global switch and mobile_access are on
        VoterDetailResponse detailSubuser = voterService.getVoterDetail(
                subuser1.getId(), Role.SUBUSER, customer1.getId(), entitlement.getId(), voter1Id);
        assertNotNull(detailSubuser.getMobileNumber());
        assertTrue(detailSubuser.getMobileNumber().contains("*****"), "Subuser must NEVER see unmasked mobile number");
    }

    @Test
    @DisplayName("Family Matching: Clusters voters sharing same booth and house number")
    void testFamilyMatching() {
        List<VoterSummaryResponse> family = voterService.getFamilyMembers(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), entitlement.getId(), voter1Id);

        assertEquals(2, family.size(), "Voter 1 and Voter 2 share House 101 in Booth 10");
        Set<String> epics = new HashSet<>(List.of(family.get(0).getVoterId(), family.get(1).getVoterId()));
        assertTrue(epics.contains("EPIC1001"));
        assertTrue(epics.contains("EPIC1002"));
    }

    @Test
    @DisplayName("Voter Slip Generation: Returns structured voter slip with on_slip field metadata")
    void testVoterSlip() {
        VoterSlipResponse slip = voterService.getVoterSlip(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), entitlement.getId(), voter1Id);

        assertNotNull(slip);
        assertEquals("EPIC1001", slip.getVoterId());
        assertEquals("AMIT SHARMA", slip.getFullNameEn());
        assertEquals("RAMESH SHARMA", slip.getRelationNameEn());
        assertEquals("101", slip.getHouseNo());
        assertEquals(35, slip.getAge());
        assertEquals("M", slip.getGender());
        assertEquals(10, slip.getPartNo());
        assertFalse(slip.getSlipFields().isEmpty(), "Slip fields from election_type_field should be populated");
    }

    @Test
    @DisplayName("Turnout Engine: Marking, conflict idempotency, subuser scope check, Customer unmark, and statistics")
    void testTurnoutEngine() {
        // 1. Subuser marks turnout in assigned booth 10 -> Success
        TurnoutResponse tResp = turnoutService.markVoted(
                subuser1.getId(), Role.SUBUSER, customer1.getId(),
                MarkTurnoutRequest.builder().entitlementId(entitlement.getId()).voterRowId(voter1Id).build());

        assertTrue(tResp.isVoted());
        assertEquals(TurnoutSource.ONLINE, tResp.getSource());

        // 2. Duplicate mark -> Idempotent ON CONFLICT DO NOTHING
        TurnoutResponse tRespReplay = turnoutService.markVoted(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(),
                MarkTurnoutRequest.builder().entitlementId(entitlement.getId()).voterRowId(voter1Id).build());
        assertTrue(tRespReplay.isVoted());

        // 3. Subuser attempts to mark voter in unassigned booth 20 -> FORBIDDEN / NOT FOUND
        ApiException exSubBooth = assertThrows(ApiException.class, () ->
                turnoutService.markVoted(subuser1.getId(), Role.SUBUSER, customer1.getId(),
                        MarkTurnoutRequest.builder().entitlementId(entitlement.getId()).voterRowId(voter3Id).build()));
        assertTrue(exSubBooth.getErrorCode() == ErrorCode.FORBIDDEN || exSubBooth.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND);

        // 4. Verify voter search reflects turnout status
        VoterSearchRequest searchVoted = VoterSearchRequest.builder()
                .entitlementId(entitlement.getId())
                .voted(true)
                .build();
        Page<VoterSummaryResponse> votedPage = voterService.searchVoters(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), searchVoted);
        assertEquals(1, votedPage.getTotalElements());
        assertEquals(voter1Id, votedPage.getContent().get(0).getId());
        assertTrue(votedPage.getContent().get(0).isVoted());

        // 5. Turnout statistics
        TurnoutStatisticsResponse stats = turnoutService.getTurnoutStatistics(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), entitlement.getId(), null);
        assertNotNull(stats);
        assertEquals(3, stats.getTotalVoters());
        assertEquals(1, stats.getTotalVoted());
        assertEquals(33.33, stats.getTurnoutPercentage(), 0.1);
        assertFalse(stats.getBoothBreakdown().isEmpty());
        assertFalse(stats.getGenderBreakdown().isEmpty());

        // 6. Subuser attempts to unmark turnout -> Strictly FORBIDDEN
        ApiException exUnmarkSub = assertThrows(ApiException.class, () ->
                turnoutService.unmarkVoted(subuser1.getId(), Role.SUBUSER, customer1.getId(),
                        UnmarkTurnoutRequest.builder().entitlementId(entitlement.getId()).voterRowId(voter1Id).build()));
        assertEquals(ErrorCode.FORBIDDEN, exUnmarkSub.getErrorCode());

        // 7. Customer unmarks turnout -> Success
        turnoutService.unmarkVoted(customerUser1.getId(), Role.CUSTOMER, customer1.getId(),
                UnmarkTurnoutRequest.builder().entitlementId(entitlement.getId()).voterRowId(voter1Id).build());

        TurnoutStatisticsResponse statsAfter = turnoutService.getTurnoutStatistics(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), entitlement.getId(), null);
        assertEquals(0, statsAfter.getTotalVoted());
    }

    @Autowired
    private com.elecsarthi.service.SyncService syncService;

    @Autowired
    private UserDeviceRepository deviceRepository;

    private List<String> mobileNumbersSeenBy(AppUser user, Role role) {
        VoterSearchRequest request = VoterSearchRequest.builder().entitlementId(entitlement.getId()).page(0).limit(10).build();
        List<String> numbers = new ArrayList<>();
        voterService.searchVoters(user.getId(), role, customer1.getId(), request)
                .forEach(voter -> numbers.add(voter.getMobileNumber()));
        voterService.getFamilyMembers(user.getId(), role, customer1.getId(), entitlement.getId(), voter1Id)
                .forEach(voter -> numbers.add(voter.getMobileNumber()));
        numbers.add(voterService.getVoterDetail(user.getId(), role, customer1.getId(), entitlement.getId(), voter1Id).getMobileNumber());
        return numbers;
    }

    @Test
    @DisplayName("Mobile numbers: search, family and detail unmask only for a Customer with all three gates open")
    void testMobileGatesApplyOnEveryVoterPath() {
        appProperties.setMobileNumbersEnabled(true);

        List<String> customerView = mobileNumbersSeenBy(customerUser1, Role.CUSTOMER);
        assertFalse(customerView.isEmpty());
        assertTrue(customerView.stream().noneMatch(n -> n == null || n.contains("*")),
                "Customer sees plain numbers when the switch, the role and the entitlement allow it");

        List<String> subuserView = mobileNumbersSeenBy(subuser1, Role.SUBUSER);
        assertFalse(subuserView.isEmpty());
        assertTrue(subuserView.stream().allMatch(n -> n != null && n.contains("*****")),
                "A Subuser never sees an unmasked number, whatever the switch and entitlement say");

        jdbcTemplate.update("UPDATE entitlement SET mobile_access = false WHERE id = ?", entitlement.getId());
        assertTrue(mobileNumbersSeenBy(customerUser1, Role.CUSTOMER).stream().allMatch(n -> n != null && n.contains("*****")),
                "Without mobile_access on the entitlement the Customer's numbers stay masked");

        jdbcTemplate.update("UPDATE entitlement SET mobile_access = true WHERE id = ?", entitlement.getId());
        appProperties.setMobileNumbersEnabled(false);
        assertTrue(mobileNumbersSeenBy(customerUser1, Role.CUSTOMER).stream().allMatch(n -> n != null && n.contains("*****")),
                "With the global switch off the Customer's numbers stay masked");
    }

    @Test
    @DisplayName("Bulk sync: chunk records follow the same three mobile-number gates as online reads")
    void testSyncChunkAppliesMobileGates() {
        Instant now = Instant.now();
        UserDevice device = deviceRepository.save(UserDevice.builder()
                .userId(customerUser1.getId())
                .customerAccountId(customer1.getId())
                .userRole(Role.CUSTOMER)
                .platform(DevicePlatform.ANDROID)
                .deviceName("Customer Phone")
                .installationId("inst-voter-sync")
                .publicKey("pk-voter-sync")
                .status(DeviceStatus.ACTIVE)
                .activatedAt(now)
                .lastUsedAt(now)
                .lastRequestCounter(0L)
                .createdAt(now)
                .build());

        for (boolean globalSwitch : new boolean[]{true, false}) {
            appProperties.setMobileNumbersEnabled(globalSwitch);
            SyncSessionResponse session = syncService.startSyncSession(customerUser1.getId(), Role.CUSTOMER, customer1.getId(),
                    device.getId(), StartSyncSessionRequest.builder().entitlementId(entitlement.getId()).build());
            assertEquals(globalSwitch, session.isIncludesMobile());

            SyncChunkResponse chunk = syncService.getChunk(customerUser1.getId(), Role.CUSTOMER, customer1.getId(), session.getSessionId(), 0);
            assertFalse(chunk.getRecords().isEmpty());
            for (VoterSummaryResponse record : chunk.getRecords()) {
                assertEquals(globalSwitch, !record.getMobileNumber().contains("*"),
                        "Sync must unmask exactly when the online read would (switch " + globalSwitch + ")");
            }
        }
    }

    @Test
    @DisplayName("Voter responses never expose dataset internals")
    void testVoterResponsesExposeNoDatasetInternals() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        VoterSearchRequest request = VoterSearchRequest.builder().entitlementId(entitlement.getId()).page(0).limit(10).build();

        List<Object> responses = new ArrayList<>();
        responses.addAll(voterService.searchVoters(customerUser1.getId(), Role.CUSTOMER, customer1.getId(), request).getContent());
        responses.addAll(voterService.getFamilyMembers(customerUser1.getId(), Role.CUSTOMER, customer1.getId(), entitlement.getId(), voter1Id));
        responses.add(voterService.getVoterDetail(customerUser1.getId(), Role.CUSTOMER, customer1.getId(), entitlement.getId(), voter1Id));
        responses.add(voterService.getVoterSlip(customerUser1.getId(), Role.CUSTOMER, customer1.getId(), entitlement.getId(), voter1Id));

        for (Object response : responses) {
            String body = json.writeValueAsString(response).toLowerCase();
            assertFalse(body.contains("dataset"), "Response must not mention a dataset: " + body);
        }
    }
}
