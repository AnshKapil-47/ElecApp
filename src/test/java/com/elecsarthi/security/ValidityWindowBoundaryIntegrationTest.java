package com.elecsarthi.security;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.dto.request.RequestOfflineLeaseRequest;
import com.elecsarthi.dto.request.VoterSearchRequest;
import com.elecsarthi.dto.response.OfflineLeaseResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.BoothAssignmentStatus;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.DurationUnit;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.BoothAssignmentRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.service.AreaResolver;
import com.elecsarthi.service.AreaResolver.ValidityWindow;
import com.elecsarthi.service.AuthService;
import com.elecsarthi.service.CriteriaNormalizer.NormalizedCriteria;
import com.elecsarthi.service.OfflineLeaseService;
import com.elecsarthi.service.VoterService;
import com.elecsarthi.support.MutableClock;
import com.elecsarthi.support.TestClockConfig;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Time-window rules at their exact boundaries, with the application Clock replaced by a {@link MutableClock}.
 * Validity windows are start-inclusive and end-exclusive; business calendar math runs in Asia/Kolkata.
 */
@Import(TestClockConfig.class)
public class ValidityWindowBoundaryIntegrationTest extends SecurityIntegrationTestSupport {

    private static final Instant T = Instant.parse("2027-03-10T06:00:00Z");
    private static final Duration MICRO = Duration.of(1, ChronoUnit.MICROS);

    @Autowired
    private MutableClock testClock;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private PasswordEncoder passwordEncoder;
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
    private AuthService authService;
    @Autowired
    private VoterService voterService;
    @Autowired
    private OfflineLeaseService offlineLeaseService;
    @Autowired
    private AreaResolver areaResolver;

    private AppUser owner;
    private AppUser customerUser;
    private AppUser subuser;
    private CustomerAccount customer;
    private ElectionType electionType;
    private Dataset masterDataset;

    @BeforeEach
    void fixTimeAndCreateTenant() {
        testClock.set(T);
        owner = user("bw_owner", Role.OWNER, null);
        customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Boundary Customer")
                .contactPhone("+919800000055")
                .createdByUserId(owner.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());
        customerUser = user("bw_customer", Role.CUSTOMER, customer);
        subuser = user("bw_subuser", Role.SUBUSER, customer);
        electionType = electionTypeRepository.findByCode(ElectionTypeCode.VIDHAN_SABHA).orElseThrow();
        masterDataset = dataset(DatasetTier.MASTER, T.plus(60, ChronoUnit.DAYS));
    }

    private AppUser user(String username, Role role, CustomerAccount account) {
        return userRepository.save(AppUser.builder()
                .username(username)
                .fullName(username)
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(role)
                .status(UserStatus.ACTIVE)
                .customerAccountId(account != null ? account.getId() : null)
                .build());
    }

    private Dataset dataset(DatasetTier tier, Instant deleteAfter) {
        return datasetRepository.save(Dataset.builder()
                .name("Boundary " + tier + " " + UUID.randomUUID())
                .originalFileName("boundary.xlsx")
                .fileSha256(UUID.randomUUID().toString().replace("-", "").repeat(2))
                .storageTier(tier)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(deleteAfter)
                .importedByUserId(owner.getId())
                .build());
    }

    private Entitlement entitlement(Dataset dataset, Instant validFrom, Instant validUntil) {
        return entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customer.getId())
                .electionTypeId(electionType.getId())
                .datasetId(dataset.getId())
                .storageTier(dataset.getStorageTier())
                .criteria("{\"ac_no\":12,\"state\":\"RAJASTHAN\"}")
                .criteriaHash("1".repeat(64))
                .scopeLabel("RAJASTHAN AC 12")
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(validFrom)
                .validUntil(validUntil)
                .mobileAccess(false)
                .createdByUserId(owner.getId())
                .createdAt(T)
                .build());
    }

    private NormalizedCriteria area() {
        return new NormalizedCriteria("{\"ac_no\":12,\"state\":\"RAJASTHAN\"}", "1".repeat(64),
                Map.of("state", "RAJASTHAN", "ac_no", 12), "RAJASTHAN AC 12");
    }

    private ValidityWindow window(DatasetTier tier, Dataset dataset, int value, DurationUnit unit, Instant now) {
        return areaResolver.validityWindow(customer.getId(), electionType, tier, area(), dataset, value, unit, now);
    }

    @Test
    @DisplayName("Voter access: an entitlement is usable from validFrom (inclusive) up to validUntil (exclusive)")
    void voterAccessWindowIsStartInclusiveEndExclusive() {
        Instant from = T.plus(1, ChronoUnit.HOURS);
        Instant until = T.plus(2, ChronoUnit.HOURS);
        Entitlement entitlement = entitlement(masterDataset, from, until);
        VoterSearchRequest request = VoterSearchRequest.builder().entitlementId(entitlement.getId()).page(0).limit(10).build();

        testClock.set(from.minus(MICRO));
        assertThrows(ForbiddenException.class, () -> voterService.searchVoters(customerUser.getId(), Role.CUSTOMER, customer.getId(), request));
        testClock.set(from);
        assertDoesNotThrow(() -> voterService.searchVoters(customerUser.getId(), Role.CUSTOMER, customer.getId(), request));
        testClock.set(until.minus(MICRO));
        assertDoesNotThrow(() -> voterService.searchVoters(customerUser.getId(), Role.CUSTOMER, customer.getId(), request));
        testClock.set(until);
        assertThrows(ForbiddenException.class, () -> voterService.searchVoters(customerUser.getId(), Role.CUSTOMER, customer.getId(), request));
    }

    @Test
    @DisplayName("Validity math runs in Asia/Kolkata: MONTH clamps to month end and follows the IST calendar day")
    void validityMathUsesIndiaCalendar() {
        Dataset longTerm = dataset(DatasetTier.LONG_TERM, T.plus(365, ChronoUnit.DAYS));

        // 31 Jan 10:00 IST + 1 MONTH clamps to 28 Feb 10:00 IST
        Instant midMorningIst = Instant.parse("2027-01-31T04:30:00Z");
        assertEquals(Instant.parse("2027-02-28T04:30:00Z"), window(DatasetTier.LONG_TERM, longTerm, 1, DurationUnit.MONTH, midMorningIst).validUntil());

        // 30 Jan 20:00 UTC is already 31 Jan 01:30 in India: IST gives 28 Feb 01:30 IST (27 Feb 20:00 UTC), UTC math would give 28 Feb 20:00 UTC
        Instant lateUtcEarlyIst = Instant.parse("2027-01-30T20:00:00Z");
        assertEquals(Instant.parse("2027-02-27T20:00:00Z"), window(DatasetTier.LONG_TERM, longTerm, 1, DurationUnit.MONTH, lateUtcEarlyIst).validUntil());

        assertEquals(Instant.parse("2027-02-06T20:00:00Z"), window(DatasetTier.LONG_TERM, longTerm, 1, DurationUnit.WEEK, lateUtcEarlyIst).validUntil());
        assertEquals(Instant.parse("2027-02-01T20:00:00Z"), window(DatasetTier.LONG_TERM, longTerm, 2, DurationUnit.DAY, lateUtcEarlyIst).validUntil());
    }

    @Test
    @DisplayName("Renewals are back-to-back; a lapsed entitlement does not delay the new one")
    void renewalStartsWhenTheCurrentEntitlementEnds() {
        Instant currentEnd = T.plus(3, ChronoUnit.DAYS);
        entitlement(masterDataset, T.minus(1, ChronoUnit.DAYS), currentEnd);

        assertEquals(currentEnd, window(DatasetTier.MASTER, masterDataset, 1, DurationUnit.DAY, T).validFrom());
        assertEquals(currentEnd, window(DatasetTier.MASTER, masterDataset, 1, DurationUnit.DAY, currentEnd.minus(MICRO)).validFrom());
        assertEquals(currentEnd, window(DatasetTier.MASTER, masterDataset, 1, DurationUnit.DAY, currentEnd).validFrom(),
                "at the exact end instant there is nothing left to renew, so the new window starts now (which is the same instant)");
        Instant later = currentEnd.plus(1, ChronoUnit.HOURS);
        assertEquals(later, window(DatasetTier.MASTER, masterDataset, 1, DurationUnit.DAY, later).validFrom());
    }

    @Test
    @DisplayName("A MASTER entitlement may end exactly at the dataset's deletion time but not one microsecond later")
    void masterEntitlementCannotOutliveItsDataset() {
        Instant deleteAfter = T.plus(10, ChronoUnit.DAYS);
        Dataset dataset = dataset(DatasetTier.MASTER, deleteAfter);

        assertEquals(deleteAfter, window(DatasetTier.MASTER, dataset, 10, DurationUnit.DAY, T).validUntil());
        BadRequestException tooLong = assertThrows(BadRequestException.class,
                () -> window(DatasetTier.MASTER, dataset, 10, DurationUnit.DAY, T.plus(MICRO)));
        assertNotNull(tooLong.getMessage());
    }

    @Test
    @DisplayName("Offline lease: expires at the earliest of 24 hours, the booth assignment and the entitlement; not issued at the assignment's end")
    void leaseExpiryAndIssuanceBoundaries() {
        Entitlement entitlement = entitlement(masterDataset, T.minus(1, ChronoUnit.DAYS), T.plus(30, ChronoUnit.DAYS));
        UserDevice device = deviceRepository.save(UserDevice.builder()
                .userId(subuser.getId())
                .customerAccountId(customer.getId())
                .userRole(Role.SUBUSER)
                .platform(DevicePlatform.ANDROID)
                .deviceName("Boundary phone")
                .installationId("inst-boundary")
                .publicKey("pk-boundary")
                .status(DeviceStatus.ACTIVE)
                .activatedAt(T)
                .lastUsedAt(T)
                .lastRequestCounter(0L)
                .createdAt(T)
                .build());
        BoothAssignment longAssignment = assignment(entitlement, (short) 1, 10, T.plus(5, ChronoUnit.DAYS));
        BoothAssignment shortAssignment = assignment(entitlement, (short) 2, 11, T.plus(5, ChronoUnit.HOURS));

        OfflineLeaseResponse capped = offlineLeaseService.requestLease(subuser.getId(), Role.SUBUSER, device.getId(),
                RequestOfflineLeaseRequest.builder().boothAssignmentId(longAssignment.getId()).build());
        assertEquals(T.plus(24, ChronoUnit.HOURS), capped.getExpiresAt());

        testClock.set(T.plus(1, ChronoUnit.MINUTES));
        OfflineLeaseResponse endsWithAssignment = offlineLeaseService.requestLease(subuser.getId(), Role.SUBUSER, device.getId(),
                RequestOfflineLeaseRequest.builder().boothAssignmentId(shortAssignment.getId()).build());
        assertEquals(shortAssignment.getValidUntil(), endsWithAssignment.getExpiresAt());

        testClock.set(shortAssignment.getValidUntil().minus(MICRO));
        assertDoesNotThrow(() -> offlineLeaseService.requestLease(subuser.getId(), Role.SUBUSER, device.getId(),
                RequestOfflineLeaseRequest.builder().boothAssignmentId(shortAssignment.getId()).build()));

        testClock.set(shortAssignment.getValidUntil());
        ApiException atEnd = assertThrows(ApiException.class, () -> offlineLeaseService.requestLease(subuser.getId(), Role.SUBUSER, device.getId(),
                RequestOfflineLeaseRequest.builder().boothAssignmentId(shortAssignment.getId()).build()));
        assertEquals(ErrorCode.SUBUSER_BOOTH_EXPIRED, atEnd.getErrorCode());
    }

    private BoothAssignment assignment(Entitlement entitlement, short slot, int booth, Instant validUntil) {
        return boothAssignmentRepository.save(BoothAssignment.builder()
                .subuserId(subuser.getId())
                .customerAccountId(customer.getId())
                .entitlementId(entitlement.getId())
                .boothValue(booth)
                .slot(slot)
                .validFrom(T.minus(1, ChronoUnit.HOURS))
                .validUntil(validUntil)
                .status(BoothAssignmentStatus.ACTIVE)
                .assignedByUserId(customerUser.getId())
                .createdAt(T)
                .build());
    }

    @Test
    @DisplayName("Sessions: a web session lapses strictly after its idle expiry, freeing its slot for a new login")
    void sessionLapsesStrictlyAfterIdleExpiry() {
        LoginRequest login = LoginRequest.builder().identifier("bw_owner").password("Password123!").clientType(ClientType.WEB).build();
        for (int i = 0; i < 3; i++) {
            authService.login(login, "127.0.0.1");
        }
        Instant idleExpiry = T.plus(appProperties.getJwt().getWebIdleExpirationMinutes(), ChronoUnit.MINUTES);

        testClock.set(idleExpiry);
        assertThrows(ConflictException.class, () -> authService.login(login, "127.0.0.1"),
                "at the exact idle expiry the sessions are still live");

        testClock.set(idleExpiry.plus(MICRO));
        assertNotNull(authService.login(login, "127.0.0.1").getAccessToken(), "one microsecond later all three have lapsed");
    }

    @Test
    @DisplayName("Access tokens: accepted until their expiry and rejected after it, judged on the application Clock")
    void accessTokenExpiryFollowsTheClock() {
        String token = tokenProvider.generateAccessToken(UUID.randomUUID(), owner.getId(), owner.getUsername(), Role.OWNER, null);
        long minutes = appProperties.getJwt().getAccessTokenExpirationMinutes();

        testClock.set(T.plus(minutes - 1, ChronoUnit.MINUTES));
        assertEquals(owner.getUsername(), tokenProvider.parseClaims(token).getSubject());

        testClock.set(T.plus(minutes + 1, ChronoUnit.MINUTES));
        assertThrows(ExpiredJwtException.class, () -> tokenProvider.parseClaims(token));
    }
}
