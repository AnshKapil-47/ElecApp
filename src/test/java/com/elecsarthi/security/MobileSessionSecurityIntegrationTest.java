package com.elecsarthi.security;

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
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mobile sessions are bound to their device: copied access and refresh tokens, mandatory refresh proof, replay protection. */
public class MobileSessionSecurityIntegrationTest extends SecurityIntegrationTestSupport {

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
}
