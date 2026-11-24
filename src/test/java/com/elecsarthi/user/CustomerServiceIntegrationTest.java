package com.elecsarthi.user;

import com.elecsarthi.dto.request.CreateAgentRequest;
import com.elecsarthi.dto.request.CreateSubownerRequest;
import com.elecsarthi.dto.request.ProvisionCustomerRequest;
import com.elecsarthi.dto.response.CustomerProvisionResponse;
import com.elecsarthi.dto.response.ReissueCodeResponse;
import com.elecsarthi.dto.response.UserDetailResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.UserSession;
import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SessionStatus;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.service.CustomerService;
import com.elecsarthi.service.UserService;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class CustomerServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private com.elecsarthi.repository.UserDeviceRepository deviceRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AppUser owner;
    private AppUser subowner;
    private AppUser agent1;
    private AppUser agent2;

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
    }

    @Test
    @DisplayName("Owner creates Subowner and Agent successfully")
    void testOwnerCreatePlatformUsers() {
        UserDetailResponse sub = userService.createSubowner(
                new CreateSubownerRequest("new_sub", "New Subowner", "Pass12345!"), owner.getId());
        assertNotNull(sub.getId());
        assertEquals(Role.SUBOWNER, sub.getRole());

        UserDetailResponse ag = userService.createAgent(
                new CreateAgentRequest("new_ag", "New Agent", "Pass12345!"), owner.getId());
        assertNotNull(ag.getId());
        assertEquals(Role.AGENT, ag.getRole());
    }

    @Test
    @DisplayName("Subowner can create Agent, but cannot create Subowner")
    void testSubownerPermissions() {
        UserDetailResponse ag = userService.createAgent(
                new CreateAgentRequest("sub_ag", "Sub Created Agent", "Pass12345!"), subowner.getId());
        assertNotNull(ag.getId());
        assertEquals(Role.AGENT, ag.getRole());
    }

    @Test
    @DisplayName("Agent provisions customer: 10-char Crockford code generated, owning agent set")
    void testAgentProvisionCustomer() {
        ProvisionCustomerRequest req = ProvisionCustomerRequest.builder()
                .customerName("Rahul Sharma")
                .partyOrOrgName("Independent")
                .contactPhone("+919876543210")
                .contactEmail("rahul@example.com")
                .build();

        CustomerProvisionResponse resp = customerService.provisionCustomer(req, agent1.getId(), Role.AGENT);

        assertNotNull(resp.getId());
        assertEquals("Rahul Sharma", resp.getCustomerName());
        assertEquals("+919876543210", resp.getContactPhone());
        assertEquals(ActivationStatus.PENDING, resp.getActivationStatus());
        assertNotNull(resp.getActivationCode());
        assertEquals(10, resp.getActivationCode().length(), "Activation code must be exactly 10 characters");

        // Verify CustomerAccount in database
        CustomerAccount account = customerAccountRepository.findById(resp.getId()).orElseThrow();
        assertEquals(agent1.getId(), account.getOwningAgentUserId(), "Owning agent must be caller agent");
        assertEquals(agent1.getId(), account.getCreatedByUserId());
        assertNotNull(account.getActivationCodeHash());

        // Verify primary app_user created in PENDING status
        AppUser custUser = userRepository.findByCustomerAccountIdAndRole(account.getId(), Role.CUSTOMER).orElseThrow();
        assertEquals(UserStatus.PENDING, custUser.getStatus());
        assertEquals("+919876543210", custUser.getUsername());
    }

    @Test
    @DisplayName("Duplicate phone registration is rejected with 409 CONFLICT")
    void testDuplicatePhoneRejected() {
        ProvisionCustomerRequest req1 = ProvisionCustomerRequest.builder()
                .customerName("Customer A")
                .contactPhone("+919999900001")
                .build();
        customerService.provisionCustomer(req1, agent1.getId(), Role.AGENT);

        ProvisionCustomerRequest req2 = ProvisionCustomerRequest.builder()
                .customerName("Customer B")
                .contactPhone("+919999900001")
                .build();

        ConflictException ex = assertThrows(ConflictException.class, () ->
                customerService.provisionCustomer(req2, agent2.getId(), Role.AGENT));
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    @DisplayName("Owning agent can reissue activation code; different agent cannot")
    void testReissueActivationCodePermissions() {
        ProvisionCustomerRequest req = ProvisionCustomerRequest.builder()
                .customerName("Test Cust")
                .contactPhone("+919999900002")
                .build();
        CustomerProvisionResponse prov = customerService.provisionCustomer(req, agent1.getId(), Role.AGENT);
        String oldCode = prov.getActivationCode();

        // Agent 2 attempts to reissue for Agent 1's customer -> 404
        ApiException ex = assertThrows(ApiException.class, () ->
                customerService.reissueActivationCode(prov.getId(), agent2.getId(), Role.AGENT));
        assertEquals(404, ex.getStatus().value());

        // Agent 1 reissues successfully
        ReissueCodeResponse reissued = customerService.reissueActivationCode(prov.getId(), agent1.getId(), Role.AGENT);
        assertNotNull(reissued.getActivationCode());
        assertEquals(10, reissued.getActivationCode().length());
        assertNotEquals(oldCode, reissued.getActivationCode());
    }

    @Test
    @DisplayName("Suspending customer terminates active sessions and increments policy version")
    void testCustomerSuspensionTerminatesSessions() {
        ProvisionCustomerRequest req = ProvisionCustomerRequest.builder()
                .customerName("Active Cust")
                .contactPhone("+919999900003")
                .build();
        CustomerProvisionResponse prov = customerService.provisionCustomer(req, agent1.getId(), Role.AGENT);
        CustomerAccount account = customerAccountRepository.findById(prov.getId()).orElseThrow();

        // Create an active device and session for the customer user
        AppUser custUser = userRepository.findByCustomerAccountIdAndRole(account.getId(), Role.CUSTOMER).orElseThrow();

        com.elecsarthi.entity.UserDevice device = com.elecsarthi.entity.UserDevice.builder()
                .userId(custUser.getId())
                .userRole(Role.CUSTOMER)
                .customerAccountId(account.getId())
                .installationId("inst-cust-test")
                .platform(DevicePlatform.ANDROID)
                .status(DeviceStatus.ACTIVE)
                .build();
        device = deviceRepository.save(device);

        UUID sessionId = UUID.randomUUID();
        UserSession session = UserSession.builder()
                .id(sessionId)
                .userId(custUser.getId())
                .userRole(Role.CUSTOMER)
                .deviceId(device.getId())
                .clientType(ClientType.MOBILE)
                .status(SessionStatus.ACTIVE)
                .issuedAt(Instant.now())
                .lastSeenAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();
        sessionRepository.save(session);

        // Suspend customer
        customerService.suspendCustomer(account.getId(), "Payment failure / breach", owner.getId(), Role.OWNER);

        CustomerAccount updatedAccount = customerAccountRepository.findById(account.getId()).orElseThrow();
        assertEquals(UserStatus.SUSPENDED, updatedAccount.getStatus());
        assertEquals(2, updatedAccount.getMobileAccessPolicyVersion(), "Policy version must increment on suspension");

        // Verify session was immediately terminated
        UserSession updatedSession = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals(SessionStatus.TERMINATED, updatedSession.getStatus());
        assertEquals("CUSTOMER_SUSPENDED", updatedSession.getTerminationReason());
    }

    @Test
    @DisplayName("Owner can reassign customer owning agent; non-existent agent rejected")
    void testReassignOwningAgent() {
        ProvisionCustomerRequest req = ProvisionCustomerRequest.builder()
                .customerName("Reassign Test")
                .contactPhone("+919999900004")
                .build();
        CustomerProvisionResponse prov = customerService.provisionCustomer(req, agent1.getId(), Role.AGENT);

        // Reassign to agent2
        customerService.reassignAgent(prov.getId(), agent2.getId(), owner.getId());

        CustomerAccount updated = customerAccountRepository.findById(prov.getId()).orElseThrow();
        assertEquals(agent2.getId(), updated.getOwningAgentUserId());

        // Reassigning to invalid ID rejected
        ApiException ex = assertThrows(ApiException.class, () ->
                customerService.reassignAgent(prov.getId(), 999999L, owner.getId()));
        assertEquals(400, ex.getStatus().value());
    }
}
