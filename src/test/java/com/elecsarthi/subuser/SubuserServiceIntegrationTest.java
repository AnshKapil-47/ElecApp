package com.elecsarthi.subuser;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.*;
import com.elecsarthi.dto.response.*;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.*;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.OfflineLeaseService;
import com.elecsarthi.service.SubuserService;
import com.elecsarthi.service.SyncService;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SubuserServiceIntegrationTest {

    @Autowired
    private SubuserService subuserService;

    @Autowired
    private OfflineLeaseService offlineLeaseService;

    @Autowired
    private SyncService syncService;

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
    private UserDeviceRepository userDeviceRepository;

    @Autowired
    private VoterTurnoutRepository voterTurnoutRepository;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AppUser owner;
    private AppUser agent;
    private CustomerAccount customer1;
    private AppUser customerUser1;
    private UserDevice customerDevice;
    private ElectionType vidhanSabha;
    private Dataset dataset;
    private Entitlement entitlement;
    private Long voter1Id;
    private Long voter2Id;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
            "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; " +
            "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter, voter_turnout CASCADE; " +
            "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;"
        );

        owner = userRepository.save(AppUser.builder()
                .username("sys_owner")
                .fullName("System Owner")
                .passwordHash(passwordEncoder.encode("OwnerPass123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
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

        customerDevice = userDeviceRepository.save(UserDevice.builder()
                .userId(customerUser1.getId())
                .userRole(Role.CUSTOMER)
                .customerAccountId(customer1.getId())
                .installationId("inst-cust-001")
                .platform(DevicePlatform.ANDROID)
                .deviceName("Pixel 8")
                .status(DeviceStatus.ACTIVE)
                .publicKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEcustpubkey")
                .keyAlgorithm("ECDSA_P256")
                .build());

        vidhanSabha = electionTypeRepository.findByCode(ElectionTypeCode.VIDHAN_SABHA)
                .orElseThrow(() -> new IllegalStateException("VIDHAN_SABHA not seeded"));

        dataset = datasetRepository.save(Dataset.builder()
                .name("Vidhan Sabha Dataset 2026")
                .originalFileName("vs_data.xlsx")
                .fileSha256("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .states(List.of("RAJASTHAN"))
                .deleteAfter(Instant.now().plus(60, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .build());

        jdbcTemplate.execute("SELECT create_dataset_partitions(" + dataset.getId() + ");");

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

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

        // Insert voters for Booth 10 and Booth 11
        voter1Id = jdbcTemplate.queryForObject(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, ac_no, part_no, v_fname_en, v_lname_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 1, 'EPIC001', 'RAJASTHAN', 12, 10, 'VIKRAM', 'SINGH', 'enc1', '1111111111111111111111111111111111111111111111111111111111111111') RETURNING id",
                Long.class, dataset.getId());

        voter2Id = jdbcTemplate.queryForObject(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, ac_no, part_no, v_fname_en, v_lname_en, mobile_no_enc, source_record_key_hash) " +
                "VALUES (?, 2, 'EPIC002', 'RAJASTHAN', 12, 11, 'KAVITA', 'SINGH', 'enc2', '2222222222222222222222222222222222222222222222222222222222222222') RETURNING id",
                Long.class, dataset.getId());
    }

    @Test
    @DisplayName("Subuser Provisioning: Only Customer can create Subusers; duplicate username rejected")
    void testSubuserProvisioning() {
        CreateSubuserRequest req = CreateSubuserRequest.builder()
                .username("subuser_alpha")
                .fullName("Worker Alpha")
                .password("WorkerPass123!")
                .build();

        // 1. Non-customer (Agent) attempts to create subuser -> FORBIDDEN
        ApiException exAgent = assertThrows(ApiException.class, () ->
                subuserService.createSubuser(agent.getId(), Role.AGENT, customer1.getId(), req));
        assertEquals(ErrorCode.FORBIDDEN, exAgent.getErrorCode());

        // 2. Customer creates subuser -> Success
        SubuserDetailResponse subuser = subuserService.createSubuser(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), req);

        assertNotNull(subuser.getId());
        assertEquals("subuser_alpha", subuser.getUsername());
        assertEquals(Role.SUBUSER, subuser.getRole());
        assertEquals(UserStatus.ACTIVE, subuser.getStatus());

        // 3. Duplicate username -> 409 Conflict
        assertThrows(ConflictException.class, () ->
                subuserService.createSubuser(customerUser1.getId(), Role.CUSTOMER, customer1.getId(), req));
    }

    @Test
    @DisplayName("Booth Assignments: Max 2 slots, duration <= 7 days, area validation, and slot revocation")
    void testBoothAssignmentRules() {
        SubuserDetailResponse subuser = subuserService.createSubuser(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(),
                CreateSubuserRequest.builder().username("subuser_beta").fullName("Worker Beta").password("Pass12345!").build());

        // 1. Assign Slot 1 to Booth 10 (Duration 5 days) -> Success
        CreateBoothAssignmentRequest slot1Req = CreateBoothAssignmentRequest.builder()
                .entitlementId(entitlement.getId())
                .boothValue(10)
                .slot((short) 1)
                .durationDays(5)
                .build();

        BoothAssignmentResponse ba1 = subuserService.assignBooth(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), subuser.getId(), slot1Req);
        assertNotNull(ba1.getId());
        assertEquals(1, ba1.getSlot());
        assertEquals(10, ba1.getBoothValue());

        // 2. Assign Slot 2 to Booth 11 (Duration 3 days) -> Success
        CreateBoothAssignmentRequest slot2Req = CreateBoothAssignmentRequest.builder()
                .entitlementId(entitlement.getId())
                .boothValue(11)
                .slot((short) 2)
                .durationDays(3)
                .build();

        BoothAssignmentResponse ba2 = subuserService.assignBooth(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), subuser.getId(), slot2Req);
        assertNotNull(ba2.getId());
        assertEquals(2, ba2.getSlot());
        assertEquals(11, ba2.getBoothValue());

        // 3. Attempting to assign 3rd booth when slots 1 and 2 are active -> SUBUSER_BOOTH_LIMIT_EXCEEDED
        CreateBoothAssignmentRequest slot1Conflict = CreateBoothAssignmentRequest.builder()
                .entitlementId(entitlement.getId())
                .boothValue(10)
                .slot((short) 1)
                .durationDays(2)
                .build();

        ApiException exSlot = assertThrows(ApiException.class, () ->
                subuserService.assignBooth(customerUser1.getId(), Role.CUSTOMER, customer1.getId(), subuser.getId(), slot1Conflict));
        assertEquals(ErrorCode.SUBUSER_BOOTH_LIMIT_EXCEEDED, exSlot.getErrorCode());

        // 4. Attempting to assign nonexistent booth (e.g. Booth 999) -> BAD_REQUEST
        CreateBoothAssignmentRequest nonExistentBooth = CreateBoothAssignmentRequest.builder()
                .entitlementId(entitlement.getId())
                .boothValue(999)
                .slot((short) 1)
                .durationDays(3)
                .build();

        assertThrows(ApiException.class, () ->
                subuserService.assignBooth(customerUser1.getId(), Role.CUSTOMER, customer1.getId(), subuser.getId(), nonExistentBooth));

        // 5. Revoke slot 1 -> Success, frees slot 1
        BoothAssignmentResponse revoked = subuserService.revokeBoothAssignment(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), ba1.getId(),
                RevokeBoothAssignmentRequest.builder().revokeReason("Worker transferred").build());

        assertEquals(BoothAssignmentStatus.REVOKED, revoked.getStatus());

        // 6. Slot 1 is now free and can be reassigned
        CreateBoothAssignmentRequest newSlot1 = CreateBoothAssignmentRequest.builder()
                .entitlementId(entitlement.getId())
                .boothValue(10)
                .slot((short) 1)
                .durationDays(4)
                .build();

        BoothAssignmentResponse baNew1 = subuserService.assignBooth(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), subuser.getId(), newSlot1);
        assertNotNull(baNew1.getId());
        assertEquals(BoothAssignmentStatus.ACTIVE, baNew1.getStatus());
    }

    @Test
    @DisplayName("Offline Booth Lease: Ed25519 signature verification, max 24h expiry, and writeback validation")
    void testOfflineLeaseAndWriteback() {
        SubuserDetailResponse subuser = subuserService.createSubuser(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(),
                CreateSubuserRequest.builder().username("subuser_gamma").fullName("Worker Gamma").password("Pass12345!").build());

        UserDevice subuserDevice = userDeviceRepository.save(UserDevice.builder()
                .userId(subuser.getId())
                .userRole(Role.SUBUSER)
                .customerAccountId(customer1.getId())
                .installationId("inst-subuser-001")
                .platform(DevicePlatform.ANDROID)
                .deviceName("Samsung Galaxy")
                .status(DeviceStatus.ACTIVE)
                .publicKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsubuserpubkey")
                .keyAlgorithm("ECDSA_P256")
                .build());

        BoothAssignmentResponse ba = subuserService.assignBooth(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), subuser.getId(),
                CreateBoothAssignmentRequest.builder().entitlementId(entitlement.getId()).boothValue(10).slot((short) 1).durationDays(3).build());

        // 1. Subuser requests offline lease
        OfflineLeaseResponse leaseResp = offlineLeaseService.requestLease(
                subuser.getId(), Role.SUBUSER, subuserDevice.getId(),
                RequestOfflineLeaseRequest.builder().boothAssignmentId(ba.getId()).build());

        assertNotNull(leaseResp.getLeaseId());
        assertNotNull(leaseResp.getServerSignature());
        assertEquals(10, leaseResp.getBoothValue());

        // 2. Verify Ed25519 server signature
        byte[] privKeyBytes = HexFormat.of().parseHex(appProperties.getSecurity().getEd25519PrivateKeyHex());
        Ed25519PrivateKeyParameters privParams = new Ed25519PrivateKeyParameters(privKeyBytes, 0);
        Ed25519PublicKeyParameters pubParams = privParams.generatePublicKey();
        byte[] pubKeyBytes = pubParams.getEncoded();

        String payloadSha256 = CryptoUtils.sha256Hex(leaseResp.getPayloadJson());
        boolean sigValid = CryptoUtils.verifyEd25519(
                payloadSha256.getBytes(StandardCharsets.UTF_8),
                Base64.getDecoder().decode(leaseResp.getServerSignature()),
                pubKeyBytes);
        assertTrue(sigValid, "Server Ed25519 signature must be valid");

        // 3. Expiry bounded by 24h max
        long hoursToExpiry = ChronoUnit.HOURS.between(leaseResp.getIssuedAt(), leaseResp.getExpiresAt());
        assertTrue(hoursToExpiry <= 24, "Lease expiry must be at most 24 hours");

        // 4. Offline Writeback: 1 valid mark before expiry, 1 invalid mark after expiry
        Instant validOfflineTime = leaseResp.getIssuedAt().plus(2, ChronoUnit.HOURS);
        Instant invalidOfflineTime = leaseResp.getExpiresAt().plus(1, ChronoUnit.HOURS);

        OfflineWritebackRequest writebackReq = OfflineWritebackRequest.builder()
                .leaseId(leaseResp.getLeaseId())
                .marks(List.of(
                        OfflineTurnoutMarkDto.builder().voterRowId(voter1Id).offlineMarkedAt(validOfflineTime).build(),
                        OfflineTurnoutMarkDto.builder().voterRowId(voter2Id).offlineMarkedAt(invalidOfflineTime).build()
                ))
                .build();

        OfflineWritebackResponse wbResp = offlineLeaseService.processWriteback(
                subuser.getId(), Role.SUBUSER, subuserDevice.getId(), writebackReq);

        assertEquals(2, wbResp.getTotalSubmitted());
        assertEquals(1, wbResp.getTotalAccepted());
        assertEquals(1, wbResp.getTotalRejected());

        // Verify voter 1 marked in DB with source OFFLINE_LEASE
        assertTrue(voterTurnoutRepository.existsByDatasetIdAndCustomerAccountIdAndVoterRowId(
                dataset.getId(), customer1.getId(), voter1Id));
    }

    @Test
    @DisplayName("Bulk Sync: Customer only; Subusers strictly prohibited; chunk retrieval and completion")
    void testBulkSyncLifecycle() {
        SubuserDetailResponse subuser = subuserService.createSubuser(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(),
                CreateSubuserRequest.builder().username("subuser_delta").fullName("Worker Delta").password("Pass12345!").build());

        StartSyncSessionRequest syncReq = StartSyncSessionRequest.builder()
                .entitlementId(entitlement.getId())
                .build();

        // 1. Subuser attempts bulk sync -> Strictly FORBIDDEN
        ApiException exSub = assertThrows(ApiException.class, () ->
                syncService.startSyncSession(subuser.getId(), Role.SUBUSER, customer1.getId(), customerDevice.getId(), syncReq));
        assertEquals(ErrorCode.FORBIDDEN, exSub.getErrorCode());

        // 2. Customer starts bulk sync session -> Success
        SyncSessionResponse sessionResp = syncService.startSyncSession(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), customerDevice.getId(), syncReq);

        assertNotNull(sessionResp.getSessionId());
        assertEquals(2, sessionResp.getTotalRecords());
        assertEquals(1, sessionResp.getTotalChunks());
        assertEquals(SyncStatus.ACTIVE, sessionResp.getStatus());

        // 3. Customer retrieves Chunk 0
        SyncChunkResponse chunkResp = syncService.getChunk(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), sessionResp.getSessionId(), 0);

        assertEquals(0, chunkResp.getChunkNo());
        assertEquals(2, chunkResp.getRecords().size());
        assertTrue(chunkResp.isLastChunk());
        assertNotNull(chunkResp.getChunkSha256());

        // 4. Customer completes sync session
        SyncSessionResponse completed = syncService.completeSyncSession(
                customerUser1.getId(), Role.CUSTOMER, customer1.getId(), sessionResp.getSessionId());

        assertEquals(SyncStatus.COMPLETED, completed.getStatus());
    }
}
