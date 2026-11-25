package com.elecsarthi.security;

import com.elecsarthi.dto.request.CreateBoothAssignmentRequest;
import com.elecsarthi.dto.request.OfflineTurnoutMarkDto;
import com.elecsarthi.dto.request.OfflineWritebackRequest;
import com.elecsarthi.dto.response.OfflineWritebackResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.entity.OfflineLease;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.BoothAssignmentStatus;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.BoothAssignmentRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.OfflineLeaseRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.OfflineLeaseService;
import com.elecsarthi.service.SubuserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Review v3 findings 1 and 2: the entitlement's whole area (not just the booth number) must be enforced by the
 * offline turnout write-back and by Subuser booth assignment, and unreadable criteria must fail closed.
 * Booth/part numbers deliberately repeat in AC 12 and AC 13.
 */
@SpringBootTest
@ActiveProfiles("test")
class EntitlementAreaScopeRegressionIntegrationTest {

    @Autowired private OfflineLeaseService offlineLeaseService;
    @Autowired private SubuserService subuserService;
    @Autowired private CustomerAccountRepository customerAccountRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private BoothAssignmentRepository boothAssignmentRepository;
    @Autowired private OfflineLeaseRepository offlineLeaseRepository;
    @Autowired private UserDeviceRepository deviceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private AppUser admin;
    private CustomerAccount customer;
    private AppUser customerUser;
    private AppUser subuser;
    private Dataset dataset;
    private Dataset otherDataset;
    private Entitlement entAc12;
    private UserDevice device;
    private OfflineLease lease;

    private Long ac12Booth1;
    private Long ac12Booth2;
    private Long ac13Booth1;
    private Long otherDatasetAc12Booth1;

    private static final String INSERT_VOTER = "INSERT INTO voter_d%d (dataset_id, source_record_key_hash, state, district, ac_no, part_no, booth_no, serial_no, voter_id, "
            + "v_fname_en, v_lname_en, vr_fname_en, vr_lname_en, house_no, age, sex) "
            + "VALUES (?, ?, 'MAHARASHTRA', 'PUNE', ?, ?, ?, ?, ?, 'ANSH', 'KAPIL', 'RAM', 'KAPIL', '101', 30, 'M') RETURNING id";

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; " +
                "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter CASCADE; " +
                "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;");

        Instant now = Instant.now();

        admin = userRepository.save(AppUser.builder().username("scope_admin").fullName("Admin")
                .passwordHash(passwordEncoder.encode("Password123!")).role(Role.OWNER).status(UserStatus.ACTIVE).build());
        customer = customerAccountRepository.save(CustomerAccount.builder().customerName("Scope Customer")
                .contactPhone("+919123456701").status(UserStatus.ACTIVE).createdByUserId(admin.getId()).build());
        customerUser = userRepository.save(AppUser.builder().username("scope_cust").fullName("Customer")
                .passwordHash(passwordEncoder.encode("Password123!")).role(Role.CUSTOMER).status(UserStatus.ACTIVE)
                .customerAccountId(customer.getId()).build());
        subuser = userRepository.save(AppUser.builder().username("scope_sub").fullName("Subuser")
                .passwordHash(passwordEncoder.encode("Password123!")).role(Role.SUBUSER).status(UserStatus.ACTIVE)
                .customerAccountId(customer.getId()).build());

        dataset = newDataset("a1", admin, now);
        otherDataset = newDataset("b2", admin, now);

        ac12Booth1 = insertVoter(dataset, "k-12-1", 12, 1, 1, 1, "E1201");
        ac12Booth2 = insertVoter(dataset, "k-12-2", 12, 2, 2, 2, "E1202");
        ac13Booth1 = insertVoter(dataset, "k-13-1", 13, 1, 1, 1, "E1301");
        // Booth 5 exists only in AC 13 (used for the assignment test: same part number, other AC).
        insertVoter(dataset, "k-13-5", 13, 5, 5, 5, "E1305");
        otherDatasetAc12Booth1 = insertVoter(otherDataset, "k-o-12-1", 12, 1, 1, 1, "EO1201");

        entAc12 = saveEntitlement(dataset, "{\"ac_no\":12}", "AC 12", "0".repeat(64), now);

        BoothAssignment assignment = boothAssignmentRepository.save(BoothAssignment.builder()
                .subuserId(subuser.getId()).customerAccountId(customer.getId()).entitlementId(entAc12.getId())
                .boothValue(1).slot((short) 1).assignedByUserId(customerUser.getId())
                .status(BoothAssignmentStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.HOURS)).validUntil(now.plus(6, ChronoUnit.DAYS)).build());

        device = deviceRepository.save(UserDevice.builder()
                .userId(subuser.getId()).userRole(Role.SUBUSER).customerAccountId(customer.getId())
                .installationId("inst_scope").platform(DevicePlatform.ANDROID).publicKey("dummy_pk_scope")
                .deviceName("Subuser Phone").status(DeviceStatus.ACTIVE)
                .challengeHash(CryptoUtils.sha256Hex("chal-scope")).challengePurpose("DEVICE_REGISTRATION")
                .challengeExpiresAt(now.plus(1, ChronoUnit.DAYS)).lastRequestCounter(0L).createdAt(now).build());

        lease = offlineLeaseRepository.save(OfflineLease.builder()
                .boothAssignmentId(assignment.getId()).subuserId(subuser.getId()).deviceId(device.getId())
                .leaseSeq(1L).payloadSha256(CryptoUtils.sha256Hex("payload-scope")).signingKeyVersion((short) 1)
                .issuedAt(now.minus(10, ChronoUnit.MINUTES)).expiresAt(now.plus(23, ChronoUnit.HOURS)).build());
    }

    // ---------- Finding 1: offline turnout write-back ----------

    @Test
    @DisplayName("Offline write-back: mark inside the entitled AC and assigned booth is accepted")
    void writebackAcceptsVoterInsideAreaAndBooth() {
        OfflineWritebackResponse r = writeback(ac12Booth1);
        assertEquals(1, r.getTotalAccepted());
        assertEquals(0, r.getTotalRejected());
        assertEquals(1, turnoutCount(ac12Booth1));
    }

    @Test
    @DisplayName("Offline write-back: same booth number in another AC is rejected and writes nothing")
    void writebackRejectsSameBoothNumberInOtherAc() {
        OfflineWritebackResponse r = writeback(ac13Booth1);
        assertEquals(0, r.getTotalAccepted());
        assertEquals(1, r.getTotalRejected());
        assertEquals(0, turnoutCount(ac13Booth1));
        assertEquals(0, totalTurnoutRows());
    }

    @Test
    @DisplayName("Offline write-back: voter in the AC but an unassigned booth is rejected")
    void writebackRejectsUnassignedBooth() {
        OfflineWritebackResponse r = writeback(ac12Booth2);
        assertEquals(0, r.getTotalAccepted());
        assertEquals(0, totalTurnoutRows());
    }

    @Test
    @DisplayName("Offline write-back: voter of another dataset is rejected")
    void writebackRejectsVoterOfOtherDataset() {
        OfflineWritebackResponse r = writeback(otherDatasetAc12Booth1);
        assertEquals(0, r.getTotalAccepted());
        assertEquals(1, r.getTotalRejected());
        assertEquals(0, totalTurnoutRows());
    }

    @Test
    @DisplayName("Offline write-back: mixed batch accepts only the in-scope mark")
    void writebackMixedBatchAcceptsOnlyInScope() {
        OfflineWritebackResponse r = writeback(ac12Booth1, ac13Booth1, ac12Booth2, otherDatasetAc12Booth1);
        assertEquals(4, r.getTotalSubmitted());
        assertEquals(1, r.getTotalAccepted());
        assertEquals(3, r.getTotalRejected());
        assertEquals(1, totalTurnoutRows());
    }

    @Test
    @DisplayName("Offline write-back: unreadable entitlement criteria fail closed with 403 and write nothing")
    void writebackFailsClosedOnBadCriteria() {
        for (String bad : List.of("[1,2,3]", "{}", "{\"unapproved_col\":\"x\"}")) {
            setCriteria(entAc12, bad);
            ApiException ex = assertThrows(ApiException.class, () -> writeback(ac12Booth1), "criteria " + bad);
            assertEquals(403, ex.getStatus().value());
            assertEquals(0, totalTurnoutRows());
        }
    }

    // ---------- Finding 2: Subuser booth assignment ----------

    @Test
    @DisplayName("Assign booth: booth inside the authorized area can be assigned")
    void assignBoothInsideArea() {
        assertNotNull(assign(entAc12, 2, (short) 2).getId());
    }

    @Test
    @DisplayName("Assign booth: booth that exists only in another AC (same part-number space) is rejected")
    void assignBoothOutsideAreaRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> assign(entAc12, 5, (short) 2));
        assertEquals(400, ex.getStatus().value());
        assertEquals(0, activeAssignmentCount(5));
    }

    @Test
    @DisplayName("Assign booth: unreadable entitlement criteria cannot bypass the area check")
    void assignBoothFailsClosedOnBadCriteria() {
        Instant now = Instant.now();
        List<String> bad = List.of("[1,2,3]", "{}", "{\"unapproved_col\":\"x\"}");
        int i = 0;
        for (String criteria : bad) {
            Entitlement ent = saveEntitlement(dataset, criteria, "bad " + i, String.valueOf(++i).repeat(64).substring(0, 64), now);
            // Booth 5 is outside any AC-12 area; with fail-open parsing it would have been assigned.
            ApiException ex = assertThrows(ApiException.class, () -> assign(ent, 5, (short) 2), "criteria " + criteria);
            assertEquals(403, ex.getStatus().value());
            assertEquals(0, activeAssignmentCount(5));
        }
    }

    // ---------- helpers ----------

    private Dataset newDataset(String hashChar, AppUser owner, Instant now) {
        Dataset ds = datasetRepository.save(Dataset.builder().name("Scope DS " + hashChar)
                .originalFileName("v.xlsx").fileSha256(hashChar.repeat(32))
                .storageTier(DatasetTier.MASTER).status(DatasetStatus.IMPORTED)
                .deleteAfter(now.plus(60, ChronoUnit.DAYS)).importedByUserId(owner.getId()).createdAt(now).build());
        jdbcTemplate.execute("SELECT create_dataset_partitions(" + ds.getId() + ");");
        return ds;
    }

    private Long insertVoter(Dataset ds, String key, int ac, int part, int booth, int serial, String epic) {
        return jdbcTemplate.queryForObject(String.format(INSERT_VOTER, ds.getId()), Long.class,
                ds.getId(), key, ac, part, booth, serial, epic);
    }

    private Entitlement saveEntitlement(Dataset ds, String criteria, String label, String hash, Instant now) {
        return entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customer.getId()).datasetId(ds.getId()).electionTypeId((short) 1)
                .storageTier(DatasetTier.MASTER).scopeLabel(label).source(EntitlementSource.DIRECT)
                .createdByUserId(admin.getId()).criteria(criteria).criteriaHash(hash)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.HOURS)).validUntil(now.plus(30, ChronoUnit.DAYS))
                .mobileAccess(false).build());
    }

    private void setCriteria(Entitlement ent, String criteria) {
        ent.setCriteria(criteria);
        entitlementRepository.save(ent);
    }

    private OfflineWritebackResponse writeback(Long... voterRowIds) {
        Instant markedAt = Instant.now();
        List<OfflineTurnoutMarkDto> marks = java.util.Arrays.stream(voterRowIds)
                .map(id -> OfflineTurnoutMarkDto.builder().voterRowId(id).offlineMarkedAt(markedAt).build()).toList();
        return offlineLeaseService.processWriteback(subuser.getId(), Role.SUBUSER, device.getId(),
                OfflineWritebackRequest.builder().leaseId(lease.getId()).marks(marks).build());
    }

    private com.elecsarthi.dto.response.BoothAssignmentResponse assign(Entitlement ent, int booth, short slot) {
        return subuserService.assignBooth(customerUser.getId(), Role.CUSTOMER, customer.getId(), subuser.getId(),
                CreateBoothAssignmentRequest.builder().entitlementId(ent.getId()).boothValue(booth).slot(slot)
                        .durationDays(3).build());
    }

    private int turnoutCount(Long voterRowId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM voter_turnout WHERE voter_row_id = ?", Integer.class, voterRowId);
    }

    private int totalTurnoutRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM voter_turnout", Integer.class);
    }

    private int activeAssignmentCount(int booth) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booth_assignment WHERE subuser_id = ? AND booth_value = ?", Integer.class,
                subuser.getId(), booth);
    }
}
