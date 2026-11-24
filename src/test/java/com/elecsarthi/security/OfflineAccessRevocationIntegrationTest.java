package com.elecsarthi.security;

import com.elecsarthi.dto.request.OfflineWritebackRequest;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.repository.*;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.CustomerService;
import com.elecsarthi.service.DeviceService;
import com.elecsarthi.service.EntitlementService;
import com.elecsarthi.service.OfflineLeaseService;
import com.elecsarthi.service.SyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sync and offline leases are revalidated and revoked when entitlements, accounts or devices change. */
public class OfflineAccessRevocationIntegrationTest extends SecurityIntegrationTestSupport {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private SyncService syncService;

    @Autowired
    private OfflineLeaseService offlineLeaseService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private EntitlementRepository entitlementRepository;

    @Autowired
    private BoothAssignmentRepository boothAssignmentRepository;

    @Autowired
    private ElectionTypeRepository electionTypeRepository;

    @Autowired
    private SyncSessionRepository syncSessionRepository;

    @Autowired
    private OfflineLeaseRepository offlineLeaseRepository;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("Verification: Bulk sync chunk and lease writeback revalidate active status and handle cascade revocations")
    void testSyncAndLeaseLifecycleIsolation() {
        Instant now = Instant.now();

        AppUser syncOwner = userRepository.save(AppUser.builder()
                .username("sync_owner")
                .fullName("Sync Owner")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount custAcc = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Sync Test Cust")
                .contactPhone("+919999900001")
                .status(UserStatus.ACTIVE)
                .createdByUserId(syncOwner.getId())
                .build());

        AppUser custUser = userRepository.save(AppUser.builder()
                .username("sync_cust_user")
                .fullName("Sync Cust User")
                .customerAccountId(custAcc.getId())
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());

        UserDevice syncDev = deviceRepository.save(UserDevice.builder()
                .userId(custUser.getId())
                .customerAccountId(custAcc.getId())
                .userRole(Role.CUSTOMER)
                .platform(DevicePlatform.ANDROID)
                .deviceName("Sync Phone")
                .installationId("inst-sync-1")
                .publicKey("dummy-pk")
                .status(DeviceStatus.ACTIVE)
                .activatedAt(now)
                .lastUsedAt(now)
                .lastRequestCounter(0L)
                .createdAt(now)
                .build());

        Dataset ds = datasetRepository.save(Dataset.builder()
                .name("Sync DS")
                .originalFileName("sync.csv")
                .fileSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .totalRows(10)
                .importedRows(10)
                .failedRows(0)
                .deleteAfter(now.plus(365, ChronoUnit.DAYS))
                .importedByUserId(syncOwner.getId())
                .createdAt(now)
                .build());

        ElectionType et = electionTypeRepository.findAll().get(0);

        Entitlement ent = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(custAcc.getId())
                .electionTypeId(et.getId())
                .datasetId(ds.getId())
                .storageTier(DatasetTier.MASTER)
                .criteria("{\"ac_no\": 1}")
                .criteriaHash("hash1234")
                .scopeLabel("AC 1")
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.DAYS))
                .validUntil(now.plus(10, ChronoUnit.DAYS))
                .mobileAccess(false)
                .createdByUserId(syncOwner.getId())
                .createdAt(now)
                .build());

        SyncSession syncSess = syncSessionRepository.save(SyncSession.builder()
                .customerAccountId(custAcc.getId())
                .userId(custUser.getId())
                .entitlementId(ent.getId())
                .deviceId(syncDev.getId())
                .protocolVersion((short) 1)
                .manifestSha256("manifest123")
                .scopeSnapshot("{\"ac_no\": 1}")
                .policyVersion(1)
                .includesMobile(false)
                .totalRecords(10)
                .totalChunks(1)
                .chunkSize(1000)
                .chunksDone(0)
                .signingKeyVersion((short) 1)
                .offlineValidUntil(now.plus(1, ChronoUnit.DAYS))
                .status(SyncStatus.ACTIVE)
                .createdAt(now)
                .expiresAt(now.plus(2, ChronoUnit.HOURS))
                .build());

        // 1. Revoke entitlement -> getChunk fails with 403
        ent.setStatus(EntitlementStatus.REVOKED);
        ent.setRevokedAt(now);
        ent.setRevokeReason("Testing revocation");
        ent.setRevokedByUserId(syncOwner.getId());
        entitlementRepository.save(ent);

        ApiException ex = assertThrows(ApiException.class, () ->
                syncService.getChunk(custUser.getId(), Role.CUSTOMER, custAcc.getId(), syncSess.getId(), 0));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, ex.getStatus());

        // Restore entitlement and test device revocation
        ent.setStatus(EntitlementStatus.ACTIVE);
        ent.setRevokedAt(null);
        ent.setRevokeReason(null);
        ent.setRevokedByUserId(null);
        entitlementRepository.save(ent);

        // 2. Revoke device -> getChunk fails with 403
        deviceService.revokeDevice(syncDev.getId(), syncOwner.getId(), "127.0.0.1");

        ApiException devEx = assertThrows(ApiException.class, () ->
                syncService.getChunk(custUser.getId(), Role.CUSTOMER, custAcc.getId(), syncSess.getId(), 0));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, devEx.getStatus());

        // 3. Subuser Lease & cascade revocation on device revocation
        AppUser subuser = userRepository.save(AppUser.builder()
                .username("subuser_wb")
                .fullName("Subuser WB")
                .customerAccountId(custAcc.getId())
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.SUBUSER)
                .status(UserStatus.ACTIVE)
                .build());

        UserDevice subDev = deviceRepository.save(UserDevice.builder()
                .userId(subuser.getId())
                .customerAccountId(custAcc.getId())
                .userRole(Role.SUBUSER)
                .platform(DevicePlatform.ANDROID)
                .deviceName("Subuser Phone")
                .installationId("inst-sub-1")
                .publicKey("pk-sub-1")
                .status(DeviceStatus.ACTIVE)
                .activatedAt(now)
                .lastUsedAt(now)
                .lastRequestCounter(0L)
                .createdAt(now)
                .build());

        BoothAssignment ba = boothAssignmentRepository.save(BoothAssignment.builder()
                .subuserId(subuser.getId())
                .customerAccountId(custAcc.getId())
                .entitlementId(ent.getId())
                .boothValue(101)
                .slot((short) 1)
                .validFrom(now.minus(1, ChronoUnit.DAYS))
                .validUntil(now.plus(5, ChronoUnit.DAYS))
                .status(BoothAssignmentStatus.ACTIVE)
                .assignedByUserId(custUser.getId())
                .createdAt(now)
                .build());

        OfflineLease lease = offlineLeaseRepository.save(OfflineLease.builder()
                .boothAssignmentId(ba.getId())
                .subuserId(subuser.getId())
                .deviceId(subDev.getId())
                .leaseSeq(1L)
                .payloadSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .signingKeyVersion((short) 1)
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.DAYS))
                .build());

        // Revoking subuser device must cascade-revoke active lease
        deviceService.revokeDevice(subDev.getId(), syncOwner.getId(), "127.0.0.1");

        OfflineLease reloadedLease = offlineLeaseRepository.findById(lease.getId()).orElseThrow();
        assertNotNull(reloadedLease.getRevokedAt());

        // Writeback must be rejected with 403
        OfflineWritebackRequest wbReq = OfflineWritebackRequest.builder()
                .leaseId(lease.getId())
                .marks(List.of())
                .build();

        ApiException wbEx = assertThrows(ApiException.class, () ->
                offlineLeaseService.processWriteback(subuser.getId(), Role.SUBUSER, subDev.getId(), wbReq));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, wbEx.getStatus());
    }

    @Test
    @DisplayName("Verification: Direct Entitlement Revocation cascades leases and recomputes LONG_TERM retention")
    void testDirectEntitlementRevocationAndRetentionRecomputation() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        AppUser owner = userRepository.save(AppUser.builder()
                .username("rev_owner_" + System.currentTimeMillis())
                .fullName("Rev Owner")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount custAcc = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Rev Customer")
                .contactPhone("+919999000077")
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .mobileAccessPolicyVersion(1)
                .build());

        Dataset ds = datasetRepository.save(Dataset.builder()
                .name("Rev Retention DS")
                .originalFileName("rev_ds.csv")
                .storageTier(DatasetTier.LONG_TERM)
                .status(DatasetStatus.IMPORTED)
                .states(List.of("DELHI"))
                .fileSha256(CryptoUtils.sha256Hex("rev-ds-" + System.currentTimeMillis()))
                .totalRows(10)
                .importedRows(10)
                .failedRows(0)
                .importedByUserId(owner.getId())
                .deleteAfter(now.plus(100, ChronoUnit.DAYS))
                .createdAt(now)
                .build());

        ElectionType et = electionTypeRepository.findAll().get(0);

        Entitlement ent = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(custAcc.getId())
                .electionTypeId(et.getId())
                .datasetId(ds.getId())
                .storageTier(DatasetTier.LONG_TERM)
                .criteria("{\"ac_no\": 80}")
                .criteriaHash("hash-rev-80")
                .scopeLabel("AC 80")
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now)
                .validUntil(now.plus(60, ChronoUnit.DAYS))
                .mobileAccess(true)
                .mobileAccessChangedAt(now)
                .mobileAccessChangedBy(owner.getId())
                .createdByUserId(owner.getId())
                .createdAt(now)
                .build());

        // Revoke the direct entitlement
        com.elecsarthi.dto.response.EntitlementResponse revokedResp =
                entitlementService.revokeEntitlement(owner.getId(), Role.OWNER, ent.getId(), "Direct customer cancellation");
        assertNotNull(revokedResp);
        assertEquals(EntitlementStatus.REVOKED, revokedResp.getStatus());

        // Verify dataset retention was recomputed to now + 30 days (since no active entitlements remain)
        Dataset updatedDs = datasetRepository.findById(ds.getId()).orElseThrow();
        assertNotNull(updatedDs.getDeleteAfter());
        long daysUntilDelete = ChronoUnit.DAYS.between(now, updatedDs.getDeleteAfter());
        assertTrue(daysUntilDelete <= 31 && daysUntilDelete >= 29, "Expected ~30 days retention, but got " + daysUntilDelete);

        // Verify customer mobileAccessPolicyVersion was incremented
        CustomerAccount updatedCust = customerAccountRepository.findById(custAcc.getId()).orElseThrow();
        assertEquals(2, updatedCust.getMobileAccessPolicyVersion());
    }

    @Test
    @DisplayName("Verification: Offline writeback is rejected after customer account suspension")
    void testOfflineWritebackRejectedAfterCustomerSuspension() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        AppUser owner = userRepository.save(AppUser.builder()
                .username("susp_owner_" + System.currentTimeMillis())
                .fullName("Susp Owner")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount custAcc = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Susp Customer")
                .contactPhone("+919999000088")
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .mobileAccessPolicyVersion(1)
                .build());

        AppUser subuser = userRepository.save(AppUser.builder()
                .username("susp_subuser_" + System.currentTimeMillis())
                .fullName("Susp Subuser")
                .customerAccountId(custAcc.getId())
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.SUBUSER)
                .status(UserStatus.ACTIVE)
                .build());

        Dataset ds = datasetRepository.save(Dataset.builder()
                .name("Susp DS")
                .originalFileName("susp_ds.csv")
                .storageTier(DatasetTier.LONG_TERM)
                .status(DatasetStatus.IMPORTED)
                .states(List.of("DELHI"))
                .fileSha256(CryptoUtils.sha256Hex("susp-ds-" + System.currentTimeMillis()))
                .totalRows(10)
                .importedRows(10)
                .failedRows(0)
                .importedByUserId(owner.getId())
                .deleteAfter(now.plus(100, ChronoUnit.DAYS))
                .createdAt(now)
                .build());

        ElectionType et = electionTypeRepository.findAll().get(0);

        Entitlement ent = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(custAcc.getId())
                .electionTypeId(et.getId())
                .datasetId(ds.getId())
                .storageTier(DatasetTier.LONG_TERM)
                .criteria("{\"ac_no\": 90}")
                .criteriaHash("hash-susp-90")
                .scopeLabel("AC 90")
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now)
                .validUntil(now.plus(30, ChronoUnit.DAYS))
                .mobileAccess(true)
                .mobileAccessChangedAt(now)
                .mobileAccessChangedBy(owner.getId())
                .createdByUserId(owner.getId())
                .createdAt(now)
                .build());

        BoothAssignment assignment = boothAssignmentRepository.save(BoothAssignment.builder()
                .customerAccountId(custAcc.getId())
                .subuserId(subuser.getId())
                .entitlementId(ent.getId())
                .boothValue(101)
                .slot((short) 1)
                .validFrom(now)
                .validUntil(now.plus(7, ChronoUnit.DAYS))
                .status(BoothAssignmentStatus.ACTIVE)
                .assignedByUserId(owner.getId())
                .createdAt(now)
                .build());

        UserDevice device = deviceRepository.save(UserDevice.builder()
                .userId(subuser.getId())
                .userRole(Role.SUBUSER)
                .customerAccountId(custAcc.getId())
                .installationId("inst_susp")
                .platform(DevicePlatform.ANDROID)
                .publicKey("dummy_pk_susp")
                .deviceName("Subuser Phone")
                .status(DeviceStatus.ACTIVE)
                .challengeHash(CryptoUtils.sha256Hex("chal-susp"))
                .challengePurpose("DEVICE_REGISTRATION")
                .challengeExpiresAt(now.plus(1, ChronoUnit.DAYS))
                .lastRequestCounter(0L)
                .createdAt(now)
                .build());

        OfflineLease lease = offlineLeaseRepository.save(OfflineLease.builder()
                .boothAssignmentId(assignment.getId())
                .subuserId(subuser.getId())
                .deviceId(device.getId())
                .leaseSeq(1L)
                .payloadSha256(CryptoUtils.sha256Hex("payload-susp-lease"))
                .signingKeyVersion((short) 1)
                .issuedAt(now)
                .expiresAt(now.plus(24, ChronoUnit.HOURS))
                .build());

        // Suspend the customer account
        customerService.suspendCustomer(custAcc.getId(), "Payment delinquency", owner.getId(), Role.OWNER);

        // Offline writeback MUST be rejected with FORBIDDEN (both lease is cascade-revoked and customer is suspended)
        OfflineWritebackRequest wbRequest = OfflineWritebackRequest.builder()
                .leaseId(lease.getId())
                .marks(List.of())
                .build();

        ApiException ex = assertThrows(ApiException.class, () ->
                offlineLeaseService.processWriteback(subuser.getId(), Role.SUBUSER, device.getId(), wbRequest));
        assertEquals("FORBIDDEN", ex.getErrorCode().name());
    }
}
