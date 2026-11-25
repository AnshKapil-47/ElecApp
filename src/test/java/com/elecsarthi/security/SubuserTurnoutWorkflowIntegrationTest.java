package com.elecsarthi.security;

import com.elecsarthi.dto.request.CreateBoothAssignmentRequest;
import com.elecsarthi.dto.request.MarkTurnoutRequest;
import com.elecsarthi.dto.request.ResetTurnoutRequest;
import com.elecsarthi.dto.request.UnmarkTurnoutRequest;
import com.elecsarthi.dto.request.VoterSearchRequest;
import com.elecsarthi.dto.response.TurnoutResponse;
import com.elecsarthi.dto.response.TurnoutStatisticsResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.service.SubuserService;
import com.elecsarthi.service.TurnoutService;
import com.elecsarthi.service.VoterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Subuser workflow end to end at the service layer: assignment inside the entitled area, booth-scoped
 * marking, Customer-only reversal with audit, whole-scope Customer statistics, and the Subuser detail rules.
 *
 * <p>Dataset A (Customer A, entitled to state STATE-A / AC 12) holds booth 1 voters of AC 12 <b>and</b> a booth 1 voter
 * of AC 13: the same booth number in a different area, which must never count as the assigned booth.
 * Dataset B belongs to Customer B.
 */
public class SubuserTurnoutWorkflowIntegrationTest extends SecurityIntegrationTestSupport {

    @Autowired private AppUserRepository userRepository;
    @Autowired private CustomerAccountRepository customerAccountRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private ElectionTypeRepository electionTypeRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SubuserService subuserService;
    @Autowired private TurnoutService turnoutService;
    @Autowired private VoterService voterService;

    private AppUser owner;
    private AppUser customerA;
    private AppUser customerB;
    private AppUser subuserA;
    private AppUser subuserB;
    private CustomerAccount accountA;
    private CustomerAccount accountB;
    private Entitlement entA;
    private Entitlement entB;
    private Long a1;          // AC 12, booth 1
    private Long a2;          // AC 12, booth 1
    private Long a3;          // AC 12, booth 2
    private Long otherAc;     // AC 13, booth 1 (same booth number, different area), same dataset
    private Long b1;          // Customer B's voter, booth 1

    @BeforeEach
    void fixtures() {
        owner = user("sw_owner", Role.OWNER, null);
        accountA = account("Customer A", "+919700000001");
        accountB = account("Customer B", "+919700000002");
        customerA = user("sw_cust_a", Role.CUSTOMER, accountA.getId());
        customerB = user("sw_cust_b", Role.CUSTOMER, accountB.getId());
        subuserA = user("sw_sub_a", Role.SUBUSER, accountA.getId());
        subuserB = user("sw_sub_b", Role.SUBUSER, accountB.getId());

        Dataset dsA = dataset("a");
        Dataset dsB = dataset("b");
        entA = entitlement(accountA, dsA, "{\"ac_no\":12,\"state\":\"STATE-A\"}", "a");
        entB = entitlement(accountB, dsB, "{\"ac_no\":12,\"state\":\"STATE-A\"}", "b");

        a1 = voter(dsA, 1, "STATE-A", 12, 1, "H1");
        a2 = voter(dsA, 2, "STATE-A", 12, 1, "H1");
        a3 = voter(dsA, 3, "STATE-A", 12, 2, "H2");
        otherAc = voter(dsA, 4, "STATE-A", 13, 1, "H9");
        b1 = voter(dsB, 1, "STATE-A", 12, 1, "H1");

        subuserService.assignBooth(customerA.getId(), Role.CUSTOMER, accountA.getId(), subuserA.getId(), assignment(entA, 1, 1));
        subuserService.assignBooth(customerB.getId(), Role.CUSTOMER, accountB.getId(), subuserB.getId(), assignment(entB, 1, 1));
    }

    @Test
    @DisplayName("Booth assignment: only booths inside the entitled area, only own Subuser and entitlement, no duplicates")
    void assignmentRules() {
        // booth 2 exists in the area; booth 5 does not; a booth number that exists only in AC 13 is not in the area
        assertNotNull(subuserService.assignBooth(customerA.getId(), Role.CUSTOMER, accountA.getId(), subuserA.getId(), assignment(entA, 2, 2)));
        assertThrows(BadRequestException.class, () -> subuserService.assignBooth(customerA.getId(), Role.CUSTOMER,
                accountA.getId(), subuserA.getId(), assignment(entA, 5, 2)));
        // duplicate booth / occupied slot are refused
        assertThrows(RuntimeException.class, () -> subuserService.assignBooth(customerA.getId(), Role.CUSTOMER,
                accountA.getId(), subuserA.getId(), assignment(entA, 1, 1)));
        // another Customer's Subuser and entitlement are invisible
        assertThrows(ResourceNotFoundException.class, () -> subuserService.assignBooth(customerA.getId(), Role.CUSTOMER,
                accountA.getId(), subuserB.getId(), assignment(entA, 1, 1)));
        assertThrows(ResourceNotFoundException.class, () -> subuserService.assignBooth(customerA.getId(), Role.CUSTOMER,
                accountA.getId(), subuserA.getId(), assignment(entB, 1, 2)));
        // a Subuser cannot assign
        assertThrows(ForbiddenException.class, () -> subuserService.assignBooth(subuserA.getId(), Role.SUBUSER,
                accountA.getId(), subuserA.getId(), assignment(entA, 2, 1)));
    }

    @Test
    @DisplayName("A Subuser marks only voters of its assigned booth inside the entitled area; nothing else is written")
    void markingIsBoothAndAreaScoped() {
        mark(subuserA, accountA, entA, a1);
        // booth 2, the same booth number in another AC (same dataset), another Customer's voter, a missing voter
        for (Long forbidden : List.of(a3, otherAc, b1, 999_999_999L)) {
            assertThrows(ResourceNotFoundException.class, () -> mark(subuserA, accountA, entA, forbidden), "voter " + forbidden);
        }
        // another Customer's entitlement is not found
        assertThrows(ResourceNotFoundException.class, () -> mark(subuserA, accountA, entB, a1));
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voter_turnout", Long.class));
        assertEquals(a1, jdbcTemplate.queryForObject("SELECT voter_row_id FROM voter_turnout", Long.class));
        // and the Subuser cannot see or search them either
        assertEquals(2L, voterService.searchVoters(subuserA.getId(), Role.SUBUSER, accountA.getId(),
                VoterSearchRequest.builder().entitlementId(entA.getId()).build()).getTotalElements());
    }

    @Test
    @DisplayName("A repeated mark reports the stored first-mark time and creates no second row")
    void duplicateMarkReportsStoredTime() {
        TurnoutResponse first = mark(subuserA, accountA, entA, a1);
        TurnoutResponse repeat = mark(subuserA, accountA, entA, a1);
        Instant stored = jdbcTemplate.queryForObject("SELECT marked_at FROM voter_turnout", java.sql.Timestamp.class).toInstant();
        assertEquals(stored, first.getMarkedAt());
        assertEquals(stored, repeat.getMarkedAt(), "a confirmation must not move the time of the first mark");
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voter_turnout", Long.class));
    }

    @Test
    @DisplayName("Only the Customer reverses turnout, inside its own scope, and the reversal is audited without voter data")
    void reversalIsCustomerOnlyScopedAndAudited() {
        mark(subuserA, accountA, entA, a1);
        mark(subuserA, accountA, entA, a2);
        assertThrows(ForbiddenException.class, () -> turnoutService.unmarkVoted(subuserA.getId(), Role.SUBUSER, accountA.getId(),
                UnmarkTurnoutRequest.builder().entitlementId(entA.getId()).voterRowId(a1).build()));
        assertThrows(ForbiddenException.class, () -> turnoutService.resetTurnout(subuserA.getId(), Role.SUBUSER, accountA.getId(),
                ResetTurnoutRequest.builder().entitlementId(entA.getId()).build()));
        // Customer B cannot touch Customer A's rows
        assertThrows(ResourceNotFoundException.class, () -> turnoutService.unmarkVoted(customerB.getId(), Role.CUSTOMER, accountB.getId(),
                UnmarkTurnoutRequest.builder().entitlementId(entA.getId()).voterRowId(a1).build()));
        turnoutService.unmarkVoted(customerB.getId(), Role.CUSTOMER, accountB.getId(),
                UnmarkTurnoutRequest.builder().entitlementId(entB.getId()).voterRowId(a1).build());
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voter_turnout", Long.class));

        turnoutService.unmarkVoted(customerA.getId(), Role.CUSTOMER, accountA.getId(),
                UnmarkTurnoutRequest.builder().entitlementId(entA.getId()).voterRowId(a2).build());
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voter_turnout", Long.class));
        turnoutService.resetTurnout(customerA.getId(), Role.CUSTOMER, accountA.getId(),
                ResetTurnoutRequest.builder().entitlementId(entA.getId()).boothNo(1).build());
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voter_turnout", Long.class));

        assertEquals(1L, auditCount("TURNOUT_UNMARKED", customerA.getId()), "unmark by Customer A is audited");
        assertEquals(1L, auditCount("TURNOUT_RESET", customerA.getId()), "reset by Customer A is audited");
        String details = String.join(" ", jdbcTemplate.queryForList(
                "SELECT details::text FROM audit_log WHERE action_type IN ('TURNOUT_UNMARKED','TURNOUT_RESET')", String.class));
        assertFalse(details.contains("VOT"), "no voter data in the audit details");
        assertTrue(details.contains("removed"));
    }

    @Test
    @DisplayName("The Customer's statistics cover the whole purchased scope; a Subuser's only its booth")
    void statisticsCoverWholeScope() {
        mark(subuserA, accountA, entA, a1);
        TurnoutStatisticsResponse customer = turnoutService.getTurnoutStatistics(customerA.getId(), Role.CUSTOMER, accountA.getId(), entA.getId(), null);
        assertEquals(3L, customer.getTotalVoters(), "booth 1 and booth 2 of AC 12 count; AC 13 does not");
        assertEquals(1L, customer.getTotalVoted());
        assertEquals(33.33, customer.getTurnoutPercentage(), 0.001);
        TurnoutStatisticsResponse sub = turnoutService.getTurnoutStatistics(subuserA.getId(), Role.SUBUSER, accountA.getId(), entA.getId(), null);
        assertEquals(2L, sub.getTotalVoters());
        assertEquals(1L, sub.getTotalVoted());
        assertThrows(ForbiddenException.class, () -> turnoutService.getTurnoutStatistics(subuserA.getId(), Role.SUBUSER,
                accountA.getId(), entA.getId(), 2));
        // Customer B sees nothing of A
        TurnoutStatisticsResponse other = turnoutService.getTurnoutStatistics(customerB.getId(), Role.CUSTOMER, accountB.getId(), entB.getId(), null);
        assertEquals(1L, other.getTotalVoters());
        assertEquals(0L, other.getTotalVoted());
    }

    @Test
    @DisplayName("Subuser detail: a Customer sees only its own Subusers, never its own primary login; the Owner can look without a 500")
    void subuserDetailRules() {
        assertEquals(subuserA.getId(), subuserService.getSubuserDetail(customerA.getId(), Role.CUSTOMER, accountA.getId(), subuserA.getId()).getId());
        assertThrows(ResourceNotFoundException.class, () -> subuserService.getSubuserDetail(customerA.getId(), Role.CUSTOMER,
                accountA.getId(), subuserB.getId()));
        assertThrows(ResourceNotFoundException.class, () -> subuserService.getSubuserDetail(customerA.getId(), Role.CUSTOMER,
                accountA.getId(), customerA.getId()), "the Customer's own primary login is not a Subuser");
        // the Owner has no Customer account of its own
        var seen = subuserService.getSubuserDetail(owner.getId(), Role.OWNER, null, subuserA.getId());
        assertEquals(Role.SUBUSER, seen.getRole());
        assertEquals(accountA.getId(), seen.getCustomerAccountId());
        assertThrows(ResourceNotFoundException.class, () -> subuserService.getSubuserDetail(owner.getId(), Role.OWNER, null, customerA.getId()));
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private TurnoutResponse mark(AppUser caller, CustomerAccount account, Entitlement ent, Long voterId) {
        return turnoutService.markVoted(caller.getId(), caller.getRole(), account.getId(),
                MarkTurnoutRequest.builder().entitlementId(ent.getId()).voterRowId(voterId).build());
    }

    private long auditCount(String action, Long actor) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action_type = ? AND actor_user_id = ?", Long.class, action, actor);
    }

    private static CreateBoothAssignmentRequest assignment(Entitlement ent, int booth, int slot) {
        CreateBoothAssignmentRequest r = new CreateBoothAssignmentRequest();
        r.setEntitlementId(ent.getId());
        r.setBoothValue(booth);
        r.setSlot((short) slot);
        r.setDurationDays(3);
        return r;
    }

    private AppUser user(String username, Role role, Long accountId) {
        return userRepository.save(AppUser.builder().username(username).fullName(username)
                .passwordHash(passwordEncoder.encode("Password123!")).role(role).customerAccountId(accountId)
                .status(UserStatus.ACTIVE).createdByUserId(owner != null ? owner.getId() : null).build());
    }

    private CustomerAccount account(String name, String phone) {
        return customerAccountRepository.save(CustomerAccount.builder().customerName(name).contactPhone(phone)
                .createdByUserId(owner.getId()).status(UserStatus.ACTIVE).activationStatus(ActivationStatus.ACTIVATED).build());
    }

    private Dataset dataset(String tag) {
        Dataset ds = datasetRepository.save(Dataset.builder().name("sw dataset " + tag).originalFileName("sw-" + tag + ".xlsx")
                .fileSha256((tag + "f").repeat(32).substring(0, 64)).storageTier(DatasetTier.MASTER).status(DatasetStatus.IMPORTED)
                .states(List.of("STATE-A")).deleteAfter(Instant.now().plus(60, ChronoUnit.DAYS)).importedByUserId(owner.getId()).build());
        jdbcTemplate.execute("SELECT create_dataset_partitions(" + ds.getId() + ");");
        return ds;
    }

    private Entitlement entitlement(CustomerAccount account, Dataset ds, String criteria, String tag) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return entitlementRepository.save(Entitlement.builder().customerAccountId(account.getId())
                .electionTypeId(electionTypeRepository.findByCode(ElectionTypeCode.VIDHAN_SABHA).orElseThrow().getId())
                .datasetId(ds.getId()).storageTier(DatasetTier.MASTER).criteria(criteria).criteriaHash((tag + "c").repeat(32).substring(0, 64))
                .scopeLabel("sw " + tag).source(EntitlementSource.DIRECT).status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.DAYS)).validUntil(now.plus(30, ChronoUnit.DAYS)).mobileAccess(false)
                .createdByUserId(owner.getId()).createdAt(now).build());
    }

    private Long voter(Dataset ds, int serial, String state, int ac, int part, String house) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO voter (dataset_id, serial_no, voter_id, state, ac_no, part_no, v_fname_en, v_lname_en, house_no, age, sex, source_record_key_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'FN', 'LN', ?, 40, 'M', ?) RETURNING id", Long.class,
                ds.getId(), serial, "SW" + ds.getId() + "-" + serial, state, ac, part, house,
                com.elecsarthi.security.device.CryptoUtils.sha256Hex(("sw-" + ds.getId() + "-" + serial).getBytes()));
    }
}
