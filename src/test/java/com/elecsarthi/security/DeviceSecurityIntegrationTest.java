package com.elecsarthi.security;

import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.repository.*;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuthService;
import com.elecsarthi.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Device registration, replacement, revocation and signed-request verification. */
public class DeviceSecurityIntegrationTest extends SecurityIntegrationTestSupport {

    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @BeforeEach
    void buildMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("Finding 10: Device Signature Verification - Rejects unsigned mobile request; accepts valid ECDSA signature")
    void testDeviceSignedRequestVerification() throws Exception {
        // 1. Create active Customer, user, and device
        AppUser creatorDev = userRepository.save(AppUser.builder()
                .username("creator_user_device")
                .fullName("Creator User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Signed Req Customer")
                .contactPhone("+919112233445")
                .status(UserStatus.ACTIVE)
                .createdByUserId(creatorDev.getId())
                .build());

        AppUser user = userRepository.save(AppUser.builder()
                .username("signed_cust")
                .fullName("Signed Customer")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .customerAccountId(customer.getId())
                .build());

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        KeyPair keyPair = kpg.generateKeyPair();
        String pubKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        UserDevice device = deviceRepository.save(UserDevice.builder()
                .userId(user.getId())
                .customerAccountId(customer.getId())
                .userRole(Role.CUSTOMER)
                .platform(DevicePlatform.ANDROID)
                .installationId(UUID.randomUUID().toString())
                .publicKey(pubKeyB64)
                .status(DeviceStatus.ACTIVE)
                .lastRequestCounter(0L)
                .createdAt(Instant.now())
                .build());

        // Login to get valid JWT token with deviceId attached
        String token = authService.login(LoginRequest.builder()
                .identifier("signed_cust")
                .password("Password123!")
                .clientType(ClientType.MOBILE)
                .installationId(device.getInstallationId())
                .build(), "127.0.0.1").getAccessToken();

        // 2. Call sensitive endpoint (/api/v1/sync/sessions) WITHOUT device signature headers -> must be rejected 401
        mockMvc.perform(post("/api/v1/sync/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entitlementId\":1,\"protocolVersion\":1}"))
                .andExpect(status().isUnauthorized());

        // 3. Call validateSignedRequest directly to verify ECDSA signing and anti-replay
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        long counter = 1L;
        String method = "POST";
        String path = "/api/v1/sync/sessions";
        byte[] body = "{\"entitlementId\":1}".getBytes();
        String bodySha = CryptoUtils.sha256Hex(body);

        String payloadToSign = String.join("\n",
                method, path, bodySha, String.valueOf(ts), nonce, String.valueOf(counter), "");
        String signature = signData(keyPair.getPrivate(), payloadToSign.getBytes());

        UserDevice validated = deviceService.validateSignedRequest(
                device.getId(), method, path, body, ts, nonce, counter, null, signature);
        assertNotNull(validated);
        assertEquals(1L, validated.getLastRequestCounter());

        // Replaying same nonce must fail with REPLAYED_NONCE
        ApiException replayEx = assertThrows(ApiException.class, () ->
                deviceService.validateSignedRequest(device.getId(), method, path, body, ts, nonce, 2L, null, signature));
        assertEquals("REPLAYED_NONCE", replayEx.getErrorCode().name());
    }

    @Test
    @DisplayName("Finding N1: Prevent password-only device replacement and enforce role-based device revocation")
    void testMobileDeviceReplacementPolicy() throws Exception {
        // 1. Create an agent and customer
        AppUser agent = userRepository.save(AppUser.builder()
                .username("rep_agent_" + UUID.randomUUID())
                .fullName("Replacement Agent")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Replacement Customer Org")
                .contactPhone("+9198" + String.format("%08d", Math.abs(new java.util.Random().nextInt(100000000))))
                .owningAgentUserId(agent.getId())
                .createdByUserId(agent.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());

        AppUser custUser = userRepository.save(AppUser.builder()
                .username("rep_cust_" + UUID.randomUUID())
                .fullName("Replacement Customer")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.CUSTOMER)
                .customerAccountId(customer.getId())
                .status(UserStatus.ACTIVE)
                .build());

        // 2. Case A: Initial login from mobile with no active device requires registration
        LoginRequest initialLogin = LoginRequest.builder()
                .identifier(custUser.getUsername())
                .password("Secret@123")
                .clientType(ClientType.MOBILE)
                .installationId("inst-initial-001")
                .build();

        var initialResp = authService.login(initialLogin, "127.0.0.1");
        assertTrue(initialResp.isDeviceRegistrationRequired());
        assertNotNull(initialResp.getRegistrationToken());

        // 3. Register and verify device
        KeyPair kp1 = generateEcKeyPair();
        String pubKey1 = Base64.getEncoder().encodeToString(kp1.getPublic().getEncoded());
        var regResp = deviceService.registerDevice(custUser.getId(),
                com.elecsarthi.dto.request.DeviceRegisterRequest.builder()
                        .installationId("inst-initial-001")
                        .publicKey(pubKey1)
                        .platform(DevicePlatform.ANDROID)
                        .deviceName("Pixel 8")
                        .build(),
                "127.0.0.1");

        String sig1 = signData(kp1.getPrivate(), regResp.getChallenge().getBytes(StandardCharsets.UTF_8));
        String jti1 = tokenProvider.parseClaims(initialResp.getRegistrationToken()).getId();
        deviceService.verifyDevice(custUser.getId(),
                com.elecsarthi.dto.request.DeviceVerifyRequest.builder()
                        .deviceId(regResp.getDeviceId())
                        .challengeSignature(sig1)
                        .build(),
                "127.0.0.1", jti1);

        // Verify device is ACTIVE
        UserDevice dev1 = deviceRepository.findById(regResp.getDeviceId()).orElseThrow();
        assertEquals(DeviceStatus.ACTIVE, dev1.getStatus());

        // 4. Case B: Login from same installation succeeds normally
        LoginRequest sameInstLogin = LoginRequest.builder()
                .identifier(custUser.getUsername())
                .password("Secret@123")
                .clientType(ClientType.MOBILE)
                .installationId("inst-initial-001")
                .build();
        var sameInstResp = authService.login(sameInstLogin, "127.0.0.1");
        assertFalse(sameInstResp.isDeviceRegistrationRequired());
        assertNotNull(sameInstResp.getAccessToken());

        // 5. Case C: Login from different installation while active device exists is FORBIDDEN
        LoginRequest diffInstLogin = LoginRequest.builder()
                .identifier(custUser.getUsername())
                .password("Secret@123")
                .clientType(ClientType.MOBILE)
                .installationId("inst-new-attacker-002")
                .build();
        ApiException diffEx = assertThrows(ApiException.class, () ->
                authService.login(diffInstLogin, "127.0.0.1"));
        assertEquals("DEVICE_CHANGE_NOT_ALLOWED", diffEx.getErrorCode().name());
        assertEquals(403, diffEx.getStatus().value());

        // Direct registration attempt for the user also rejected
        ApiException directRegEx = assertThrows(ApiException.class, () ->
                deviceService.registerDevice(custUser.getId(),
                        com.elecsarthi.dto.request.DeviceRegisterRequest.builder()
                                .installationId("inst-new-attacker-002")
                                .publicKey(pubKey1)
                                .platform(DevicePlatform.ANDROID)
                                .deviceName("Rogue Device")
                                .build(),
                        "127.0.0.1"));
        assertEquals("DEVICE_CHANGE_NOT_ALLOWED", directRegEx.getErrorCode().name());

        // 6. Role-based device revocation check:
        // Another agent cannot revoke this device
        AppUser otherAgent = userRepository.save(AppUser.builder()
                .username("rep_other_agent_" + UUID.randomUUID())
                .fullName("Other Agent")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build());

        ApiException unauthAgentEx = assertThrows(ApiException.class, () ->
                deviceService.revokeDevice(dev1.getId(), otherAgent.getId(), Role.AGENT, "127.0.0.1"));
        assertEquals(403, unauthAgentEx.getStatus().value());

        // Owning agent can revoke the device
        deviceService.revokeDevice(dev1.getId(), agent.getId(), Role.AGENT, "127.0.0.1");
        UserDevice revokedDev = deviceRepository.findById(dev1.getId()).orElseThrow();
        assertEquals(DeviceStatus.REVOKED, revokedDev.getStatus());

        // 7. After revocation, new installation can now log in and register
        var newInstResp = authService.login(diffInstLogin, "127.0.0.1");
        assertTrue(newInstResp.isDeviceRegistrationRequired());
        assertNotNull(newInstResp.getRegistrationToken());
    }

    @Test
    @DisplayName("Finding N1: Customer subuser device revocation authorization")
    void testCustomerSubuserDeviceRevocationAuthorization() throws Exception {
        AppUser agent = userRepository.save(AppUser.builder()
                .username("sub_agent_" + UUID.randomUUID())
                .fullName("Sub Agent")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Customer Org Two")
                .contactPhone("+9198" + String.format("%08d", Math.abs(new java.util.Random().nextInt(100000000))))
                .owningAgentUserId(agent.getId())
                .createdByUserId(agent.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());

        AppUser primaryCust = userRepository.save(AppUser.builder()
                .username("prim_cust_" + UUID.randomUUID())
                .fullName("Primary Customer")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.CUSTOMER)
                .customerAccountId(customer.getId())
                .status(UserStatus.ACTIVE)
                .build());

        AppUser subuser = userRepository.save(AppUser.builder()
                .username("subuser_" + UUID.randomUUID())
                .fullName("Subuser One")
                .passwordHash(passwordEncoder.encode("Secret@123"))
                .role(Role.SUBUSER)
                .customerAccountId(customer.getId())
                .status(UserStatus.ACTIVE)
                .build());

        KeyPair kpSub = generateEcKeyPair();
        String pubKeySub = Base64.getEncoder().encodeToString(kpSub.getPublic().getEncoded());

        var regResp = deviceService.registerDevice(subuser.getId(),
                com.elecsarthi.dto.request.DeviceRegisterRequest.builder()
                        .installationId("inst-subuser-001")
                        .publicKey(pubKeySub)
                        .platform(DevicePlatform.ANDROID)
                        .deviceName("Redmi Note 12")
                        .build(),
                "127.0.0.1");

        String sigSub = signData(kpSub.getPrivate(), regResp.getChallenge().getBytes(StandardCharsets.UTF_8));
        deviceService.verifyDevice(subuser.getId(),
                com.elecsarthi.dto.request.DeviceVerifyRequest.builder()
                        .deviceId(regResp.getDeviceId())
                        .challengeSignature(sigSub)
                        .build(),
                "127.0.0.1");

        // Subuser cannot revoke any device
        ApiException subuserRevokeEx = assertThrows(ApiException.class, () ->
                deviceService.revokeDevice(regResp.getDeviceId(), subuser.getId(), Role.SUBUSER, "127.0.0.1"));
        assertEquals(403, subuserRevokeEx.getStatus().value());

        // Primary Customer CAN revoke subordinate Subuser's device
        assertDoesNotThrow(() ->
                deviceService.revokeDevice(regResp.getDeviceId(), primaryCust.getId(), Role.CUSTOMER, "127.0.0.1"));

        UserDevice dev = deviceRepository.findById(regResp.getDeviceId()).orElseThrow();
        assertEquals(DeviceStatus.REVOKED, dev.getStatus());
    }

    @Test
    @DisplayName("Point 3: Complete 5-role device revocation permission matrix via HTTP MockMvc without privilege escalation")
    void testDeviceRevocationPermissionMatrixViaHttp() throws Exception {
        AppUser owner = userRepository.save(AppUser.builder()
                .username("mat_owner_" + UUID.randomUUID())
                .fullName("Matrix Owner")
                .passwordHash(passwordEncoder.encode("Pass123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());
        AppUser subowner = userRepository.save(AppUser.builder()
                .username("mat_subowner_" + UUID.randomUUID())
                .fullName("Matrix Subowner")
                .passwordHash(passwordEncoder.encode("Pass123!"))
                .role(Role.SUBOWNER)
                .status(UserStatus.ACTIVE)
                .build());
        AppUser agent1 = userRepository.save(AppUser.builder()
                .username("mat_agent1_" + UUID.randomUUID())
                .fullName("Matrix Agent 1")
                .passwordHash(passwordEncoder.encode("Pass123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build());
        AppUser agent2 = userRepository.save(AppUser.builder()
                .username("mat_agent2_" + UUID.randomUUID())
                .fullName("Matrix Agent 2")
                .passwordHash(passwordEncoder.encode("Pass123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount custAcc1 = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Matrix Cust 1")
                .contactPhone("+9197" + String.format("%08d", Math.abs(new java.util.Random().nextInt(100000000))))
                .owningAgentUserId(agent1.getId())
                .createdByUserId(agent1.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());
        CustomerAccount custAcc2 = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Matrix Cust 2")
                .contactPhone("+9196" + String.format("%08d", Math.abs(new java.util.Random().nextInt(100000000))))
                .owningAgentUserId(agent2.getId())
                .createdByUserId(agent2.getId())
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.ACTIVATED)
                .build());

        AppUser custUser1 = userRepository.save(AppUser.builder()
                .username("mat_cust1_" + UUID.randomUUID())
                .fullName("Matrix Cust User 1")
                .passwordHash(passwordEncoder.encode("Pass123!"))
                .role(Role.CUSTOMER)
                .customerAccountId(custAcc1.getId())
                .status(UserStatus.ACTIVE)
                .build());
        AppUser subuser1 = userRepository.save(AppUser.builder()
                .username("mat_sub1_" + UUID.randomUUID())
                .fullName("Matrix Subuser 1")
                .passwordHash(passwordEncoder.encode("Pass123!"))
                .role(Role.SUBUSER)
                .customerAccountId(custAcc1.getId())
                .status(UserStatus.ACTIVE)
                .build());
        AppUser custUser2 = userRepository.save(AppUser.builder()
                .username("mat_cust2_" + UUID.randomUUID())
                .fullName("Matrix Cust User 2")
                .passwordHash(passwordEncoder.encode("Pass123!"))
                .role(Role.CUSTOMER)
                .customerAccountId(custAcc2.getId())
                .status(UserStatus.ACTIVE)
                .build());
        AppUser subuser2 = userRepository.save(AppUser.builder()
                .username("mat_sub2_" + UUID.randomUUID())
                .fullName("Matrix Subuser 2")
                .passwordHash(passwordEncoder.encode("Pass123!"))
                .role(Role.SUBUSER)
                .customerAccountId(custAcc2.getId())
                .status(UserStatus.ACTIVE)
                .build());

        UserDevice agent1Dev = createActiveDevice(agent1, null, "inst-mat-ag1");
        UserDevice cust1Dev = createActiveDevice(custUser1, custAcc1.getId(), "inst-mat-c1");
        UserDevice sub1Dev = createActiveDevice(subuser1, custAcc1.getId(), "inst-mat-s1");
        UserDevice cust2Dev = createActiveDevice(custUser2, custAcc2.getId(), "inst-mat-c2");
        UserDevice sub2Dev = createActiveDevice(subuser2, custAcc2.getId(), "inst-mat-s2");

        String ownerToken = createActiveSessionAndToken(owner, ClientType.WEB, null);
        String subownerToken = createActiveSessionAndToken(subowner, ClientType.WEB, null);
        String agent1Token = createActiveSessionAndToken(agent1, ClientType.MOBILE, agent1Dev.getId());
        String cust1Token = createActiveSessionAndToken(custUser1, ClientType.MOBILE, cust1Dev.getId());
        String sub1Token = createActiveSessionAndToken(subuser1, ClientType.MOBILE, sub1Dev.getId());

        // 1. SUBUSER cannot revoke any device (not even their own, even with spoofed query/body params) -> 403
        mockMvc.perform(post("/api/v1/devices/" + sub1Dev.getId() + "/revoke")
                        .param("callerUserId", owner.getId().toString())
                        .param("role", "OWNER")
                        .header("Authorization", "Bearer " + sub1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"callerUserId\":" + owner.getId() + ",\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());

        // 2. CUSTOMER cannot revoke OWN primary device (phone replacement bypass check!) -> 403
        mockMvc.perform(post("/api/v1/devices/" + cust1Dev.getId() + "/revoke")
                        .param("callerUserId", owner.getId().toString())
                        .header("Authorization", "Bearer " + cust1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());

        // 3. CUSTOMER cannot revoke another organization's SUBUSER device -> 403
        mockMvc.perform(post("/api/v1/devices/" + sub2Dev.getId() + "/revoke")
                        .header("Authorization", "Bearer " + cust1Token))
                .andExpect(status().isForbidden());

        // 4. CUSTOMER CAN revoke subordinate SUBUSER device in own organization -> 200
        mockMvc.perform(post("/api/v1/devices/" + sub1Dev.getId() + "/revoke")
                        .header("Authorization", "Bearer " + cust1Token))
                .andExpect(status().isOk());
        assertEquals(DeviceStatus.REVOKED, deviceRepository.findById(sub1Dev.getId()).orElseThrow().getStatus());

        // 5. AGENT cannot revoke OWN device -> 403
        mockMvc.perform(post("/api/v1/devices/" + agent1Dev.getId() + "/revoke")
                        .header("Authorization", "Bearer " + agent1Token))
                .andExpect(status().isForbidden());

        // 6. AGENT cannot revoke unassigned customer's device -> 403
        mockMvc.perform(post("/api/v1/devices/" + cust2Dev.getId() + "/revoke")
                        .header("Authorization", "Bearer " + agent1Token))
                .andExpect(status().isForbidden());

        // 7. AGENT CAN revoke assigned customer's device -> 200
        mockMvc.perform(post("/api/v1/devices/" + cust1Dev.getId() + "/revoke")
                        .header("Authorization", "Bearer " + agent1Token))
                .andExpect(status().isOk());
        assertEquals(DeviceStatus.REVOKED, deviceRepository.findById(cust1Dev.getId()).orElseThrow().getStatus());

        // 8. SUBOWNER CAN revoke any device (e.g., agent1Dev or cust2Dev) -> 200
        mockMvc.perform(post("/api/v1/devices/" + agent1Dev.getId() + "/revoke")
                        .header("Authorization", "Bearer " + subownerToken))
                .andExpect(status().isOk());
        assertEquals(DeviceStatus.REVOKED, deviceRepository.findById(agent1Dev.getId()).orElseThrow().getStatus());

        // 9. OWNER CAN revoke any device (e.g., cust2Dev and sub2Dev) -> 200
        mockMvc.perform(post("/api/v1/devices/" + cust2Dev.getId() + "/revoke")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/devices/" + sub2Dev.getId() + "/revoke")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        assertEquals(DeviceStatus.REVOKED, deviceRepository.findById(cust2Dev.getId()).orElseThrow().getStatus());
        assertEquals(DeviceStatus.REVOKED, deviceRepository.findById(sub2Dev.getId()).orElseThrow().getStatus());
    }
}
