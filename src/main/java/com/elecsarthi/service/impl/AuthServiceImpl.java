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
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.security.jwt.JwtTokenProvider;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthServiceImpl(AppUserRepository userRepository,
                           CustomerAccountRepository customerAccountRepository,
                           UserDeviceRepository deviceRepository,
                           UserSessionRepository sessionRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider tokenProvider,
                           AuditService auditService,
                           AppProperties appProperties,
                           Clock clock) {
        this.userRepository = userRepository;
        this.customerAccountRepository = customerAccountRepository;
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.auditService = auditService;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, String clientIp) {
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
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid username or password.", HttpStatus.UNAUTHORIZED);
        }

        // Lock user row for atomic session limit enforcement
        user = userRepository.findByIdForUpdate(user.getId()).orElse(user);

        // Verify user status
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

        // Verify password
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            auditService.log(user.getId(), user.getCustomerAccountId(), null, null, "LOGIN", AuditOutcome.DENIED,
                    "app_user", user.getUsername(), "{\"reason\":\"Bad password\"}", clientIp);
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid username or password.", HttpStatus.UNAUTHORIZED);
        }

        // Enforce client separation
        if (request.getClientType() == ClientType.WEB) {
            if (user.getRole() != Role.OWNER && user.getRole() != Role.SUBOWNER) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Mobile application users cannot log in to the web console.", HttpStatus.FORBIDDEN);
            }
        } else if (request.getClientType() == ClientType.MOBILE) {
            if (user.getRole() == Role.OWNER || user.getRole() == Role.SUBOWNER) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Web console administrators cannot log in to the mobile application.", HttpStatus.FORBIDDEN);
            }
        }

        // For mobile roles, verify registered active device
        UserDevice userDevice = null;
        if (request.getClientType() == ClientType.MOBILE) {
            if (request.getInstallationId() == null || request.getInstallationId().isBlank()) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Device installation ID is required for mobile login.", HttpStatus.BAD_REQUEST);
            }
            Optional<UserDevice> activeDevOpt = deviceRepository.findByUserIdAndStatus(user.getId(), DeviceStatus.ACTIVE);
            if (activeDevOpt.isEmpty() || !activeDevOpt.get().getInstallationId().equals(request.getInstallationId())) {
                throw new ApiException(ErrorCode.DEVICE_PENDING_VERIFICATION, "Device is not registered or not active.", HttpStatus.FORBIDDEN);
            }
            userDevice = activeDevOpt.get();
        }

        // Check concurrent session limits: Owner=3, Subowner=3, Agent=1, Customer=1, Subuser=1
        int maxSessions = switch (user.getRole()) {
            case OWNER, SUBOWNER -> 3;
            case AGENT, CUSTOMER, SUBUSER -> 1;
        };

        List<UserSession> activeSessions = sessionRepository.findByUserIdAndStatus(user.getId(), SessionStatus.ACTIVE);
        if (activeSessions.size() >= maxSessions) {
            // Check same device replacement exception for mobile
            if (userDevice != null) {
                final UUID activeDevId = userDevice.getId();
                Optional<UserSession> sameDeviceSession = activeSessions.stream()
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
        Instant now = clock.instant();

        // Wire format: sid.generation.secret
        String[] parts = request.getRefreshToken().split("\\.");
        if (parts.length != 3) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid refresh token format.", HttpStatus.UNAUTHORIZED);
        }

        UUID sessionId;
        int presentedGen;
        try {
            sessionId = UUID.fromString(parts[0]);
            presentedGen = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid refresh token format.", HttpStatus.UNAUTHORIZED);
        }

        UserSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Session not found.", HttpStatus.UNAUTHORIZED));

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
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Session expired. Please log in again.", HttpStatus.UNAUTHORIZED);
        }

        // Verify user and device still active
        AppUser user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "User not found.", HttpStatus.UNAUTHORIZED));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "User account is suspended.", HttpStatus.UNAUTHORIZED);
        }

        if (session.getDeviceId() != null) {
            UserDevice device = deviceRepository.findById(session.getDeviceId()).orElse(null);
            if (device == null || device.getStatus() != DeviceStatus.ACTIVE) {
                throw new ApiException(ErrorCode.DEVICE_REVOKED, "Device revoked.", HttpStatus.UNAUTHORIZED);
            }
        }

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
    public void activateCustomer(CustomerActivateRequest request, String clientIp) {
        Instant now = clock.instant();

        CustomerAccount customer = customerAccountRepository.findByContactPhone(request.getContactPhone())
                .orElseThrow(() -> new ResourceNotFoundException("Customer account not found for contact phone."));

        if (customer.getActivationStatus() == ActivationStatus.ACTIVATED) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Customer account is already activated.", HttpStatus.BAD_REQUEST);
        }

        if (customer.getActivationAttemptCount() >= appProperties.getSecurity().getActivationMaxAttempts()) {
            throw new ApiException(ErrorCode.ACTIVATION_MAX_ATTEMPTS_EXCEEDED, "Activation locked. Maximum attempts exceeded. Re-issue required.", HttpStatus.FORBIDDEN);
        }

        if (customer.getActivationCodeExpiresAt() != null && customer.getActivationCodeExpiresAt().isBefore(now)) {
            throw new ApiException(ErrorCode.ACTIVATION_CODE_EXPIRED, "Activation code has expired. Please request a new code.", HttpStatus.BAD_REQUEST);
        }

        // Verify HMAC-SHA-256 with pepper
        String expectedHash = CryptoUtils.hmacSha256Hex(request.getActivationCode().toUpperCase(), appProperties.getSecurity().getActivationPepper());
        if (!expectedHash.equalsIgnoreCase(customer.getActivationCodeHash())) {
            customer.setActivationAttemptCount((short) (customer.getActivationAttemptCount() + 1));
            customerAccountRepository.save(customer);

            auditService.log(null, customer.getId(), null, null, "CUSTOMER_ACTIVATE", AuditOutcome.DENIED,
                    "customer_account", customer.getId().toString(), "{\"reason\":\"Invalid code attempt\"}", clientIp);

            throw new ApiException(ErrorCode.BAD_REQUEST, "Invalid activation code. Attempts remaining: " + (5 - customer.getActivationAttemptCount()), HttpStatus.BAD_REQUEST);
        }

        // Activation success: activate customer and primary user
        customer.setActivationStatus(ActivationStatus.ACTIVATED);
        customer.setActivatedAt(now);
        customer.setActivationCodeHash(null); // Must be null when ACTIVATED per DB constraint
        customer.setActivationAttemptCount((short) 0);
        customerAccountRepository.save(customer);

        // Find primary customer user
        Optional<AppUser> primaryUserOpt = userRepository.findByCustomerAccountIdAndRole(customer.getId(), Role.CUSTOMER);
        if (primaryUserOpt.isPresent()) {
            AppUser primary = primaryUserOpt.get();
            primary.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            primary.setStatus(UserStatus.ACTIVE);
            userRepository.save(primary);
        }

        auditService.log(null, customer.getId(), null, null, "CUSTOMER_ACTIVATE", AuditOutcome.SUCCESS,
                "customer_account", customer.getId().toString(), null, clientIp);
    }
}
