package com.elecsarthi.service.impl;

import com.elecsarthi.dto.request.DeviceProof;
import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.CustomerActivateRequest;
import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.dto.request.RefreshTokenRequest;
import com.elecsarthi.dto.response.CustomerActivateResponse;
import com.elecsarthi.dto.response.LoginResponse;
import com.elecsarthi.dto.response.TokenRefreshResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.entity.UserSession;
import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SessionStatus;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.common.UnauthorizedException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.security.jwt.JwtTokenProvider;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.security.service.RateLimiterService;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.AuthService;
import com.elecsarthi.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String REFRESH_PATH = "/api/v1/auth/refresh";
    private static final String INVALID_ACTIVATION = "Invalid activation credentials or account not eligible for activation.";

    private final AppUserRepository userRepository;
    private final CustomerAccountRepository customerAccountRepository;
    private final UserDeviceRepository deviceRepository;
    private final UserSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AuditService auditService;
    private final AppProperties appProperties;
    private final Clock clock;
    private final RateLimiterService rateLimiterService;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final DeviceService deviceService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, String clientIp) {
        rateLimiterService.checkRateLimit("login_ip", clientIp, 30);
        rateLimiterService.checkRateLimit("login_id", request.getIdentifier(), 15);
        Instant now = clock.instant();

        AppUser user = authenticate(request, clientIp);
        requireUsableAccount(user);
        requireClientTypeAllowed(user, request.getClientType(), clientIp);

        UserDevice device = null;
        if (request.getClientType() == ClientType.MOBILE) {
            Optional<UserDevice> activeDevice = deviceRepository.findByUserIdAndStatus(user.getId(), DeviceStatus.ACTIVE);
            if (activeDevice.isEmpty()) {
                return deviceRegistrationRequired(user);
            }
            device = requireSameInstallation(user, activeDevice.get(), request.getInstallationId(), clientIp);
        }

        enforceSessionLimit(user, device, now, clientIp);
        return openSession(user, device, request.getClientType(), now, clientIp);
    }

    /**
     * Resolves the user by username or by the Customer's contact phone and verifies the password. The same
     * response is returned for an unknown user and a wrong password, and the status is only revealed after the
     * password matched. The user row is locked so concurrent logins cannot exceed the session limit.
     */
    private AppUser authenticate(LoginRequest request, String clientIp) {
        String identifier = request.getIdentifier();
        Optional<AppUser> found = userRepository.findByUsername(identifier).or(() ->
                customerAccountRepository.findByContactPhone(identifier)
                        .flatMap(customer -> userRepository.findByCustomerAccountIdAndRole(customer.getId(), Role.CUSTOMER)));
        if (found.isEmpty()) {
            // Do the same BCrypt work as for a real user, so the response time does not reveal which identifiers exist.
            passwordEncoder.matches(request.getPassword(), dummyPasswordHash());
            auditService.logDetails(null, null, null, null, "LOGIN", AuditOutcome.DENIED,
                    "app_user", identifier, AuditService.details("reason", "User not found"), clientIp);
            throw new UnauthorizedException("Invalid username or password.");
        }

        AppUser user = userRepository.findByIdForUpdate(found.get().getId()).orElse(found.get());
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            auditService.logDetails(user.getId(), user.getCustomerAccountId(), null, null, "LOGIN", AuditOutcome.DENIED,
                    "app_user", user.getUsername(), AuditService.details("reason", "Bad credentials"), clientIp);
            throw new UnauthorizedException("Invalid username or password.");
        }
        return user;
    }

    /** A hash nobody knows the password of, made once with the configured encoder (same cost as real hashes). */
    private volatile String dummyPasswordHash;

    private String dummyPasswordHash() {
        String hash = dummyPasswordHash;
        if (hash == null) {
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            dummyPasswordHash = hash;
        }
        return hash;
    }

    private void requireUsableAccount(AppUser user) {
        if (user.getStatus() == UserStatus.PENDING) {
            throw new ApiException(ErrorCode.ACCOUNT_PENDING_ACTIVATION, "Account is pending activation.", HttpStatus.FORBIDDEN);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Account is not active.", HttpStatus.FORBIDDEN);
        }
        if (user.getCustomerAccountId() != null) {
            boolean customerActive = customerAccountRepository.findById(user.getCustomerAccountId())
                    .map(customer -> customer.getStatus() == UserStatus.ACTIVE)
                    .orElse(false);
            if (!customerActive) {
                throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Customer account is suspended.", HttpStatus.FORBIDDEN);
            }
        }
    }

    /** Owner and Subowner use the web console only; every other role uses the mobile app only. */
    private void requireClientTypeAllowed(AppUser user, ClientType clientType, String clientIp) {
        boolean webRole = user.getRole() == Role.OWNER || user.getRole() == Role.SUBOWNER;
        String refusal = null;
        if (clientType == ClientType.WEB && !webRole) {
            refusal = "Mobile application users cannot log in to the web console.";
        } else if (clientType == ClientType.MOBILE && webRole) {
            refusal = "Web console administrators cannot log in to the mobile application.";
        }
        if (refusal != null) {
            auditService.logDetails(user.getId(), user.getCustomerAccountId(), null, null, "LOGIN", AuditOutcome.DENIED,
                    "app_user", user.getUsername(), AuditService.details("reason", "Client type not allowed for role",
                            "clientType", clientType), clientIp);
            throw new ForbiddenException(refusal);
        }
    }

    /** First mobile login (no ACTIVE device yet): hand out a short-lived, single-use device registration token. */
    private LoginResponse deviceRegistrationRequired(AppUser user) {
        String registrationToken = tokenProvider.generateRegistrationToken(
                user.getId(), user.getUsername(), user.getRole(), user.getCustomerAccountId());
        return LoginResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .role(user.getRole())
                .customerAccountId(user.getCustomerAccountId())
                .clientType(ClientType.MOBILE)
                .deviceRegistrationRequired(true)
                .registrationToken(registrationToken)
                .mustChangePassword(user.isMustChangePassword())
                .build();
    }

    /**
     * A password alone never moves an account to another phone: login from an installation other than the
     * ACTIVE device is denied and the Owner must revoke the old device first.
     */
    private UserDevice requireSameInstallation(AppUser user, UserDevice activeDevice, String installationId, String clientIp) {
        if (installationId == null || installationId.isBlank()) {
            throw new BadRequestException("Device installation ID is required for mobile login.");
        }
        if (!activeDevice.getInstallationId().equals(installationId)) {
            auditService.logDetails(user.getId(), user.getCustomerAccountId(), null, activeDevice.getId(),
                    "LOGIN_DEVICE_MISMATCH", AuditOutcome.DENIED, "app_user", user.getUsername(),
                    Map.of("reason", "Attempted login from new installation while active device exists",
                            "attemptedInstallationId", installationId),
                    clientIp);
            throw new ApiException(ErrorCode.DEVICE_CHANGE_NOT_ALLOWED,
                    "An active device is already registered for this account. Contact an administrator to revoke the existing device before registering a new one.",
                    HttpStatus.FORBIDDEN);
        }
        return activeDevice;
    }

    /**
     * Expired sessions are closed first. When the limit is still reached, a login proving the user's ACTIVE
     * device replaces that device's own session; any other login is rejected and never evicts a session.
     */
    private void enforceSessionLimit(AppUser user, UserDevice device, Instant now, String clientIp) {
        int maxSessions = switch (user.getRole()) {
            case OWNER, SUBOWNER -> 3;
            case AGENT, CUSTOMER, SUBUSER -> 1;
        };

        List<UserSession> liveSessions = new ArrayList<>();
        for (UserSession session : sessionRepository.findByUserIdAndStatus(user.getId(), SessionStatus.ACTIVE)) {
            if (!expireIfLapsed(session, now)) {
                liveSessions.add(session);
            }
        }
        if (liveSessions.size() < maxSessions) {
            return;
        }
        if (device == null) {
            auditSessionLimitDenied(user, null, maxSessions, clientIp);
            throw new ConflictException(ErrorCode.SESSION_LIMIT_EXCEEDED, "Concurrent session limit reached (" + maxSessions + ").");
        }
        UserSession sameDeviceSession = liveSessions.stream()
                .filter(session -> device.getId().equals(session.getDeviceId()))
                .findFirst()
                .orElseThrow(() -> {
                    auditSessionLimitDenied(user, device, maxSessions, clientIp);
                    return new ConflictException(ErrorCode.SESSION_LIMIT_EXCEEDED,
                            "Concurrent session limit reached. You are logged in on another device.");
                });
        sameDeviceSession.terminate(now, "REPLACED_SAME_DEVICE", user.getId());
        sessionRepository.save(sameDeviceSession);
    }

    /** A login refused because the account is at its concurrent-session limit is audited as DENIED (no secrets). */
    private void auditSessionLimitDenied(AppUser user, UserDevice device, int maxSessions, String clientIp) {
        auditService.logDetails(user.getId(), user.getCustomerAccountId(), null, device == null ? null : device.getId(),
                "LOGIN", AuditOutcome.DENIED, "app_user", user.getUsername(),
                AuditService.details("reason", "Concurrent session limit reached", "limit", maxSessions), clientIp);
    }

    /** Terminates the session if its absolute or idle lifetime is over; returns whether it did. */
    private boolean expireIfLapsed(UserSession session, Instant now) {
        boolean absoluteExpired = session.getAbsoluteExpiresAt().isBefore(now);
        boolean idleExpired = session.getIdleExpiresAt() != null && session.getIdleExpiresAt().isBefore(now);
        if (!absoluteExpired && !idleExpired) {
            return false;
        }
        session.terminate(now, absoluteExpired ? "ABSOLUTE_EXPIRED" : "IDLE_EXPIRED", null);
        sessionRepository.save(session);
        return true;
    }

    private LoginResponse openSession(AppUser user, UserDevice device, ClientType clientType, Instant now, String clientIp) {
        AppProperties.JwtProperties jwt = appProperties.getJwt();
        UUID sessionId = UUID.randomUUID();
        UserSession.UserSessionBuilder session = UserSession.builder()
                .id(sessionId)
                .userId(user.getId())
                .userRole(user.getRole())
                .deviceId(device != null ? device.getId() : null)
                .clientType(clientType)
                .status(SessionStatus.ACTIVE)
                .issuedAt(now)
                .lastSeenAt(now)
                .refreshTokenGeneration(1)
                .ipAddress(clientIp);

        String refreshToken = null;
        if (clientType == ClientType.MOBILE) {
            refreshToken = newRefreshToken(sessionId, 1);
            session.absoluteExpiresAt(now.plus(jwt.getSessionAbsoluteExpirationDays(), ChronoUnit.DAYS))
                    .refreshTokenHash(CryptoUtils.sha256Hex(refreshToken))
                    .refreshTokenExpiresAt(now.plus(jwt.getRefreshTokenExpirationDays(), ChronoUnit.DAYS));
        } else {
            session.absoluteExpiresAt(now.plus(jwt.getWebAbsoluteExpirationHours(), ChronoUnit.HOURS))
                    .idleExpiresAt(now.plus(jwt.getWebIdleExpirationMinutes(), ChronoUnit.MINUTES));
        }
        sessionRepository.save(session.build());

        auditService.logDetails(user.getId(), user.getCustomerAccountId(), null,
                device != null ? device.getId() : null, "LOGIN", AuditOutcome.SUCCESS,
                "user_session", sessionId.toString(), AuditService.details("clientType", clientType), clientIp);

        return LoginResponse.builder()
                .sessionId(sessionId)
                .userId(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .role(user.getRole())
                .customerAccountId(user.getCustomerAccountId())
                .clientType(clientType)
                .accessToken(accessTokenFor(sessionId, user))
                .refreshToken(refreshToken)
                .expiresAt(accessTokenExpiry(now))
                .mustChangePassword(user.isMustChangePassword())
                .build();
    }

    /**
     * Rotates a mobile refresh token. Besides the token itself, the caller must prove possession of the
     * session's ACTIVE device with a signed request. A reused (older) generation or a hash mismatch terminates
     * the session; the transaction does not roll back on ApiException so that termination is persisted.
     */
    @Override
    @Transactional(noRollbackFor = {ApiException.class})
    public TokenRefreshResponse refreshToken(RefreshTokenRequest request, String clientIp, DeviceProof proof) {
        rateLimiterService.checkRateLimit("refresh_ip", clientIp, 60);
        Instant now = clock.instant();

        // Wire format: sessionId.generation.secret
        String[] parts = request.getRefreshToken().split("\\.");
        if (parts.length != 3) {
            throw new UnauthorizedException("Invalid refresh token format.");
        }
        UUID sessionId;
        int presentedGeneration;
        try {
            sessionId = UUID.fromString(parts[0]);
            presentedGeneration = Integer.parseInt(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid refresh token format.");
        }

        UserSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new UnauthorizedException("Session not found."));
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new ApiException(ErrorCode.SESSION_TERMINATED, "Session has been terminated.", HttpStatus.UNAUTHORIZED);
        }

        if (presentedGeneration < session.getRefreshTokenGeneration()) {
            session.terminate(now, "REFRESH_REUSE", null);
            sessionRepository.save(session);
            auditService.logDetails(session.getUserId(), null, null, session.getDeviceId(),
                    "REFRESH_REUSE", AuditOutcome.DENIED, "user_session", sessionId.toString(),
                    AuditService.details("presentedGen", presentedGeneration, "storedGen", session.getRefreshTokenGeneration()), clientIp);
            throw new ApiException(ErrorCode.REFRESH_TOKEN_REUSE, "Token reuse detected. Session terminated.", HttpStatus.UNAUTHORIZED);
        }
        if (!CryptoUtils.sha256Hex(request.getRefreshToken()).equals(session.getRefreshTokenHash())) {
            session.terminate(now, "REFRESH_MISMATCH", null);
            sessionRepository.save(session);
            throw new ApiException(ErrorCode.REFRESH_TOKEN_REUSE, "Refresh token mismatch. Session terminated.", HttpStatus.UNAUTHORIZED);
        }

        boolean refreshExpired = session.getRefreshTokenExpiresAt() != null && session.getRefreshTokenExpiresAt().isBefore(now);
        if (session.getAbsoluteExpiresAt().isBefore(now) || refreshExpired) {
            session.setStatus(SessionStatus.EXPIRED);
            sessionRepository.save(session);
            throw new UnauthorizedException("Session expired. Please log in again.");
        }

        AppUser user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new UnauthorizedException("User not found."));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "User account is suspended.", HttpStatus.UNAUTHORIZED);
        }
        if (session.getClientType() != ClientType.MOBILE || session.getDeviceId() == null) {
            throw new UnauthorizedException("Refresh tokens are only supported for mobile sessions.");
        }

        if (proof.deviceId() == null || proof.deviceId().isBlank()) {
            throw new UnauthorizedException("Missing required X-Device-Id header.");
        }
        String signature = requireDeviceProofHeader(proof.signature(), "X-Device-Signature");
        String timestamp = requireDeviceProofHeader(proof.timestamp(), "X-Device-Timestamp");
        String nonce = requireDeviceProofHeader(proof.nonce(), "X-Device-Nonce");
        String counter = requireDeviceProofHeader(proof.counter(), "X-Device-Counter");

        requireBoundDevice(session, proof.deviceId().trim(), clientIp);

        long timestampValue;
        long counterValue;
        try {
            timestampValue = Long.parseLong(timestamp);
            counterValue = Long.parseLong(counter);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Malformed timestamp or counter format.");
        }
        deviceService.validateSignedRequest(session.getDeviceId(), "POST", REFRESH_PATH,
                request.getRefreshToken().getBytes(StandardCharsets.UTF_8),
                timestampValue, nonce, counterValue, proof.contextId(), signature);

        int nextGeneration = presentedGeneration + 1;
        String newRefreshToken = newRefreshToken(sessionId, nextGeneration);
        session.setRefreshTokenGeneration(nextGeneration);
        session.setRefreshTokenHash(CryptoUtils.sha256Hex(newRefreshToken));
        session.setRefreshTokenExpiresAt(now.plus(appProperties.getJwt().getRefreshTokenExpirationDays(), ChronoUnit.DAYS));
        session.setRotatedAt(now);
        session.setLastSeenAt(now);
        sessionRepository.save(session);

        auditService.log(user.getId(), user.getCustomerAccountId(), null, session.getDeviceId(),
                "TOKEN_REFRESH", AuditOutcome.SUCCESS, "user_session", sessionId.toString(), null, clientIp);

        return TokenRefreshResponse.builder()
                .accessToken(accessTokenFor(sessionId, user))
                .refreshToken(newRefreshToken)
                .expiresAt(accessTokenExpiry(now))
                .build();
    }

    private static String requireDeviceProofHeader(String value, String headerName) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_DEVICE_SIGNATURE, "Missing required " + headerName + " header.", HttpStatus.UNAUTHORIZED);
        }
        return value.trim();
    }

    /** The presented device must be the one the session is bound to, and that device must still be ACTIVE. */
    private void requireBoundDevice(UserSession session, String presentedDeviceId, String clientIp) {
        UUID presented;
        try {
            presented = UUID.fromString(presentedDeviceId);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid device ID format.");
        }
        if (!session.getDeviceId().equals(presented)) {
            auditService.logDetails(session.getUserId(), null, null, session.getDeviceId(),
                    "REFRESH_DEVICE_MISMATCH", AuditOutcome.DENIED, "user_session", session.getId().toString(),
                    Map.of("presentedDeviceId", presentedDeviceId), clientIp);
            throw new UnauthorizedException("Refresh token is bound to a different device.");
        }
        boolean deviceActive = deviceRepository.findById(session.getDeviceId())
                .map(device -> device.getStatus() == DeviceStatus.ACTIVE)
                .orElse(false);
        if (!deviceActive) {
            throw new ApiException(ErrorCode.DEVICE_REVOKED, "Device revoked or inactive.", HttpStatus.UNAUTHORIZED);
        }
    }

    @Override
    @Transactional
    public void logout(ElecSarthiUserPrincipal principal, String clientIp) {
        sessionRepository.findById(principal.getSessionId()).ifPresent(session -> {
            session.terminate(clock.instant(), "USER_LOGOUT", principal.getUserId());
            sessionRepository.save(session);
        });
        auditService.log(principal.getUserId(), principal.getCustomerAccountId(), null,
                principal.getDeviceId(), "LOGOUT", AuditOutcome.SUCCESS,
                "user_session", principal.getSessionId().toString(), null, clientIp);
    }

    @Override
    @Transactional
    public CustomerActivateResponse activateCustomer(CustomerActivateRequest request, String clientIp) {
        rateLimiterService.checkRateLimit("activate_ip", clientIp, 20);
        rateLimiterService.checkRateLimit("activate_phone", request.getContactPhone(), 10);
        Instant now = clock.instant();
        String pepper = appProperties.getSecurity().getActivationPepper();

        Optional<CustomerAccount> found = customerAccountRepository.findByContactPhone(request.getContactPhone());
        if (found.isEmpty()) {
            // Same work as a real check, so response timing does not reveal whether the phone is registered.
            CryptoUtils.hmacSha256Hex(request.getActivationCode() != null ? request.getActivationCode().toUpperCase(Locale.ROOT) : "DUMMY", pepper);
            auditService.logDetails(null, null, null, null, "CUSTOMER_ACTIVATION", AuditOutcome.DENIED, "customer_account", "UNKNOWN",
                    AuditService.details("reason", "Invalid activation credentials or account not eligible"), clientIp);
            throw new BadRequestException(INVALID_ACTIVATION);
        }
        CustomerAccount customer = found.get();
        if (customer.getActivationStatus() == ActivationStatus.ACTIVATED) {
            throw new BadRequestException(INVALID_ACTIVATION);
        }

        int maxAttempts = appProperties.getSecurity().getActivationMaxAttempts();
        if (customer.getActivationAttemptCount() >= maxAttempts) {
            throw new ApiException(ErrorCode.ACTIVATION_MAX_ATTEMPTS_EXCEEDED,
                    "Activation locked. Maximum attempts exceeded. Re-issue required.", HttpStatus.FORBIDDEN);
        }
        if (customer.getActivationCodeExpiresAt() != null && customer.getActivationCodeExpiresAt().isBefore(now)) {
            throw new ApiException(ErrorCode.ACTIVATION_CODE_EXPIRED,
                    "Activation code has expired. Please request a new code.", HttpStatus.BAD_REQUEST);
        }

        String presentedHash = CryptoUtils.hmacSha256Hex(request.getActivationCode().toUpperCase(Locale.ROOT), pepper);
        if (!hashesMatch(presentedHash, customer.getActivationCodeHash())) {
            short attempts = (short) (customer.getActivationAttemptCount() + 1);
            recordFailedActivation(customer.getId(), attempts, clientIp);
            // Same response as an unknown phone: a wrong code must not reveal that the phone is registered,
            // nor how many attempts are left. The lockout itself is still enforced above.
            throw new BadRequestException(INVALID_ACTIVATION);
        }

        customer.setActivationStatus(ActivationStatus.ACTIVATED);
        customer.setActivatedAt(now);
        customer.setActivationCodeHash(null); // the DB requires a NULL hash once ACTIVATED
        customer.setActivationAttemptCount((short) 0);
        customerAccountRepository.save(customer);

        AppUser primary = userRepository.findByCustomerAccountIdAndRole(customer.getId(), Role.CUSTOMER)
                .orElseThrow(() -> new ResourceNotFoundException("Primary customer user not found"));
        primary.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        primary.setStatus(UserStatus.ACTIVE);
        userRepository.save(primary);

        auditService.log(null, customer.getId(), null, null, "CUSTOMER_ACTIVATE", AuditOutcome.SUCCESS,
                "customer_account", customer.getId().toString(), null, clientIp);

        return CustomerActivateResponse.builder()
                .message("Customer account activated successfully")
                .registrationToken(tokenProvider.generateRegistrationToken(
                        primary.getId(), primary.getUsername(), primary.getRole(), customer.getId()))
                .userId(primary.getId())
                .role(primary.getRole())
                .customerAccountId(customer.getId())
                .build();
    }

    /** Counts the failed attempt in its own transaction so the lockout and its audit row survive the rollback. */
    private void recordFailedActivation(Long customerId, short attempts, String clientIp) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE customer_account SET activation_attempt_count = ? WHERE id = ?", attempts, customerId);
            auditService.logDetails(null, customerId, null, null, "CUSTOMER_ACTIVATE", AuditOutcome.DENIED,
                    "customer_account", customerId.toString(), AuditService.details("reason", "Invalid code attempt"), clientIp);
        });
    }

    /** Constant-time comparison of two hex digests, ignoring case. */
    private static boolean hashesMatch(String presentedHex, String storedHex) {
        return storedHex != null && MessageDigest.isEqual(
                presentedHex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                storedHex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /** Wire format sessionId.generation.secret; only the SHA-256 of it is stored. */
    private String newRefreshToken(UUID sessionId, int generation) {
        byte[] secret = new byte[32];
        secureRandom.nextBytes(secret);
        return sessionId + "." + generation + "." + HexFormat.of().formatHex(secret);
    }

    private String accessTokenFor(UUID sessionId, AppUser user) {
        return tokenProvider.generateAccessToken(sessionId, user.getId(), user.getUsername(), user.getRole(), user.getCustomerAccountId());
    }

    private Instant accessTokenExpiry(Instant now) {
        return now.plus(appProperties.getJwt().getAccessTokenExpirationMinutes(), ChronoUnit.MINUTES);
    }
}
