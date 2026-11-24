package com.elecsarthi.security;

import com.elecsarthi.dto.request.CreateBoothAssignmentRequest;
import com.elecsarthi.dto.request.CreateSaleRequest;
import com.elecsarthi.dto.request.OfflineWritebackRequest;
import com.elecsarthi.dto.request.RequestOfflineLeaseRequest;
import com.elecsarthi.dto.request.RevokeBoothAssignmentRequest;
import com.elecsarthi.dto.request.SaleLineRequest;
import com.elecsarthi.dto.request.StartSyncSessionRequest;
import com.elecsarthi.dto.request.VoterSearchRequest;
import com.elecsarthi.entity.AgentSale;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.entity.OfflineLease;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.BoothAssignmentStatus;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.DurationUnit;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SaleStatus;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.repository.AgentSaleRepository;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.BoothAssignmentRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.OfflineLeaseRepository;
import com.elecsarthi.service.CustomerService;
import com.elecsarthi.service.EntitlementService;
import com.elecsarthi.service.OfflineLeaseService;
import com.elecsarthi.service.SaleService;
import com.elecsarthi.service.SubuserService;
import com.elecsarthi.service.SyncService;
import com.elecsarthi.service.VoterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Two complete tenants (Agent A with Customer A, Agent B with Customer B). A resource of the other tenant must be
 * indistinguishable from a resource that does not exist (404, same message), while an operation the caller's
 * role is categorically forbidden to perform stays 403.
 */
public class CrossTenantConcealmentIntegrationTest extends SecurityIntegrationTestSupport {

    private static final long MISSING_ID = 9_000_000_000L;

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
    private AgentSaleRepository agentSaleRepository;
    @Autowired
    private BoothAssignmentRepository boothAssignmentRepository;
    @Autowired
    private OfflineLeaseRepository offlineLeaseRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private EntitlementService entitlementService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private SaleService saleService;
    @Autowired
    private VoterService voterService;
    @Autowired
    private SyncService syncService;
    @Autowired
    private SubuserService subuserService;
    @Autowired
    private OfflineLeaseService offlineLeaseService;

    private AppUser owner;
    private AppUser subowner;
    private AppUser agentA;
    private AppUser agentB;
    private CustomerAccount customerA;
    private CustomerAccount customerB;
    private AppUser customerUserA;
    private AppUser customerUserB;
    private AppUser subuserA;
    private AppUser subuserB;
    private Entitlement entitlementA;
    private Entitlement entitlementB;
    private AgentSale saleB;
    private BoothAssignment assignmentB;
    private OfflineLease leaseB;
    private ElectionType electionType;

    @BeforeEach
    void createTwoTenants() {
        Instant now = Instant.now();
        owner = user("ct_owner", Role.OWNER, null);
        subowner = user("ct_subowner", Role.SUBOWNER, null);
        agentA = user("ct_agent_a", Role.AGENT, null);
        agentB = user("ct_agent_b", Role.AGENT, null);
        customerA = customer("Tenant A", "+919800000001", agentA);
        customerB = customer("Tenant B", "+919800000002", agentB);
        customerUserA = user("ct_cust_a", Role.CUSTOMER, customerA);
        customerUserB = user("ct_cust_b", Role.CUSTOMER, customerB);
        subuserA = user("ct_sub_a", Role.SUBUSER, customerA);
        subuserB = user("ct_sub_b", Role.SUBUSER, customerB);

        electionType = electionTypeRepository.findByCode(ElectionTypeCode.VIDHAN_SABHA).orElseThrow();
        Dataset dataset = datasetRepository.save(Dataset.builder()
                .name("Tenant Dataset")
                .originalFileName("tenants.xlsx")
                .fileSha256("c".repeat(64))
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(now.plus(60, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .build());
        entitlementA = entitlement(customerA, dataset, 12, "a".repeat(64), now);
        entitlementB = entitlement(customerB, dataset, 15, "b".repeat(64), now);

        saleB = agentSaleRepository.save(AgentSale.builder()
                .soldByUserId(agentB.getId())
                .customerAccountId(customerB.getId())
                .electionTypeId(electionType.getId())
                .storageTier(DatasetTier.MASTER)
                .idempotencyKey("tenant-b-sale")
                .requestSha256("d".repeat(64))
                .soldAt(now)
                .status(SaleStatus.COMPLETED)
                .build());

        UserDevice deviceB = deviceRepository.save(UserDevice.builder()
                .userId(subuserB.getId())
                .customerAccountId(customerB.getId())
                .userRole(Role.SUBUSER)
                .platform(DevicePlatform.ANDROID)
                .deviceName("Tenant B phone")
                .installationId("inst-tenant-b")
                .publicKey("pk-tenant-b")
                .status(DeviceStatus.ACTIVE)
                .activatedAt(now)
                .lastUsedAt(now)
                .lastRequestCounter(0L)
                .createdAt(now)
                .build());
        assignmentB = boothAssignmentRepository.save(BoothAssignment.builder()
                .subuserId(subuserB.getId())
                .customerAccountId(customerB.getId())
                .entitlementId(entitlementB.getId())
                .boothValue(20)
                .slot((short) 1)
                .validFrom(now.minus(1, ChronoUnit.DAYS))
                .validUntil(now.plus(5, ChronoUnit.DAYS))
                .status(BoothAssignmentStatus.ACTIVE)
                .assignedByUserId(customerUserB.getId())
                .createdAt(now)
                .build());
        leaseB = offlineLeaseRepository.save(OfflineLease.builder()
                .boothAssignmentId(assignmentB.getId())
                .subuserId(subuserB.getId())
                .deviceId(deviceB.getId())
                .leaseSeq(1L)
                .payloadSha256("e".repeat(64))
                .signingKeyVersion((short) 1)
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.DAYS))
                .build());
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

    private CustomerAccount customer(String name, String phone, AppUser owningAgent) {
        return customerAccountRepository.save(CustomerAccount.builder()
                .customerName(name)
                .contactPhone(phone)
                .createdByUserId(owningAgent.getId())
                .owningAgentUserId(owningAgent.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());
    }

    private Entitlement entitlement(CustomerAccount account, Dataset dataset, int acNo, String criteriaHash, Instant now) {
        return entitlementRepository.save(Entitlement.builder()
                .customerAccountId(account.getId())
                .electionTypeId(electionType.getId())
                .datasetId(dataset.getId())
                .storageTier(DatasetTier.MASTER)
                .criteria("{\"ac_no\":" + acNo + ",\"state\":\"RAJASTHAN\"}")
                .criteriaHash(criteriaHash)
                .scopeLabel("RAJASTHAN AC " + acNo)
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(1, ChronoUnit.DAYS))
                .validUntil(now.plus(30, ChronoUnit.DAYS))
                .mobileAccess(false)
                .createdByUserId(owner.getId())
                .createdAt(now)
                .build());
    }

    private static ApiException thrownBy(Executable executable) {
        return assertThrows(ApiException.class, executable);
    }

    /** The other tenant's resource must produce exactly the answer a missing one does. */
    private static void assertConcealed(Executable otherTenants, Executable missing) {
        ApiException concealed = thrownBy(otherTenants);
        ApiException absent = thrownBy(missing);
        assertEquals(HttpStatus.NOT_FOUND, absent.getStatus());
        assertEquals(absent.getStatus(), concealed.getStatus(), "other tenant's resource must answer like a missing one");
        assertEquals(absent.getErrorCode(), concealed.getErrorCode());
        assertEquals(absent.getMessage().replaceAll("\\d+", "#"), concealed.getMessage().replaceAll("\\d+", "#"),
                "the message must not tell the two cases apart");
    }

    private static void assertForbidden(Executable executable) {
        assertEquals(HttpStatus.FORBIDDEN, thrownBy(executable).getStatus());
    }

    @Test
    @DisplayName("Entitlements: a Customer or Agent cannot read another tenant's entitlements, by id or by Customer")
    void entitlementsOfOtherTenantAreConcealed() {
        // The Customer role used to have no ownership check here at all
        assertConcealed(() -> entitlementService.getEntitlementById(customerUserA.getId(), Role.CUSTOMER, entitlementB.getId()),
                () -> entitlementService.getEntitlementById(customerUserA.getId(), Role.CUSTOMER, MISSING_ID));
        assertConcealed(() -> entitlementService.getEntitlementsByCustomer(customerUserA.getId(), Role.CUSTOMER, customerB.getId()),
                () -> entitlementService.getEntitlementsByCustomer(customerUserA.getId(), Role.CUSTOMER, MISSING_ID));
        assertConcealed(() -> entitlementService.getEntitlementById(agentA.getId(), Role.AGENT, entitlementB.getId()),
                () -> entitlementService.getEntitlementById(agentA.getId(), Role.AGENT, MISSING_ID));
        assertConcealed(() -> entitlementService.getEntitlementsByCustomer(agentA.getId(), Role.AGENT, customerB.getId()),
                () -> entitlementService.getEntitlementsByCustomer(agentA.getId(), Role.AGENT, MISSING_ID));

        assertEquals(entitlementA.getId(), entitlementService.getEntitlementById(customerUserA.getId(), Role.CUSTOMER, entitlementA.getId()).getId());
        assertEquals(1, entitlementService.getEntitlementsByCustomer(customerUserA.getId(), Role.CUSTOMER, customerA.getId()).size());
        assertEquals(1, entitlementService.getEntitlementsByCustomer(agentA.getId(), Role.AGENT, customerA.getId()).size());
        assertEquals(1, entitlementService.getEntitlementsByCustomer(owner.getId(), Role.OWNER, customerB.getId()).size());
        assertEquals(1, entitlementService.getEntitlementsByCustomer(subowner.getId(), Role.SUBOWNER, customerB.getId()).size());

        assertForbidden(() -> entitlementService.getEntitlementById(subuserA.getId(), Role.SUBUSER, entitlementA.getId()));
    }

    @Test
    @DisplayName("Customer detail: a Customer cannot read another Customer's account; an Agent only their own Customers")
    void customerDetailOfOtherTenantIsConcealed() {
        assertConcealed(() -> customerService.getCustomer(customerB.getId(), customerUserA.getId(), Role.CUSTOMER),
                () -> customerService.getCustomer(MISSING_ID, customerUserA.getId(), Role.CUSTOMER));
        assertConcealed(() -> customerService.getCustomer(customerB.getId(), agentA.getId(), Role.AGENT),
                () -> customerService.getCustomer(MISSING_ID, agentA.getId(), Role.AGENT));

        assertEquals(customerA.getId(), customerService.getCustomer(customerA.getId(), customerUserA.getId(), Role.CUSTOMER).getId());
        assertEquals(customerA.getId(), customerService.getCustomer(customerA.getId(), agentA.getId(), Role.AGENT).getId());
        assertNotNull(customerService.getCustomer(customerB.getId(), owner.getId(), Role.OWNER));
    }

    @Test
    @DisplayName("Sales: another Agent's sale and Customer are concealed; Subowners stay forbidden by role")
    void salesOfOtherTenantAreConcealed() {
        assertConcealed(() -> saleService.getSale(agentA.getId(), Role.AGENT, saleB.getId()),
                () -> saleService.getSale(agentA.getId(), Role.AGENT, MISSING_ID));
        assertConcealed(() -> saleService.getSalesByCustomer(agentA.getId(), Role.AGENT, customerB.getId()),
                () -> saleService.getSalesByCustomer(agentA.getId(), Role.AGENT, MISSING_ID));

        CreateSaleRequest sellToB = saleRequest(customerB.getId());
        assertConcealed(() -> saleService.createSale(agentA.getId(), sellToB),
                () -> saleService.createSale(agentA.getId(), saleRequest(MISSING_ID)));

        assertEquals(saleB.getId(), saleService.getSale(agentB.getId(), Role.AGENT, saleB.getId()).getId());
        assertEquals(saleB.getId(), saleService.getSale(owner.getId(), Role.OWNER, saleB.getId()).getId());

        assertForbidden(() -> saleService.getSale(subowner.getId(), Role.SUBOWNER, saleB.getId()));
        assertForbidden(() -> saleService.getSale(subowner.getId(), Role.SUBOWNER, MISSING_ID));
        assertForbidden(() -> saleService.getSalesByCustomer(subowner.getId(), Role.SUBOWNER, customerB.getId()));
        assertForbidden(() -> saleService.createSale(subowner.getId(), sellToB));
    }

    private CreateSaleRequest saleRequest(Long customerAccountId) {
        return CreateSaleRequest.builder()
                .customerAccountId(customerAccountId)
                .electionTypeId(electionType.getId())
                .storageTier(DatasetTier.MASTER)
                .idempotencyKey("probe-" + UUID.randomUUID())
                .lines(List.of(SaleLineRequest.builder()
                        .criteria(Map.of("state", "RAJASTHAN", "ac_no", 15))
                        .durationValue(5)
                        .durationUnit(DurationUnit.DAY)
                        .build()))
                .build();
    }

    @Test
    @DisplayName("Voter reads and bulk sync: another Customer's entitlement is concealed")
    void voterAndSyncAccessToOtherTenantsEntitlementIsConcealed() {
        VoterSearchRequest searchB = VoterSearchRequest.builder().entitlementId(entitlementB.getId()).page(0).limit(10).build();
        VoterSearchRequest searchMissing = VoterSearchRequest.builder().entitlementId(MISSING_ID).page(0).limit(10).build();
        assertConcealed(() -> voterService.searchVoters(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), searchB),
                () -> voterService.searchVoters(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), searchMissing));
        assertConcealed(() -> voterService.getVoterDetail(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), entitlementB.getId(), 1L),
                () -> voterService.getVoterDetail(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), MISSING_ID, 1L));

        assertConcealed(() -> syncService.startSyncSession(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), UUID.randomUUID(),
                        StartSyncSessionRequest.builder().entitlementId(entitlementB.getId()).build()),
                () -> syncService.startSyncSession(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), UUID.randomUUID(),
                        StartSyncSessionRequest.builder().entitlementId(MISSING_ID).build()));

        // Roles that may never read voters keep their categorical 403, whatever the id
        assertForbidden(() -> voterService.searchVoters(agentA.getId(), Role.AGENT, customerA.getId(), searchB));
        assertForbidden(() -> voterService.searchVoters(owner.getId(), Role.OWNER, customerA.getId(), searchB));
    }

    @Test
    @DisplayName("Subusers, booth assignments and leases of another Customer are concealed")
    void subuserBoothAndLeaseOfOtherTenantAreConcealed() {
        assertConcealed(() -> subuserService.getSubuserDetail(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), subuserB.getId()),
                () -> subuserService.getSubuserDetail(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), MISSING_ID));

        CreateBoothAssignmentRequest assignToA = CreateBoothAssignmentRequest.builder()
                .entitlementId(entitlementA.getId()).boothValue(10).slot((short) 1).durationDays(2).build();
        assertConcealed(() -> subuserService.assignBooth(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), subuserB.getId(), assignToA),
                () -> subuserService.assignBooth(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), MISSING_ID, assignToA));
        CreateBoothAssignmentRequest assignOfB = CreateBoothAssignmentRequest.builder()
                .entitlementId(entitlementB.getId()).boothValue(10).slot((short) 1).durationDays(2).build();
        assertConcealed(() -> subuserService.assignBooth(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), subuserA.getId(), assignOfB),
                () -> subuserService.assignBooth(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), subuserA.getId(),
                        CreateBoothAssignmentRequest.builder().entitlementId(MISSING_ID).boothValue(10).slot((short) 1).durationDays(2).build()));

        RevokeBoothAssignmentRequest revoke = RevokeBoothAssignmentRequest.builder().revokeReason("probe").build();
        assertConcealed(() -> subuserService.revokeBoothAssignment(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), assignmentB.getId(), revoke),
                () -> subuserService.revokeBoothAssignment(customerUserA.getId(), Role.CUSTOMER, customerA.getId(), MISSING_ID, revoke));
        // A role that may never revoke booths is told so, whether or not the assignment exists
        assertForbidden(() -> subuserService.revokeBoothAssignment(agentA.getId(), Role.AGENT, customerA.getId(), assignmentB.getId(), revoke));
        assertForbidden(() -> subuserService.revokeBoothAssignment(agentA.getId(), Role.AGENT, customerA.getId(), MISSING_ID, revoke));

        UUID anyDevice = UUID.randomUUID();
        assertConcealed(() -> offlineLeaseService.requestLease(subuserA.getId(), Role.SUBUSER, anyDevice,
                        RequestOfflineLeaseRequest.builder().boothAssignmentId(assignmentB.getId()).build()),
                () -> offlineLeaseService.requestLease(subuserA.getId(), Role.SUBUSER, anyDevice,
                        RequestOfflineLeaseRequest.builder().boothAssignmentId(MISSING_ID).build()));

        OfflineWritebackRequest writebackToB = OfflineWritebackRequest.builder().leaseId(leaseB.getId()).marks(List.of()).build();
        OfflineWritebackRequest writebackMissing = OfflineWritebackRequest.builder().leaseId(UUID.randomUUID()).marks(List.of()).build();
        assertConcealed(() -> offlineLeaseService.processWriteback(subuserA.getId(), Role.SUBUSER, anyDevice, writebackToB),
                () -> offlineLeaseService.processWriteback(subuserA.getId(), Role.SUBUSER, anyDevice, writebackMissing));

        assertNotEquals(HttpStatus.FORBIDDEN, thrownBy(() -> subuserService.getSubuserDetail(
                customerUserA.getId(), Role.CUSTOMER, customerA.getId(), subuserB.getId())).getStatus());
    }
}
