package com.elecsarthi.security;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.repository.*;
import com.elecsarthi.security.jwt.JwtTokenProvider;
import com.elecsarthi.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Single-use device registration tokens: durability, concurrency and fail-closed behaviour. */
public class RegistrationTokenSecurityIntegrationTest extends SecurityIntegrationTestSupport {

    private MockMvc mockMvc;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AppProperties appProperties;

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

    @Test
    @DisplayName("Registration token JTI check fails closed on blank input and on DB outage")
    void testRegistrationJtiFailsClosedOnBlankInputAndDatabaseOutage() {
        // Blank input, and a fresh instance (uncached JTI) with a failing database, must both be rejected
        assertTrue(tokenProvider.isRegistrationJtiInvalid(null));
        assertTrue(tokenProvider.isRegistrationJtiInvalid(""));
        assertTrue(tokenProvider.isRegistrationJtiInvalid("   "));

        JdbcTemplate failingJtiJdbc = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                throw new org.springframework.dao.DataAccessResourceFailureException("Simulated DB outage");
            }

            @Override
            public int update(String sql, Object... args) {
                throw new org.springframework.dao.DataAccessResourceFailureException("Simulated DB outage");
            }
        };
        JwtTokenProvider freshFailingTokenProvider = new JwtTokenProvider(appProperties, failingJtiJdbc);
        String validRandomJti = UUID.randomUUID().toString();
        assertTrue(freshFailingTokenProvider.isRegistrationJtiInvalid(validRandomJti),
                "Uncached JTI check MUST fail closed (return true) on DB failure");
        assertThrows(IllegalStateException.class, () -> freshFailingTokenProvider.invalidateRegistrationJti(validRandomJti),
                "Persisting JTI invalidation must throw on DB outage rather than silently swallowing");
    }
}
