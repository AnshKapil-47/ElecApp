package com.elecsarthi.security;

import com.elecsarthi.dto.request.DeviceRegisterRequest;
import com.elecsarthi.dto.request.DeviceVerifyRequest;
import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.dto.response.DeviceRegisterResponse;
import com.elecsarthi.dto.response.LoginResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.security.jwt.JwtTokenProvider;
import com.elecsarthi.service.AuthService;
import com.elecsarthi.service.DeviceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
public class DeviceBootstrapIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private UserDeviceRepository deviceRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        jdbcTemplate.execute(
                "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable; " +
                "TRUNCATE TABLE booth_assignment, offline_lease, device_nonce, user_session, user_device, sync_session, entitlement, agent_sale_line, agent_sale, dataset, app_user, customer_account, audit_log, import_issue, voter CASCADE; " +
                "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable;"
        );
    }

    @Test
    @DisplayName("Finding 1: Zero-device bootstrap flow - Login returns registrationToken -> register -> verify -> re-login succeeds")
    void testZeroDeviceRegistrationBootstrapWorkflow() throws Exception {
        // 1. Create creator agent and active Customer with ZERO registered devices
        AppUser creator = userRepository.save(AppUser.builder()
                .username("creator_agent")
                .fullName("Creator Agent")
                .passwordHash(passwordEncoder.encode("Pass123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount customer = CustomerAccount.builder()
                .customerName("Bootstrap Customer")
                .contactPhone("+919988776655")
                .status(UserStatus.ACTIVE)
                .createdByUserId(creator.getId())
                .build();
        customer = customerAccountRepository.save(customer);

        AppUser user = AppUser.builder()
                .username("cust_bootstrap")
                .fullName("Bootstrap User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .customerAccountId(customer.getId())
                .build();
        user = userRepository.save(user);

        // Verify zero devices in database
        assertEquals(0, deviceRepository.count());

        String installationId = UUID.randomUUID().toString();

        // 2. Initial login attempt with zero devices
        LoginRequest loginReq = LoginRequest.builder()
                .identifier("cust_bootstrap")
                .password("Password123!")
                .clientType(ClientType.MOBILE)
                .installationId(installationId)
                .build();

        LoginResponse initialLogin = authService.login(loginReq, "127.0.0.1");

        // Assert deadlock is broken: login does not throw or fail; returns registration token
        assertNotNull(initialLogin);
        assertTrue(initialLogin.isDeviceRegistrationRequired());
        assertNotNull(initialLogin.getRegistrationToken(), "Short-lived single-use registrationToken must be provided");
        assertNull(initialLogin.getAccessToken(), "Access token must NOT be issued when device registration is required");
        assertNull(initialLogin.getRefreshToken(), "Refresh token must NOT be issued without active device");

        String regToken = initialLogin.getRegistrationToken();

        // 3. Security check: Registration token MUST be rejected when attempting to call normal voter APIs
        mockMvc.perform(get("/api/v1/voters/search?entitlementId=1")
                        .header("Authorization", "Bearer " + regToken))
                .andExpect(status().isForbidden());

        // 4. Generate device EC key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        KeyPair keyPair = kpg.generateKeyPair();
        String publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        // 5. Register device using registration token via REST API
        DeviceRegisterRequest regReq = DeviceRegisterRequest.builder()
                .installationId(installationId)
                .platform(DevicePlatform.ANDROID)
                .deviceName("Pixel 9 Pro")
                .publicKey(publicKeyB64)
                .build();

        String regRespJson = mockMvc.perform(post("/api/v1/devices/register")
                        .header("Authorization", "Bearer " + regToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regReq)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        DeviceRegisterResponse regResp = objectMapper.readValue(regRespJson, DeviceRegisterResponse.class);
        assertNotNull(regResp);
        UUID deviceId = regResp.getDeviceId();
        String challengeHex = regResp.getChallenge();
        assertNotNull(deviceId);
        assertNotNull(challengeHex);

        // Verify device created in PENDING status
        UserDevice createdDev = deviceRepository.findById(deviceId).orElseThrow();
        assertEquals(DeviceStatus.PENDING, createdDev.getStatus());

        // 6. Sign challenge with device private key
        byte[] challengeBytes = challengeHex.getBytes(StandardCharsets.UTF_8);
        String challengeSignature = signData(keyPair.getPrivate(), challengeBytes);

        // 7. Verify device via REST API
        DeviceVerifyRequest verifyReq = DeviceVerifyRequest.builder()
                .deviceId(deviceId)
                .challengeSignature(challengeSignature)
                .build();

        mockMvc.perform(post("/api/v1/devices/verify")
                        .header("Authorization", "Bearer " + regToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk());

        // Verify device transitioned to ACTIVE
        UserDevice verifiedDev = deviceRepository.findById(deviceId).orElseThrow();
        assertEquals(DeviceStatus.ACTIVE, verifiedDev.getStatus());

        // 8. Replay prevention: replaying registration token after verification must be rejected
        mockMvc.perform(post("/api/v1/devices/verify")
                        .header("Authorization", "Bearer " + regToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isForbidden());

        // 9. Now client logs in again with verified installationId -> receives full session!
        LoginResponse finalLogin = authService.login(loginReq, "127.0.0.1");

        assertNotNull(finalLogin);
        assertFalse(finalLogin.isDeviceRegistrationRequired());
        assertNotNull(finalLogin.getAccessToken());
        assertNotNull(finalLogin.getRefreshToken());
        assertEquals(Role.CUSTOMER, finalLogin.getRole());
        assertEquals(ClientType.MOBILE, finalLogin.getClientType());
    }

    private String signData(PrivateKey privateKey, byte[] data) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(data);
        return Base64.getEncoder().encodeToString(sig.sign());
    }
}
