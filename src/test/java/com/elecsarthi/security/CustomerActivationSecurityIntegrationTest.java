package com.elecsarthi.security;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.CustomerActivateRequest;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer activation: attempt lockout with autonomous audit, and no phone enumeration. */
public class CustomerActivationSecurityIntegrationTest extends SecurityIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private PasswordEncoder passwordEncoder;

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
    @DisplayName("Finding 18: Anti-phone enumeration during customer activation")
    void testAntiPhoneEnumerationDuringActivation() {
        // Unknown phone returns identical 400 Bad Request
        CustomerActivateRequest req = CustomerActivateRequest.builder()
                .contactPhone("+919999999999")
                .activationCode("XYZ1234567")
                .password("NewSecret@123")
                .build();

        ApiException ex = assertThrows(ApiException.class, () -> authService.activateCustomer(req, "127.0.0.1"));
        assertEquals(400, ex.getStatus().value());
        assertTrue(ex.getMessage().contains("Invalid activation credentials or account not eligible for activation"));
    }

    @Test
    @DisplayName("Batch 4: login for an unknown identifier costs as much as a wrong password (no timing oracle)")
    void unknownIdentifierLoginCostsAsMuchAsWrongPassword() {
        userRepository.save(AppUser.builder().username("timing_user").fullName("Timing User")
                .passwordHash(passwordEncoder.encode("Password123!")).role(Role.OWNER).status(UserStatus.ACTIVE).build());
        com.elecsarthi.dto.request.LoginRequest known = com.elecsarthi.dto.request.LoginRequest.builder()
                .identifier("timing_user").password("not-the-password").clientType(com.elecsarthi.enums.ClientType.WEB).build();
        com.elecsarthi.dto.request.LoginRequest unknown = com.elecsarthi.dto.request.LoginRequest.builder()
                .identifier("nobody_here").password("not-the-password").clientType(com.elecsarthi.enums.ClientType.WEB).build();

        // warm-up so class loading and the one-time dummy hash do not skew the comparison
        assertThrows(ApiException.class, () -> authService.login(known, "10.20.30.40"));
        assertThrows(ApiException.class, () -> authService.login(unknown, "10.20.30.40"));

        long knownNanos = timeLogin(known);
        long unknownNanos = timeLogin(unknown);
        // BCrypt cost 12 is ~100-300 ms; without the dummy check an unknown identifier answers in a few ms
        assertTrue(unknownNanos > knownNanos / 3,
                "unknown identifier answered in " + unknownNanos / 1_000_000 + " ms vs " + knownNanos / 1_000_000 + " ms for a wrong password");
    }

    private long timeLogin(com.elecsarthi.dto.request.LoginRequest request) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            assertThrows(ApiException.class, () -> authService.login(request, "10.20.30.40"));
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }

    @Test
    @DisplayName("Batch 4: an expired code is refused even when it is the right code, and the account stays PENDING")
    void expiredCodeIsRefused() {
        AppUser creator = userRepository.save(AppUser.builder()
                .username("creator_exp_1").fullName("Creator").passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER).status(UserStatus.ACTIVE).build());
        CustomerAccount customer = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Expired Code Customer").contactPhone("+919876500002")
                .status(UserStatus.ACTIVE).activationStatus(ActivationStatus.PENDING)
                .activationCodeHash(CryptoUtils.hmacSha256Hex("EXPIREDCD1", appProperties.getSecurity().getActivationPepper()))
                .activationCodeExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .activationAttemptCount((short) 0).createdByUserId(creator.getId()).build());

        ApiException ex = assertThrows(ApiException.class, () -> authService.activateCustomer(
                CustomerActivateRequest.builder().contactPhone("+919876500002").activationCode("EXPIREDCD1").password("NewSecret@123").build(), "127.0.0.1"));
        assertEquals(400, ex.getStatus().value());
        assertEquals("ACTIVATION_CODE_EXPIRED", ex.getErrorCode().name());
        assertEquals(ActivationStatus.PENDING, customerAccountRepository.findById(customer.getId()).orElseThrow().getActivationStatus());
    }

    @Test
    @DisplayName("Batch 4: a wrong code on a registered pending phone is indistinguishable from an unknown phone")
    void wrongCodeOnRegisteredPhoneLooksLikeUnknownPhone() {
        AppUser creator = userRepository.save(AppUser.builder()
                .username("creator_enum_1").fullName("Creator").passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER).status(UserStatus.ACTIVE).build());
        customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Enumeration Test Customer").contactPhone("+919876500001")
                .status(UserStatus.ACTIVE).activationStatus(ActivationStatus.PENDING)
                .activationCodeHash(CryptoUtils.hmacSha256Hex("REALCODE12", appProperties.getSecurity().getActivationPepper()))
                .activationCodeExpiresAt(Instant.now().plus(72, ChronoUnit.HOURS))
                .activationAttemptCount((short) 0).createdByUserId(creator.getId()).build());

        ApiException unknown = assertThrows(ApiException.class, () -> authService.activateCustomer(
                CustomerActivateRequest.builder().contactPhone("+919876500099").activationCode("WRONGCODE1").password("NewSecret@123").build(), "127.0.0.1"));
        ApiException known = assertThrows(ApiException.class, () -> authService.activateCustomer(
                CustomerActivateRequest.builder().contactPhone("+919876500001").activationCode("WRONGCODE1").password("NewSecret@123").build(), "127.0.0.1"));

        assertEquals(unknown.getStatus(), known.getStatus());
        assertEquals(unknown.getErrorCode(), known.getErrorCode());
        assertEquals(unknown.getMessage(), known.getMessage());
        assertFalse(known.getMessage().toLowerCase().contains("attempt"), "the attempt counter must not be revealed");
    }
}
