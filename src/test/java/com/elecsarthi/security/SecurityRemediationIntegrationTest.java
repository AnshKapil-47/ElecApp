package com.elecsarthi.security;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.CustomerActivateRequest;
import com.elecsarthi.dto.request.DirectEntitlementRequest;
import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.dto.request.OfflineWritebackRequest;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.repository.*;
import com.elecsarthi.security.config.SecurityConfig;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.security.jwt.JwtTokenProvider;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.AuthService;
import com.elecsarthi.service.CustomerService;
import com.elecsarthi.service.DeviceService;
import com.elecsarthi.service.EntitlementService;
import com.elecsarthi.service.OfflineLeaseService;
import com.elecsarthi.service.SaleService;
import com.elecsarthi.service.SyncService;
import com.elecsarthi.service.impl.RetentionScheduledService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
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
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
public class SecurityRemediationIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private SaleService saleService;

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
    private JwtTokenProvider tokenProvider;

    @Autowired
    private RetentionScheduledService retentionScheduledService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private UserDeviceRepository deviceRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private AuditService auditService;

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
    @DisplayName("Finding 5: Activation Lockout & Autonomous Audit - Failed activation increments counter and logs DENIED in autonomous transaction")
    void testActivationLockoutAndAuditPersistence() {
        Instant now = Instant.now();
        String pepper = appProperties.getSecurity().getActivationPepper();
        String validCode = "VALID1";
        String validHash = CryptoUtils.hmacSha256Hex(validCode, pepper);

        AppUser creator1 = userRepository.save(AppUser.builder()
                .username("creator_user_1")
                .fullName("Creator User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Lockout Test Customer")
                .contactPhone("+919876543210")
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.PENDING)
                .activationCodeHash(validHash)
                .activationCodeExpiresAt(now.plus(72, ChronoUnit.HOURS))
                .activationAttemptCount((short) 0)
                .createdByUserId(creator1.getId())
                .build());

        // Perform 5 failed activation attempts with bad code
        for (int i = 1; i <= 5; i++) {
            CustomerActivateRequest badReq = CustomerActivateRequest.builder()
                    .contactPhone("+919876543210")
                    .activationCode("WRONG" + i)
                    .build();

            int finalI = i;
            ApiException ex = assertThrows(ApiException.class, () -> authService.activateCustomer(badReq, "127.0.0.1"));

            // Verify attempt counter is persisted in DB immediately (not rolled back by exception!)
            CustomerAccount updated = customerAccountRepository.findById(customer.getId()).orElseThrow();
            assertEquals(finalI, updated.getActivationAttemptCount(), "Attempt counter must increment and commit in autonomous transaction");
        }

        // 6th attempt: Must be locked out with ACTIVATION_MAX_ATTEMPTS_EXCEEDED
        CustomerActivateRequest attempt6 = CustomerActivateRequest.builder()
                .contactPhone("+919876543210")
                .activationCode(validCode) // Even with valid code, customer is now locked out!
                .build();

        ApiException lockoutEx = assertThrows(ApiException.class, () -> authService.activateCustomer(attempt6, "127.0.0.1"));
        assertEquals("ACTIVATION_MAX_ATTEMPTS_EXCEEDED", lockoutEx.getErrorCode().name());

        // Verify DENIED audit logs survived in audit_log
        Integer deniedAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'CUSTOMER_ACTIVATE' AND outcome = 'DENIED'",
                Integer.class
        );
        assertNotNull(deniedAudits);
        assertTrue(deniedAudits >= 5, "DENIED audit logs must survive in database across thrown exceptions");
    }

    @Test
    @DisplayName("Finding 6: Scheduled Retention - Purges MASTER dataset partitions past delete_after and terminates expired sessions")
    void testScheduledRetentionExecution() {
        Instant now = Instant.now();

        AppUser creatorRet = userRepository.save(AppUser.builder()
                .username("creator_user_retention")
                .fullName("Creator User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        // 1. Create expired MASTER dataset past delete_after
        Dataset masterDs = datasetRepository.save(Dataset.builder()
                .name("Expired Master Roll")
                .originalFileName("master_roll.xlsx")
                .fileSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(now.minus(1, ChronoUnit.DAYS))
                .importedByUserId(creatorRet.getId())
                .createdAt(now.minus(61, ChronoUnit.DAYS))
                .build());

        jdbcTemplate.execute("SELECT create_dataset_partitions(" + masterDs.getId() + ");");

        // Verify partition table exists before purge
        Boolean partitionExistsBefore = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'voter_d" + masterDs.getId() + "')",
                Boolean.class
        );
        assertTrue(Boolean.TRUE.equals(partitionExistsBefore));

        // 2. Create expired user session
        AppUser user = userRepository.save(AppUser.builder()
                .username("retention_user")
                .fullName("Retention User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        UserSession expiredSession = sessionRepository.save(UserSession.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .userRole(user.getRole())
                .clientType(ClientType.WEB)
                .status(SessionStatus.ACTIVE)
                .absoluteExpiresAt(now.minus(1, ChronoUnit.DAYS))
                .build());

        // 3. Trigger retention jobs
        retentionScheduledService.purgeExpiredDatasets();
        retentionScheduledService.expireSessionsAndLeases();

        // 4. Verify partition was dropped and dataset marked DELETING
        Boolean partitionExistsAfter = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'voter_d" + masterDs.getId() + "')",
                Boolean.class
        );
        assertFalse(Boolean.TRUE.equals(partitionExistsAfter), "Partition table voter_d<id> must be detached and dropped");

        Dataset purgedDs = datasetRepository.findById(masterDs.getId()).orElseThrow();
        assertEquals(DatasetStatus.DELETING, purgedDs.getStatus());

        // 5. Verify user session transitioned to TERMINATED
        UserSession updatedSession = sessionRepository.findById(expiredSession.getId()).orElseThrow();
        assertEquals(SessionStatus.TERMINATED, updatedSession.getStatus());
        assertEquals("ABSOLUTE_EXPIRED", updatedSession.getTerminationReason());
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
    @DisplayName("Finding 12: Audit JSON Resilience - Special characters in void reason do not break JSON serialization")
    void testAuditJsonSerializationResilience() {
        AppUser creatorAud = userRepository.save(AppUser.builder()
                .username("creator_user_audit")
                .fullName("Creator User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Audit Test Customer")
                .contactPhone("+919112233446")
                .status(UserStatus.ACTIVE)
                .createdByUserId(creatorAud.getId())
                .build());

        // Log an audit with tricky characters (quotes, backslashes, newlines)
        String maliciousReason = "customer said: \"cancel my subscription\" and \n dropped connection \\ null";
        assertDoesNotThrow(() ->
                auditService.logDetails(creatorAud.getId(), customer.getId(), null, null,
                        "TEST_REASON", AuditOutcome.SUCCESS, "test_entity", "1",
                        Map.of("reason", maliciousReason), "127.0.0.1")
        );

        // Verify row was committed into audit_log
        String detailsInDb = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_log WHERE action_type = 'TEST_REASON' ORDER BY id DESC LIMIT 1",
                String.class
        );
        assertNotNull(detailsInDb);
        assertTrue(detailsInDb.contains("cancel my subscription"));
    }

    @Test
    @DisplayName("Finding 19: Production Secret Validation - Rejects default development keys and placeholder values in prod")
    void testProductionSecretValidation() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        AppProperties prodProps = new AppProperties();
        prodProps.getJwt().setSecret("replace_with_a_real_production_secret_at_least_64_characters_long_now");
        prodProps.getSecurity().setActivationPepper("valid-pepper-12345");
        prodProps.getSecurity().setEd25519PrivateKeyHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60e0a5ced346296312484a3b7d13e71789c9e83ec54c9da459048a1cb364718428");
        prodProps.getSecurity().setVoterMobileAesKeyHex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4");

        SecurityConfig secConfig = new SecurityConfig(null, null, prodEnv, prodProps);

        // Must throw IllegalStateException because secret contains placeholder "replace"
        IllegalStateException ex = assertThrows(IllegalStateException.class, secConfig::validateProductionSecurity);
        assertTrue(ex.getMessage().contains("placeholder") || ex.getMessage().contains("JWT secret"));
    }

    @Test
    @DisplayName("Verification: Registration token JTI single-use state is durable across restarts and persistent")
    void testRegistrationTokenSingleUseDurabilityAcrossRestarts() throws Exception {
        AppUser regOwner = userRepository.save(AppUser.builder()
                .username("reg_owner_user")
                .fullName("Reg Owner User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount regCust = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Reg Dur Cust")
                .contactPhone("+919999900010")
                .status(UserStatus.ACTIVE)
                .createdByUserId(regOwner.getId())
                .build());

        AppUser regUser = userRepository.save(AppUser.builder()
                .username("reg_dur_user")
                .fullName("Reg Dur User")
                .customerAccountId(regCust.getId())
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());

        String regToken = tokenProvider.generateRegistrationToken(regUser.getId(), regUser.getUsername(), regUser.getRole(), null);
        io.jsonwebtoken.Claims claims = tokenProvider.parseClaims(regToken);
        String jti = claims.get("jti", String.class);
        assertNotNull(jti);
        assertFalse(tokenProvider.isRegistrationJtiInvalid(jti));

        // Invalidate JTI
        tokenProvider.invalidateRegistrationJti(jti);
        assertTrue(tokenProvider.isRegistrationJtiInvalid(jti));

        // Verify that audit_log has the invalidated JTI record
        Boolean inDb = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM audit_log WHERE action_type = 'REGISTRATION_JTI_INVALIDATED' AND details->>'jti' = ?)",
                Boolean.class, jti
        );
        assertTrue(Boolean.TRUE.equals(inDb));

        // Replaying this token against /api/v1/devices/verify must return 403 Forbidden
        mockMvc.perform(post("/api/v1/devices/verify")
                        .header("Authorization", "Bearer " + regToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + UUID.randomUUID() + "\",\"challengeSignature\":\"dummy\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Verification: Copied access token without signature rejected on voter endpoints")
    void testCopiedAccessTokenRejectedWithoutDeviceSignatureOnVoterEndpoints() throws Exception {
        AppUser mobOwner = userRepository.save(AppUser.builder()
                .username("mob_owner_user")
                .fullName("Mob Owner User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount mobileCustAcc = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Mobile Cust Acc")
                .contactPhone("+919999900020")
                .status(UserStatus.ACTIVE)
                .createdByUserId(mobOwner.getId())
                .build());

        AppUser mobileCust = userRepository.save(AppUser.builder()
                .username("mobile_cust_sig")
                .fullName("Mobile Customer")
                .customerAccountId(mobileCustAcc.getId())
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());

        UserDevice activeDev = deviceRepository.save(UserDevice.builder()
                .userId(mobileCust.getId())
                .customerAccountId(mobileCustAcc.getId())
                .userRole(Role.CUSTOMER)
                .platform(DevicePlatform.ANDROID)
                .deviceName("Test Android")
                .installationId("inst-sig-1")
                .publicKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7Z8")
                .status(DeviceStatus.ACTIVE)
                .activatedAt(Instant.now())
                .lastUsedAt(Instant.now())
                .lastRequestCounter(0L)
                .createdAt(Instant.now())
                .build());

        UUID sessId = UUID.randomUUID();
        sessionRepository.save(UserSession.builder()
                .id(sessId)
                .userId(mobileCust.getId())
                .userRole(Role.CUSTOMER)
                .deviceId(activeDev.getId())
                .clientType(ClientType.MOBILE)
                .status(SessionStatus.ACTIVE)
                .issuedAt(Instant.now())
                .lastSeenAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .build());

        String mobileAccessToken = tokenProvider.generateAccessToken(sessId, mobileCust.getId(), mobileCust.getUsername(), Role.CUSTOMER, null);

        // Making a request to /api/v1/voters/search with a mobile access token but without signature headers
        mockMvc.perform(post("/api/v1/voters/search")
                        .header("Authorization", "Bearer " + mobileAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":0,\"size\":10}"))
                .andExpect(status().isUnauthorized());
    }

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
    @DisplayName("Verification: Retention job safety checks preserve datasets with active entitlements")
    void testRetentionJobPreservesDatasetWithActiveEntitlement() {
        Instant now = Instant.now();

        AppUser owner = userRepository.save(AppUser.builder()
                .username("ret_owner")
                .fullName("Ret Owner")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount custAcc = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Ret Test Cust")
                .contactPhone("+919999900002")
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .build());

        // Create an expired dataset with delete_after in the past
        Dataset expDs = datasetRepository.save(Dataset.builder()
                .name("Expired But Active Entitlement DS")
                .originalFileName("exp.csv")
                .fileSha256("11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff")
                .storageTier(DatasetTier.LONG_TERM)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(now.minus(1, ChronoUnit.DAYS))
                .totalRows(10)
                .importedRows(10)
                .failedRows(0)
                .importedByUserId(owner.getId())
                .createdAt(now.minus(60, ChronoUnit.DAYS))
                .build());

        jdbcTemplate.execute("SELECT create_dataset_partitions(" + expDs.getId() + ");");

        ElectionType et = electionTypeRepository.findAll().get(0);

        // Add an ACTIVE entitlement whose validUntil is in the future
        entitlementRepository.save(Entitlement.builder()
                .customerAccountId(custAcc.getId())
                .electionTypeId(et.getId())
                .datasetId(expDs.getId())
                .storageTier(DatasetTier.LONG_TERM)
                .criteria("{\"ac_no\": 50}")
                .criteriaHash("hash-exp-1")
                .scopeLabel("AC 50")
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(10, ChronoUnit.DAYS))
                .validUntil(now.plus(10, ChronoUnit.DAYS))
                .mobileAccess(false)
                .createdByUserId(owner.getId())
                .createdAt(now.minus(10, ChronoUnit.DAYS))
                .build());

        // Run retention job
        retentionScheduledService.purgeExpiredDatasets();

        // Dataset MUST STILL EXIST and remain in IMPORTED status
        Dataset preservedDs = datasetRepository.findById(expDs.getId()).orElseThrow();
        assertEquals(DatasetStatus.IMPORTED, preservedDs.getStatus());
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

    @Test
    @DisplayName("Verification: Copied refresh token from different device or without matching device ID is rejected")
    void testCopiedRefreshTokenRejectedFromDifferentDevice() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        AppUser owner = userRepository.save(AppUser.builder()
                .username("ref_owner_" + System.currentTimeMillis())
                .fullName("Ref Owner")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount custAcc = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Ref Customer")
                .contactPhone("+919999000099")
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .build());

        AppUser customerUser = userRepository.save(AppUser.builder()
                .username("ref_user_" + System.currentTimeMillis())
                .fullName("Ref User")
                .customerAccountId(custAcc.getId())
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());

        KeyPair kpLegit = generateEcKeyPair();
        String pkLegitBase64 = Base64.getEncoder().encodeToString(kpLegit.getPublic().getEncoded());

        UserDevice legitimateDevice = deviceRepository.save(UserDevice.builder()
                .userId(customerUser.getId())
                .userRole(Role.CUSTOMER)
                .customerAccountId(custAcc.getId())
                .installationId("inst_legit")
                .platform(DevicePlatform.ANDROID)
                .publicKey(pkLegitBase64)
                .deviceName("Legitimate Device")
                .status(DeviceStatus.ACTIVE)
                .challengeHash(CryptoUtils.sha256Hex("chal-legit"))
                .challengePurpose("DEVICE_REGISTRATION")
                .challengeExpiresAt(now.plus(1, ChronoUnit.DAYS))
                .lastRequestCounter(0L)
                .createdAt(now)
                .build());

        CustomerAccount attackerCust = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Attacker Customer")
                .contactPhone("+919999000089")
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .build());

        AppUser attackerUser = userRepository.save(AppUser.builder()
                .username("ref_attacker_" + System.currentTimeMillis())
                .fullName("Ref Attacker")
                .customerAccountId(attackerCust.getId())
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());

        KeyPair kpAttacker = generateEcKeyPair();
        String pkAttackerBase64 = Base64.getEncoder().encodeToString(kpAttacker.getPublic().getEncoded());

        UserDevice attackerDevice = deviceRepository.save(UserDevice.builder()
                .userId(attackerUser.getId())
                .userRole(Role.CUSTOMER)
                .customerAccountId(attackerCust.getId())
                .installationId("inst_attacker")
                .platform(DevicePlatform.ANDROID)
                .publicKey(pkAttackerBase64)
                .deviceName("Attacker Device")
                .status(DeviceStatus.ACTIVE)
                .challengeHash(CryptoUtils.sha256Hex("chal-attacker"))
                .challengePurpose("DEVICE_REGISTRATION")
                .challengeExpiresAt(now.plus(1, ChronoUnit.DAYS))
                .lastRequestCounter(0L)
                .createdAt(now)
                .build());

        // Create a mobile session bound to legitimateDevice
        UUID sessionId = UUID.randomUUID();
        String wireRefreshToken = sessionId + ".1.secret1234567890123456789012345678";
        String tokenHash = CryptoUtils.sha256Hex(wireRefreshToken);

        sessionRepository.save(UserSession.builder()
                .id(sessionId)
                .userId(customerUser.getId())
                .userRole(Role.CUSTOMER)
                .clientType(ClientType.MOBILE)
                .deviceId(legitimateDevice.getId())
                .status(SessionStatus.ACTIVE)
                .refreshTokenHash(tokenHash)
                .refreshTokenGeneration(1)
                .refreshTokenExpiresAt(now.plus(30, ChronoUnit.DAYS))
                .absoluteExpiresAt(now.plus(30, ChronoUnit.DAYS))
                .issuedAt(now)
                .lastSeenAt(now)
                .build());

        com.elecsarthi.dto.request.RefreshTokenRequest refReq =
                new com.elecsarthi.dto.request.RefreshTokenRequest(wireRefreshToken);

        // 1. Attacker attempts to refresh without headers -> Rejected with 401 UNAUTHORIZED
        ApiException missingHeadersEx = assertThrows(ApiException.class, () ->
                authService.refreshToken(refReq, "10.0.0.99"));
        assertEquals("UNAUTHORIZED", missingHeadersEx.getErrorCode().name());

        // 2. Attacker attempts to refresh with attacker device ID and valid attacker signature -> Rejected with 401 REFRESH_DEVICE_MISMATCH
        long ts = now.toEpochMilli();
        String nonceAttacker = UUID.randomUUID().toString();
        long counterAttacker = 1L;
        String sigAttacker;
        try {
            sigAttacker = signRefresh(kpAttacker.getPrivate(), wireRefreshToken, ts, nonceAttacker, counterAttacker);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ApiException mismatchEx = assertThrows(ApiException.class, () ->
                authService.refreshToken(refReq, "10.0.0.99", attackerDevice.getId().toString(),
                        sigAttacker, String.valueOf(ts), nonceAttacker, String.valueOf(counterAttacker), null));
        assertEquals("UNAUTHORIZED", mismatchEx.getErrorCode().name());

        // 3. Legitimate device refresh with all 5 valid proof headers -> Succeeds!
        String nonceLegit = UUID.randomUUID().toString();
        long counterLegit = 1L;
        String sigLegit;
        try {
            sigLegit = signRefresh(kpLegit.getPrivate(), wireRefreshToken, ts, nonceLegit, counterLegit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        com.elecsarthi.dto.response.TokenRefreshResponse refreshResp = authService.refreshToken(refReq, "10.0.0.1", legitimateDevice.getId().toString(),
                sigLegit, String.valueOf(ts), nonceLegit, String.valueOf(counterLegit), null);
        assertNotNull(refreshResp.getAccessToken());
        assertNotNull(refreshResp.getRefreshToken());
        assertNotEquals(wireRefreshToken, refreshResp.getRefreshToken());
    }

    @Test
    @DisplayName("Verification: Mandatory mobile refresh proof validation (headers, signature, window, replay, counter, revoked)")
    void testMobileRefreshMandatoryHeadersAndReplayProtection() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        AppUser owner = userRepository.save(AppUser.builder()
                .username("ref_man_owner_" + System.currentTimeMillis())
                .fullName("Ref Man Owner")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount custAcc = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Ref Man Customer")
                .contactPhone("+919999000199")
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .build());

        AppUser customerUser = userRepository.save(AppUser.builder()
                .username("ref_man_user_" + System.currentTimeMillis())
                .fullName("Ref Man User")
                .customerAccountId(custAcc.getId())
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());

        KeyPair kp = generateEcKeyPair();
        String pkBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        UserDevice device = deviceRepository.save(UserDevice.builder()
                .userId(customerUser.getId())
                .userRole(Role.CUSTOMER)
                .customerAccountId(custAcc.getId())
                .installationId("inst_man_dev")
                .platform(DevicePlatform.ANDROID)
                .publicKey(pkBase64)
                .deviceName("Man Device")
                .status(DeviceStatus.ACTIVE)
                .challengeHash(CryptoUtils.sha256Hex("chal-man"))
                .challengePurpose("DEVICE_REGISTRATION")
                .challengeExpiresAt(now.plus(1, ChronoUnit.DAYS))
                .lastRequestCounter(0L)
                .createdAt(now)
                .build());

        UUID sessionId = UUID.randomUUID();
        String wireToken = sessionId + ".1.secretman1234567890123456789012345";
        sessionRepository.save(UserSession.builder()
                .id(sessionId)
                .userId(customerUser.getId())
                .userRole(Role.CUSTOMER)
                .clientType(ClientType.MOBILE)
                .deviceId(device.getId())
                .status(SessionStatus.ACTIVE)
                .refreshTokenHash(CryptoUtils.sha256Hex(wireToken))
                .refreshTokenGeneration(1)
                .refreshTokenExpiresAt(now.plus(30, ChronoUnit.DAYS))
                .absoluteExpiresAt(now.plus(30, ChronoUnit.DAYS))
                .issuedAt(now)
                .lastSeenAt(now)
                .build());

        com.elecsarthi.dto.request.RefreshTokenRequest req = new com.elecsarthi.dto.request.RefreshTokenRequest(wireToken);
        long ts = now.toEpochMilli();
        String nonce1 = UUID.randomUUID().toString();
        long counter1 = 1L;
        String validSig = signRefresh(kp.getPrivate(), wireToken, ts, nonce1, counter1);

        // A. Missing X-Device-Id -> 401 UNAUTHORIZED
        ApiException ex1 = assertThrows(ApiException.class, () ->
                authService.refreshToken(req, "127.0.0.1", null, validSig, String.valueOf(ts), nonce1, String.valueOf(counter1), null));
        assertEquals("UNAUTHORIZED", ex1.getErrorCode().name());

        // B. Missing X-Device-Signature -> 401 INVALID_DEVICE_SIGNATURE
        ApiException ex2 = assertThrows(ApiException.class, () ->
                authService.refreshToken(req, "127.0.0.1", device.getId().toString(), null, String.valueOf(ts), nonce1, String.valueOf(counter1), null));
        assertEquals("INVALID_DEVICE_SIGNATURE", ex2.getErrorCode().name());

        // C. Missing X-Device-Timestamp -> 401 INVALID_DEVICE_SIGNATURE
        ApiException ex3 = assertThrows(ApiException.class, () ->
                authService.refreshToken(req, "127.0.0.1", device.getId().toString(), validSig, null, nonce1, String.valueOf(counter1), null));
        assertEquals("INVALID_DEVICE_SIGNATURE", ex3.getErrorCode().name());

        // D. Missing X-Device-Nonce -> 401 INVALID_DEVICE_SIGNATURE
        ApiException ex4 = assertThrows(ApiException.class, () ->
                authService.refreshToken(req, "127.0.0.1", device.getId().toString(), validSig, String.valueOf(ts), null, String.valueOf(counter1), null));
        assertEquals("INVALID_DEVICE_SIGNATURE", ex4.getErrorCode().name());

        // E. Missing X-Device-Counter -> 401 INVALID_DEVICE_SIGNATURE
        ApiException ex5 = assertThrows(ApiException.class, () ->
                authService.refreshToken(req, "127.0.0.1", device.getId().toString(), validSig, String.valueOf(ts), nonce1, null, null));
        assertEquals("INVALID_DEVICE_SIGNATURE", ex5.getErrorCode().name());

        // F. Malformed X-Device-Id -> 401 UNAUTHORIZED
        ApiException ex6 = assertThrows(ApiException.class, () ->
                authService.refreshToken(req, "127.0.0.1", "not-a-uuid", validSig, String.valueOf(ts), nonce1, String.valueOf(counter1), null));
        assertEquals("UNAUTHORIZED", ex6.getErrorCode().name());

        // G. Invalid signature (signed with different key) -> 401 INVALID_DEVICE_SIGNATURE
        KeyPair wrongKp = generateEcKeyPair();
        String wrongSig = signRefresh(wrongKp.getPrivate(), wireToken, ts, nonce1, counter1);
        ApiException ex7 = assertThrows(ApiException.class, () ->
                authService.refreshToken(req, "127.0.0.1", device.getId().toString(), wrongSig, String.valueOf(ts), nonce1, String.valueOf(counter1), null));
        assertEquals("INVALID_DEVICE_SIGNATURE", ex7.getErrorCode().name());

        // H. Expired timestamp (> window, e.g. 1 hour ago) -> 400 BAD_REQUEST
        long expiredTs = ts - (60L * 60L * 1000L);
        String expiredSig = signRefresh(kp.getPrivate(), wireToken, expiredTs, nonce1, counter1);
        ApiException ex8 = assertThrows(ApiException.class, () ->
                authService.refreshToken(req, "127.0.0.1", device.getId().toString(), expiredSig, String.valueOf(expiredTs), nonce1, String.valueOf(counter1), null));
        assertEquals("BAD_REQUEST", ex8.getErrorCode().name());

        // I. Valid refresh succeeds
        com.elecsarthi.dto.response.TokenRefreshResponse rotated = authService.refreshToken(
                req, "127.0.0.1", device.getId().toString(), validSig, String.valueOf(ts), nonce1, String.valueOf(counter1), null);
        assertNotNull(rotated.getRefreshToken());

        // J. Replayed nonce with new token -> 400 REPLAYED_NONCE
        com.elecsarthi.dto.request.RefreshTokenRequest rotatedReq = new com.elecsarthi.dto.request.RefreshTokenRequest(rotated.getRefreshToken());
        long counter2 = 2L;
        String replayedNonceSig = signRefresh(kp.getPrivate(), rotated.getRefreshToken(), ts, nonce1, counter2);
        ApiException ex9 = assertThrows(ApiException.class, () ->
                authService.refreshToken(rotatedReq, "127.0.0.1", device.getId().toString(), replayedNonceSig, String.valueOf(ts), nonce1, String.valueOf(counter2), null));
        assertEquals("REPLAYED_NONCE", ex9.getErrorCode().name());

        // K. Non-increasing counter (counter 1 replayed while lastRequestCounter is 1) -> 400 BAD_REQUEST
        String nonce2 = UUID.randomUUID().toString();
        String nonIncSig = signRefresh(kp.getPrivate(), rotated.getRefreshToken(), ts, nonce2, 1L);
        ApiException ex10 = assertThrows(ApiException.class, () ->
                authService.refreshToken(rotatedReq, "127.0.0.1", device.getId().toString(), nonIncSig, String.valueOf(ts), nonce2, "1", null));
        assertEquals("BAD_REQUEST", ex10.getErrorCode().name());

        // L. Revoked device -> 401 DEVICE_REVOKED
        deviceService.revokeDevice(device.getId(), owner.getId(), "127.0.0.1");
        long counter3 = 3L;
        String nonce3 = UUID.randomUUID().toString();
        String afterRevokeSig = signRefresh(kp.getPrivate(), rotated.getRefreshToken(), ts, nonce3, counter3);
        ApiException ex11 = assertThrows(ApiException.class, () ->
                authService.refreshToken(rotatedReq, "127.0.0.1", device.getId().toString(), afterRevokeSig, String.valueOf(ts), nonce3, String.valueOf(counter3), null));
        // Session terminated or device revoked gives 401 UNAUTHORIZED / SESSION_TERMINATED / DEVICE_REVOKED
        assertEquals(401, ex11.getStatus().value());
    }

    @Test
    @DisplayName("Verification: Concurrency-safe registration token consumption serializes and allows exactly one success")
    void testConcurrentRegistrationTokenVerificationOnlyOneSucceeds() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        AppUser owner = userRepository.save(AppUser.builder()
                .username("reg_con_owner_" + System.currentTimeMillis())
                .fullName("Reg Con Owner")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount custAcc = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Reg Con Customer")
                .contactPhone("+919999000299")
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .build());

        AppUser agent = userRepository.save(AppUser.builder()
                .username("reg_con_agent_" + System.currentTimeMillis())
                .fullName("Reg Con Agent")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .build());

        // 1. Generate client keypair & initiate registration
        KeyPair kp = generateEcKeyPair();
        String pkBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        com.elecsarthi.dto.request.DeviceRegisterRequest regReq = com.elecsarthi.dto.request.DeviceRegisterRequest.builder()
                .installationId("inst_con_" + System.currentTimeMillis())
                .publicKey(pkBase64)
                .platform(DevicePlatform.ANDROID)
                .deviceName("Concurrent Test Phone")
                .build();

        com.elecsarthi.dto.response.DeviceRegisterResponse regResp = deviceService.registerDevice(agent.getId(), regReq, "127.0.0.1");
        assertNotNull(regResp.getDeviceId());
        assertNotNull(regResp.getChallenge());

        // 2. Generate a single-use registration token with unique JTI
        String regToken = tokenProvider.generateRegistrationToken(agent.getId(), agent.getUsername(), agent.getRole(), agent.getCustomerAccountId());
        io.jsonwebtoken.Claims claims = tokenProvider.parseClaims(regToken);
        String jti = claims.get("jti", String.class);
        assertNotNull(jti);

        // 3. Sign registration challenge with private key
        String challengeSignature = signData(kp.getPrivate(), regResp.getChallenge().getBytes(StandardCharsets.UTF_8));
        com.elecsarthi.dto.request.DeviceVerifyRequest verifyReq = com.elecsarthi.dto.request.DeviceVerifyRequest.builder()
                .deviceId(regResp.getDeviceId())
                .challengeSignature(challengeSignature)
                .build();

        // 4. Concurrently invoke verifyDevice with the same JTI across 2 threads
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    deviceService.verifyDevice(agent.getId(), verifyReq, "127.0.0.1", jti);
                    successCount.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getStatus().value() == 401 || "UNAUTHORIZED".equals(e.getErrorCode().name())) {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Fire both threads simultaneously
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent verification must complete within timeout");
        executor.shutdown();

        // EXACTLY ONE thread must succeed; the other must be rejected due to consumed token!
        assertEquals(1, successCount.get(), "Exactly one verification request must succeed");
        assertEquals(1, rejectedCount.get(), "Concurrent replay must be rejected with 401 UNAUTHORIZED");

        // The device must now be in ACTIVE status
        UserDevice verifiedDev = deviceRepository.findById(regResp.getDeviceId()).orElseThrow();
        assertEquals(DeviceStatus.ACTIVE, verifiedDev.getStatus());

        // Further attempt to use the registration token must immediately fail
        ApiException postEx = assertThrows(ApiException.class, () ->
                deviceService.verifyDevice(agent.getId(), verifyReq, "127.0.0.1", jti));
        assertEquals("UNAUTHORIZED", postEx.getErrorCode().name());
    }

    private KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate EC key pair", e);
        }
    }

    private String signRefresh(PrivateKey privateKey, String refreshToken, long ts, String nonce, long counter) throws Exception {
        String bodySha256 = CryptoUtils.sha256Hex(refreshToken.getBytes(StandardCharsets.UTF_8));
        String payload = String.join("\n",
                "POST",
                "/api/v1/auth/refresh",
                bodySha256,
                String.valueOf(ts),
                nonce,
                String.valueOf(counter),
                ""
        );
        return signData(privateKey, payload.getBytes(StandardCharsets.UTF_8));
    }

    private String signData(PrivateKey privateKey, byte[] data) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(data);
        return Base64.getEncoder().encodeToString(sig.sign());
    }
}
