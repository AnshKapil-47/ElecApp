package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.CustomerActivateRequest;
import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.dto.request.RefreshTokenRequest;
import com.elecsarthi.dto.response.LoginResponse;
import com.elecsarthi.dto.response.TokenRefreshResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.entity.UserSession;
import com.elecsarthi.enums.*;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

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

    public AuthServiceImpl(AppUserRepository userRepository,
                           CustomerAccountRepository customerAccountRepository,
                           UserDeviceRepository deviceRepository,
                           UserSessionRepository sessionRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider tokenProvider,
                           AuditService auditService,
                           AppProperties appProperties,
                           Clock clock,
                           RateLimiterService rateLimiterService,
                           JdbcTemplate jdbcTemplate,
                           PlatformTransactionManager transactionManager,
                           DeviceService deviceService) {
        this.userRepository = userRepository;
        this.customerAccountRepository = customerAccountRepository;
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.auditService = auditService;
        this.appProperties = appProperties;
        this.clock = clock;
        this.rateLimiterService = rateLimiterService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
        this.deviceService = deviceService;
    }

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, String clientIp) {
        // Rate limit login attempts per IP and per identifier
        rateLimiterService.checkRateLimit("login_ip", clientIp, 30);
        rateLimiterService.checkRateLimit("login_id", request.getIdentifier(), 15);

        Instant now = clock.instant();

        // 1. Resolve user by identifier (username or contact_phone)
        AppUser user = null;
        Optional<AppUser> userByUsername = userRepository.findByUsername(request.getIdentifier());
        if (userByUsername.isPresent()) {
            user = userByUsername.get();
        } else {
            // Check customer account by contact phone
            Optional<CustomerAccount> custByPhone = customerAccountRepository.findByContactPhone(request.getIdentifier());
            if (custByPhone.isPresent()) {
                Optional<AppUser> primaryUser = userRepository.findByCustomerAccountIdAndRole(custByPhone.get().getId(), Role.CUSTOMER);
                if (primaryUser.isEmpty()) {
                    // Also check legacy CUSTOMER_ADMIN if present
                    primaryUser = userRepository.findByCustomerAccountIdAndRole(custByPhone.get().getId(), Role.CUSTOMER);
                }
                user = primaryUser.orElse(null);
            }
        }

        if (user == null) {
            auditService.log(null, null, null, null, "LOGIN", AuditOutcome.DENIED,
                    "app_user", request.getIdentifier(), "{\"reason\":\"User not found\"}", clientIp);
            throw new UnauthorizedException("Invalid username or password.");
        }

        // Lock user row for atomic session limit enforcement
        user = userRepository.findByIdForUpdate(user.getId()).orElse(user);

        // Verify password first to prevent user enumeration
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            auditService.log(user.getId(), user.getCustomerAccountId(), null, null, "LOGIN", AuditOutcome.DENIED,
                    "app_user", user.getUsername(), "{\"reason\":\"Bad credentials\"}", clientIp);
            throw new UnauthorizedException("Invalid username or password.");
        }

        // Verify user status after password matches
        if (user.getStatus() == UserStatus.PENDING) {
            throw new ApiException(ErrorCode.ACCOUNT_PENDING_ACTIVATION, "Account is pending activation.", HttpStatus.FORBIDDEN);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Account is not active.", HttpStatus.FORBIDDEN);
        }

        // Verify customer account status if applicable
        CustomerAccount customerAccount = null;
        if (user.getCustomerAccountId() != null) {
            customerAccount = customerAccountRepository.findById(user.getCustomerAccountId()).orElse(null);
            if (customerAccount == null || customerAccount.getStatus() != UserStatus.ACTIVE) {
                throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Customer account is suspended.", HttpStatus.FORBIDDEN);
            }
        }

        // Enforce client separation
        if (request.getClientType() == ClientType.WEB) {
            if (user.getRole() != Role.OWNER && user.getRole() != Role.SUBOWNER) {
                throw new ForbiddenException("Mobile application users cannot log in to the web console.");
            }
        } else if (request.getClientType() == ClientType.MOBILE) {
            if (user.getRole() == Role.OWNER || user.getRole() == Role.SUBOWNER) {
                throw new ForbiddenException("Web console administrators cannot log in to the mobile application.");
            }
        }

        // For mobile roles, check registered active device (device registration bootstrap)
        UserDevice userDevice = null;
        if (request.getClientType() == ClientType.MOBILE) {
            Optional<UserDevice> activeDevOpt = deviceRepository.findByUserIdAndStatus(user.getId(), DeviceStatus.ACTIVE);
            if (activeDevOpt.isEmpty()) {
                // Case A: Deadlock-free device bootstrap: issue short-lived, single-use registration token
                String regToken = tokenProvider.generateRegistrationToken(user.getId(), user.getUsername(), user.getRole(), user.getCustomerAccountId());
                return LoginResponse.builder()
                        .userId(user.getId())
                        .username(user.getUsername())
                        .fullName(user.getFullName())
                        .role(user.getRole())
                        .customerAccountId(user.getCustomerAccountId())
                        .clientType(ClientType.MOBILE)
                        .deviceRegistrationRequired(true)
                        .registrationToken(regToken)
                        .mustChangePassword(user.isMustChangePassword())
                        .build();
            }

            if (request.getInstallationId() == null || request.getInstallationId().isBlank()) {
                throw new BadRequestException("Device installation ID is required for mobile login.");
            }

            UserDevice activeDev = activeDevOpt.get();
            if (!activeDev.getInstallationId().equals(request.getInstallationId())) {
                // Case C: Attempted login from a new/different installation while an active device already exists.
                // Prevent password-only device takeover: reject login without issuing registration token.
                auditService.log(user.getId(), user.getCustomerAccountId(), null, activeDev.getId(),
                        "LOGIN_DEVICE_MISMATCH", AuditOutcome.DENIED, "app_user", user.getUsername(),
                        "{\"reason\":\"Attempted login from new installation while active device exists\",\"attemptedInstallationId\":\"" + request.getInstallationId() + "\"}",
                        clientIp);
                throw new ApiException(ErrorCode.DEVICE_CHANGE_NOT_ALLOWED,
                        "An active device is already registered for this account. Contact an administrator to revoke the existing device before registering a new one.",
                        HttpStatus.FORBIDDEN);
            }

            // Case B: Same registered device installation -> proceed with login
            userDevice = activeDev;
        }

        // Check concurrent session limits: Owner=3, Subowner=3, Agent=1, Customer=1, Subuser=1
        int maxSessions = switch (user.getRole()) {
            case OWNER, SUBOWNER -> 3;
            case AGENT, CUSTOMER, SUBUSER -> 1;
        };

        // Terminate expired sessions before enforcing limit
        List<UserSession> activeSessions = sessionRepository.findByUserIdAndStatus(user.getId(), SessionStatus.ACTIVE);
        List<UserSession> trulyActiveSessions = new ArrayList<>();
        for (UserSession s : activeSessions) {
            boolean expired = s.getAbsoluteExpiresAt().isBefore(now) || (s.getIdleExpiresAt() != null && s.getIdleExpiresAt().isBefore(now));
            if (expired) {
                s.setStatus(SessionStatus.TERMINATED);
                s.setTerminatedAt(now);
                s.setTerminationReason(s.getAbsoluteExpiresAt().isBefore(now) ? "ABSOLUTE_EXPIRED" : "IDLE_EXPIRED");
                sessionRepository.save(s);
            } else {
                trulyActiveSessions.add(s);
            }
        }

        if (trulyActiveSessions.size() >= maxSessions) {
            // Check same device replacement exception for mobile
            if (userDevice != null) {
                final UUID activeDevId = userDevice.getId();
                Optional<UserSession> sameDeviceSession = trulyActiveSessions.stream()
                        .filter(s -> activeDevId.equals(s.getDeviceId()))
                        .findFirst();
                if (sameDeviceSession.isPresent()) {
                    UserSession old = sameDeviceSession.get();
                    old.setStatus(SessionStatus.TERMINATED);
                    old.setTerminatedAt(now);
                    old.setTerminationReason("REPLACED_SAME_DEVICE");
                    old.setTerminatedByUserId(user.getId());
                    sessionRepository.save(old);
                } else {
                    throw new ConflictException(ErrorCode.SESSION_LIMIT_EXCEEDED, "Concurrent session limit reached. You are logged in on another device.");
                }
            } else {
                throw new ConflictException(ErrorCode.SESSION_LIMIT_EXCEEDED, "Concurrent session limit reached (" + maxSessions + ").");
            }
        }

        // Create new user_session
        UUID sessionId = UUID.randomUUID();
        String wireRefreshToken = null;
        Instant refreshExpiresAt = null;
        Instant idleExpiresAt = null;
        Instant absoluteExpiresAt;

        if (request.getClientType() == ClientType.MOBILE) {
            byte[] secretBytes = new byte[32];
            secureRandom.nextBytes(secretBytes);
            String secretHex = HexFormat.of().formatHex(secretBytes);
            wireRefreshToken = sessionId + ".1." + secretHex;

            refreshExpiresAt = now.plus(appProperties.getJwt().getRefreshTokenExpirationDays(), ChronoUnit.DAYS);
            absoluteExpiresAt = now.plus(appProperties.getJwt().getSessionAbsoluteExpirationDays(), ChronoUnit.DAYS);
        } else {
            idleExpiresAt = now.plus(appProperties.getJwt().getWebIdleExpirationMinutes(), ChronoUnit.MINUTES);
            absoluteExpiresAt = now.plus(appProperties.getJwt().getWebAbsoluteExpirationHours(), ChronoUnit.HOURS);
        }

        UserSession session = UserSession.builder()
                .id(sessionId)
                .userId(user.getId())
                .userRole(user.getRole())
                .deviceId(userDevice != null ? userDevice.getId() : null)
                .clientType(request.getClientType())
                .status(SessionStatus.ACTIVE)
                .issuedAt(now)
                .lastSeenAt(now)
                .idleExpiresAt(idleExpiresAt)
                .absoluteExpiresAt(absoluteExpiresAt)
                .refreshTokenHash(wireRefreshToken != null ? CryptoUtils.sha256Hex(wireRefreshToken) : null)
                .refreshTokenGeneration(1)
                .refreshTokenExpiresAt(refreshExpiresAt)
                .ipAddress(clientIp)
                .build();

        sessionRepository.save(session);

        String accessToken = tokenProvider.generateAccessToken(
                sessionId,
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getCustomerAccountId()
        );

        auditService.log(user.getId(), user.getCustomerAccountId(), null,
                userDevice != null ? userDevice.getId() : null, "LOGIN", AuditOutcome.SUCCESS,
                "user_session", sessionId.toString(),
                "{\"clientType\":\"" + request.getClientType() + "\"}", clientIp);

        return LoginResponse.builder()
                .sessionId(sessionId)
                .userId(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .role(user.getRole())
                .customerAccountId(user.getCustomerAccountId())
                .clientType(request.getClientType())
                .accessToken(accessToken)
                .refreshToken(wireRefreshToken)
                .expiresAt(now.plus(appProperties.getJwt().getAccessTokenExpirationMinutes(), ChronoUnit.MINUTES))
                .mustChangePassword(user.isMustChangePassword())
                .build();
    }

    @Override
    @Transactional(noRollbackFor = {ApiException.class})
    public TokenRefreshResponse refreshToken(RefreshTokenRequest request, String clientIp) {
        return refreshToken(request, clientIp, null, null, null, null, null, null);
    }

    @Override
    @Transactional(noRollbackFor = {ApiException.class})
    public TokenRefreshResponse refreshToken(RefreshTokenRequest request, String clientIp, String deviceIdHeader,
                                             String deviceSignature, String deviceTimestamp, String deviceNonce,
                                             String deviceCounter, String deviceContextId) {
        // Rate limit refresh token requests per IP
        rateLimiterService.checkRateLimit("refresh_ip", clientIp, 60);

        Instant now = clock.instant();

        // Wire format: sid.generation.secret
        String[] parts = request.getRefreshToken().split("\\.");
        if (parts.length != 3) {
            throw new UnauthorizedException("Invalid refresh token format.");
        }

        UUID sessionId;
        int presentedGen;
        try {
            sessionId = UUID.fromString(parts[0]);
            presentedGen = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid refresh token format.");
        }

        UserSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new UnauthorizedException("Session not found."));

        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new ApiException(ErrorCode.SESSION_TERMINATED, "Session has been terminated.", HttpStatus.UNAUTHORIZED);
        }

        // Token reuse detection
        if (presentedGen < session.getRefreshTokenGeneration()) {
            session.setStatus(SessionStatus.TERMINATED);
            session.setTerminatedAt(now);
            session.setTerminationReason("REFRESH_REUSE");
            sessionRepository.save(session);

            auditService.log(session.getUserId(), null, null, session.getDeviceId(),
                    "REFRESH_REUSE", AuditOutcome.DENIED, "user_session", sessionId.toString(),
                    "{\"presentedGen\":" + presentedGen + ",\"storedGen\":" + session.getRefreshTokenGeneration() + "}", clientIp);

            throw new ApiException(ErrorCode.REFRESH_TOKEN_REUSE, "Token reuse detected. Session terminated.", HttpStatus.UNAUTHORIZED);
        }

        String presentedHash = CryptoUtils.sha256Hex(request.getRefreshToken());
        if (!presentedHash.equals(session.getRefreshTokenHash())) {
            session.setStatus(SessionStatus.TERMINATED);
            session.setTerminatedAt(now);
            session.setTerminationReason("REFRESH_MISMATCH");
            sessionRepository.save(session);
            throw new ApiException(ErrorCode.REFRESH_TOKEN_REUSE, "Refresh token mismatch. Session terminated.", HttpStatus.UNAUTHORIZED);
        }

        // Check absolute expiry
        if (session.getAbsoluteExpiresAt().isBefore(now) || (session.getRefreshTokenExpiresAt() != null && session.getRefreshTokenExpiresAt().isBefore(now))) {
            session.setStatus(SessionStatus.EXPIRED);
            sessionRepository.save(session);
            throw new UnauthorizedException("Session expired. Please log in again.");
        }

        // Verify user still active
        AppUser user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new UnauthorizedException("User not found."));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "User account is suspended.", HttpStatus.UNAUTHORIZED);
        }

        // Refresh tokens are strictly restricted to mobile sessions
        if (session.getClientType() != ClientType.MOBILE || session.getDeviceId() == null) {
            throw new UnauthorizedException("Refresh tokens are only supported for mobile sessions.");
        }

        // Mandatory device proof headers for mobile refresh
        if (deviceIdHeader == null || deviceIdHeader.isBlank()) {
            throw new UnauthorizedException("Missing required X-Device-Id header.");
        }
        if (deviceSignature == null || deviceSignature.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_DEVICE_SIGNATURE, "Missing required X-Device-Signature header.", HttpStatus.UNAUTHORIZED);
        }
        if (deviceTimestamp == null || deviceTimestamp.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_DEVICE_SIGNATURE, "Missing required X-Device-Timestamp header.", HttpStatus.UNAUTHORIZED);
        }
        if (deviceNonce == null || deviceNonce.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_DEVICE_SIGNATURE, "Missing required X-Device-Nonce header.", HttpStatus.UNAUTHORIZED);
        }
        if (deviceCounter == null || deviceCounter.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_DEVICE_SIGNATURE, "Missing required X-Device-Counter header.", HttpStatus.UNAUTHORIZED);
        }

        // 1. Verify supplied device ID matches session's registered device
        UUID presentedDevId;
        try {
            presentedDevId = UUID.fromString(deviceIdHeader.trim());
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid device ID format.");
        }
        if (!session.getDeviceId().equals(presentedDevId)) {
            auditService.log(session.getUserId(), null, null, session.getDeviceId(),
                    "REFRESH_DEVICE_MISMATCH", AuditOutcome.DENIED, "user_session", sessionId.toString(),
                    "{\"presentedDeviceId\":\"" + deviceIdHeader + "\"}", clientIp);
            throw new UnauthorizedException("Refresh token is bound to a different device.");
        }

        // 2. Bound device must exist and be ACTIVE
        UserDevice device = deviceRepository.findById(session.getDeviceId()).orElse(null);
        if (device == null || device.getStatus() != DeviceStatus.ACTIVE) {
            throw new ApiException(ErrorCode.DEVICE_REVOKED, "Device revoked or inactive.", HttpStatus.UNAUTHORIZED);
        }

        // 3. Parse timestamp and counter
        long ts;
        long counter;
        try {
            ts = Long.parseLong(deviceTimestamp.trim());
            counter = Long.parseLong(deviceCounter.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Malformed timestamp or counter format.");
        }

        // 4. Validate cryptographic ECDSA P-256 signature, window, monotonic counter, nonce uniqueness
        byte[] body = request.getRefreshToken().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        deviceService.validateSignedRequest(
                session.getDeviceId(),
                "POST",
                "/api/v1/auth/refresh",
                body,
                ts,
                deviceNonce.trim(),
                counter,
                deviceContextId,
                deviceSignature.trim()
        );

        // Rotate token: increment generation and generate new secret
        int nextGen = presentedGen + 1;
        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        String secretHex = HexFormat.of().formatHex(secretBytes);
        String newWireToken = sessionId + "." + nextGen + "." + secretHex;

        session.setRefreshTokenGeneration(nextGen);
        session.setRefreshTokenHash(CryptoUtils.sha256Hex(newWireToken));
        session.setRefreshTokenExpiresAt(now.plus(appProperties.getJwt().getRefreshTokenExpirationDays(), ChronoUnit.DAYS));
        session.setRotatedAt(now);
        session.setLastSeenAt(now);
        sessionRepository.save(session);

        String newAccessToken = tokenProvider.generateAccessToken(
                sessionId,
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getCustomerAccountId()
        );

        auditService.log(user.getId(), user.getCustomerAccountId(), null, session.getDeviceId(),
                "TOKEN_REFRESH", AuditOutcome.SUCCESS, "user_session", sessionId.toString(), null, clientIp);

        return TokenRefreshResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newWireToken)
                .expiresAt(now.plus(appProperties.getJwt().getAccessTokenExpirationMinutes(), ChronoUnit.MINUTES))
                .build();
    }

    @Override
    @Transactional
    public void logout(ElecSarthiUserPrincipal principal, String clientIp) {
        Instant now = clock.instant();
        sessionRepository.findById(principal.getSessionId()).ifPresent(session -> {
            session.setStatus(SessionStatus.TERMINATED);
            session.setTerminatedAt(now);
            session.setTerminationReason("USER_LOGOUT");
            session.setTerminatedByUserId(principal.getUserId());
            sessionRepository.save(session);
        });

        auditService.log(principal.getUserId(), principal.getCustomerAccountId(), null,
                principal.getDeviceId(), "LOGOUT", AuditOutcome.SUCCESS,
                "user_session", principal.getSessionId().toString(), null, clientIp);
    }

    @Override
    @Transactional
    public com.elecsarthi.dto.response.CustomerActivateResponse activateCustomer(CustomerActivateRequest request, String clientIp) {
        // Rate limit activation requests per IP and per phone
        rateLimiterService.checkRateLimit("activate_ip", clientIp, 20);
        rateLimiterService.checkRateLimit("activate_phone", request.getContactPhone(), 10);

        Instant now = clock.instant();

        Optional<CustomerAccount> custOpt = customerAccountRepository.findByContactPhone(request.getContactPhone());
        if (custOpt.isEmpty()) {
            // Anti-phone enumeration: perform dummy HMAC to maintain constant timing against timing side channels
            CryptoUtils.hmacSha256Hex(request.getActivationCode() != null ? request.getActivationCode().toUpperCase() : "DUMMY",
                    appProperties.getSecurity().getActivationPepper());
            auditService.log(null, null, null, null,
                    "CUSTOMER_ACTIVATION", AuditOutcome.DENIED, "customer_account", "UNKNOWN",
                    "{\"reason\":\"Invalid activation credentials or account not eligible\"}", clientIp);
            throw new BadRequestException("Invalid activation credentials or account not eligible for activation.");
        }

        CustomerAccount customer = custOpt.get();

        if (customer.getActivationStatus() == ActivationStatus.ACTIVATED) {
            throw new BadRequestException("Invalid activation credentials or account not eligible for activation.");
        }

        int maxAttempts = appProperties.getSecurity().getActivationMaxAttempts();
        if (customer.getActivationAttemptCount() >= maxAttempts) {
            throw new ApiException(ErrorCode.ACTIVATION_MAX_ATTEMPTS_EXCEEDED, "Activation locked. Maximum attempts exceeded. Re-issue required.", HttpStatus.FORBIDDEN);
        }

        if (customer.getActivationCodeExpiresAt() != null && customer.getActivationCodeExpiresAt().isBefore(now)) {
            throw new ApiException(ErrorCode.ACTIVATION_CODE_EXPIRED, "Activation code has expired. Please request a new code.", HttpStatus.BAD_REQUEST);
        }

        // Verify HMAC-SHA-256 with pepper
        String expectedHash = CryptoUtils.hmacSha256Hex(request.getActivationCode().toUpperCase(), appProperties.getSecurity().getActivationPepper());
        if (!expectedHash.equalsIgnoreCase(customer.getActivationCodeHash())) {
            short newAttempts = (short) (customer.getActivationAttemptCount() + 1);

            // Dedicated REQUIRES_NEW transaction so lockout and audit survive exception without broad noRollbackFor
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txTemplate.execute(status -> {
                jdbcTemplate.update("UPDATE customer_account SET activation_attempt_count = ? WHERE id = ?", newAttempts, customer.getId());
                auditService.log(null, customer.getId(), null, null, "CUSTOMER_ACTIVATE", AuditOutcome.DENIED,
                        "customer_account", customer.getId().toString(), "{\"reason\":\"Invalid code attempt\"}", clientIp);
                return null;
            });

            int remaining = Math.max(0, maxAttempts - newAttempts);
            throw new BadRequestException("Invalid activation code. Attempts remaining: " + remaining);
        }

        // Activation success: activate customer and primary user
        customer.setActivationStatus(ActivationStatus.ACTIVATED);
        customer.setActivatedAt(now);
        customer.setActivationCodeHash(null); // Must be null when ACTIVATED per DB constraint
        customer.setActivationAttemptCount((short) 0);
        customerAccountRepository.save(customer);

        // Find primary customer user
        Optional<AppUser> primaryUserOpt = userRepository.findByCustomerAccountIdAndRole(customer.getId(), Role.CUSTOMER);
        AppUser primary = primaryUserOpt.orElseThrow(() -> new ResourceNotFoundException("Primary customer user not found"));
        primary.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        primary.setStatus(UserStatus.ACTIVE);
        userRepository.save(primary);

        auditService.log(null, customer.getId(), null, null, "CUSTOMER_ACTIVATE", AuditOutcome.SUCCESS,
                "customer_account", customer.getId().toString(), null, clientIp);

        // Single-use device registration token to bootstrap first device enrollment
        String regToken = tokenProvider.generateRegistrationToken(primary.getId(), primary.getUsername(), primary.getRole(), customer.getId());

        return com.elecsarthi.dto.response.CustomerActivateResponse.builder()
                .message("Customer account activated successfully")
                .registrationToken(regToken)
                .userId(primary.getId())
                .role(primary.getRole())
                .customerAccountId(customer.getId())
                .build();
    }
}
