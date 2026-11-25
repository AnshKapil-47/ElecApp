package com.elecsarthi.service.impl;

import java.util.Locale;
import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.DeviceRegisterRequest;
import com.elecsarthi.dto.request.DeviceVerifyRequest;
import com.elecsarthi.dto.response.DeviceRegisterResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.DeviceNonce;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SessionStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.common.UnauthorizedException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DeviceNonceRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.security.signing.DeviceSignatureVerifier;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.DeviceService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceServiceImpl implements DeviceService {

    private final UserDeviceRepository deviceRepository;
    private final AppUserRepository userRepository;
    private final CustomerAccountRepository customerAccountRepository;
    private final UserSessionRepository sessionRepository;
    private final DeviceNonceRepository nonceRepository;
    private final DeviceSignatureVerifier deviceSignatureVerifier;
    private final AuditService auditService;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;
    private final com.elecsarthi.security.jwt.JwtTokenProvider tokenProvider;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceServiceImpl(UserDeviceRepository deviceRepository,
                             AppUserRepository userRepository,
                             CustomerAccountRepository customerAccountRepository,
                             UserSessionRepository sessionRepository,
                             DeviceNonceRepository nonceRepository,
                             DeviceSignatureVerifier deviceSignatureVerifier,
                             AuditService auditService,
                             AppProperties appProperties,
                             JdbcTemplate jdbcTemplate,
                             com.elecsarthi.security.jwt.JwtTokenProvider tokenProvider,
                             Clock clock) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.customerAccountRepository = customerAccountRepository;
        this.sessionRepository = sessionRepository;
        this.nonceRepository = nonceRepository;
        this.deviceSignatureVerifier = deviceSignatureVerifier;
        this.auditService = auditService;
        this.appProperties = appProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.tokenProvider = tokenProvider;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DeviceRegisterResponse registerDevice(Long userId, DeviceRegisterRequest request, String clientIp) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.getRole() == Role.OWNER || user.getRole() == Role.SUBOWNER) {
            throw new ForbiddenException("Owner and Subowner users use the web application only.");
        }

        // Prevent password-only device replacement. If an active device exists, reject registration.
        Optional<UserDevice> activeOpt = deviceRepository.findByUserIdAndStatus(userId, DeviceStatus.ACTIVE);
        if (activeOpt.isPresent()) {
            throw new ApiException(ErrorCode.DEVICE_CHANGE_NOT_ALLOWED,
                    "An active device is already registered for this user. The active device must be revoked by an administrator before a new device can be registered.",
                    HttpStatus.FORBIDDEN);
        }

        // Check if installation ID was ever revoked for this customer
        Optional<UserDevice> existingByInst = deviceRepository.findByInstallationId(request.getInstallationId());
        if (existingByInst.isPresent() && existingByInst.get().getStatus() == DeviceStatus.REVOKED) {
            throw new ConflictException(ErrorCode.DEVICE_REVOKED, "A revoked device installation cannot be re-registered.");
        }

        // Clean up any stale PENDING registration for this user
        Optional<UserDevice> pendingOpt = deviceRepository.findByUserIdAndStatus(userId, DeviceStatus.PENDING);
        // Flush the delete before the new PENDING row is inserted: Hibernate would otherwise run the insert first and
        // violate ux_device_pending_per_user, so a user whose registration was abandoned could never register again.
        pendingOpt.ifPresent(stale -> {
            deviceRepository.delete(stale);
            deviceRepository.flush();
        });

        // Generate 32-byte challenge
        byte[] challengeBytes = new byte[32];
        secureRandom.nextBytes(challengeBytes);
        String base64Challenge = Base64.getEncoder().encodeToString(challengeBytes);
        String challengeHash = CryptoUtils.sha256Hex(challengeBytes);

        Instant now = clock.instant();
        Instant expiresAt = now.plus(appProperties.getSecurity().getDeviceChallengeExpirationMinutes(), ChronoUnit.MINUTES);

        UserDevice device = UserDevice.builder()
                .userId(user.getId())
                .customerAccountId(user.getCustomerAccountId())
                .userRole(user.getRole())
                .installationId(request.getInstallationId())
                .publicKey(request.getPublicKey())
                .platform(request.getPlatform())
                .deviceName(request.getDeviceName())
                .status(DeviceStatus.PENDING)
                .challengeHash(challengeHash)
                .challengePurpose("DEVICE_REGISTRATION")
                .challengeExpiresAt(expiresAt)
                .lastRequestCounter(0L)
                .createdAt(now)
                .build();

        device = deviceRepository.save(device);

        auditService.logDetails(userId, user.getCustomerAccountId(), null, device.getId(),
                "DEVICE_REGISTER_INITIATED", AuditOutcome.SUCCESS, "user_device", device.getId().toString(),
                AuditService.details("installationId", request.getInstallationId()), clientIp);

        return DeviceRegisterResponse.builder()
                .deviceId(device.getId())
                .challenge(challengeHash)
                .expiresAt(expiresAt)
                .build();
    }

    @Override
    @Transactional
    public void verifyDevice(Long userId, DeviceVerifyRequest request, String clientIp) {
        verifyDevice(userId, request, clientIp, null);
    }

    @Override
    @Transactional
    public void verifyDevice(Long userId, DeviceVerifyRequest request, String clientIp, String registrationJti) {
        // Concurrency-safe atomic token invalidation across replicas:
        // Serialize concurrent attempts for the same registration token via PostgreSQL transaction-scoped advisory lock
        if (registrationJti != null && !registrationJti.isBlank()) {
            String lockKey = "reg_jti_" + registrationJti.trim();
            jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, lockKey);
            if (tokenProvider.isRegistrationJtiInvalid(registrationJti.trim())) {
                throw new UnauthorizedException("Registration token has already been consumed or invalidated.");
            }
        }

        // Reject activation if an active device already exists for this user
        Optional<UserDevice> currentActive = deviceRepository.findByUserIdAndStatus(userId, DeviceStatus.ACTIVE);
        if (currentActive.isPresent()) {
            throw new ApiException(ErrorCode.DEVICE_CHANGE_NOT_ALLOWED,
                    "An active device is already registered for this user. The active device must be revoked before activating a new device.",
                    HttpStatus.FORBIDDEN);
        }

        UserDevice pendingDevice = deviceRepository.findById(request.getDeviceId())
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));

        if (!pendingDevice.getUserId().equals(userId) || pendingDevice.getStatus() != DeviceStatus.PENDING) {
            throw new BadRequestException("Invalid pending device verification request");
        }

        Instant now = clock.instant();
        if (pendingDevice.getChallengeExpiresAt().isBefore(now)) {
            deviceRepository.delete(pendingDevice);
            throw new BadRequestException("Device challenge has expired. Please re-register.");
        }

        // Verify ECDSA signature
        boolean signatureValid = deviceSignatureVerifier.verifySignature(
                pendingDevice.getPublicKey(),
                pendingDevice.getChallengeHash().getBytes(StandardCharsets.UTF_8),
                request.getChallengeSignature()
        );

        if (!signatureValid) {
            auditService.logDetails(userId, pendingDevice.getCustomerAccountId(), null, pendingDevice.getId(),
                    "DEVICE_VERIFICATION", AuditOutcome.DENIED, "user_device", pendingDevice.getId().toString(),
                    AuditService.details("reason", "Invalid signature"), clientIp);
            throw new ApiException(ErrorCode.INVALID_DEVICE_SIGNATURE, "Device challenge signature verification failed", HttpStatus.BAD_REQUEST);
        }

        pendingDevice.setStatus(DeviceStatus.ACTIVE);
        pendingDevice.setActivatedAt(now);
        pendingDevice.setLastUsedAt(now);
        pendingDevice.setChallengeHash(null);
        pendingDevice.setChallengeExpiresAt(null);
        deviceRepository.save(pendingDevice);

        // Consume registration token atomically within the same database transaction
        if (registrationJti != null && !registrationJti.isBlank()) {
            tokenProvider.invalidateRegistrationJti(registrationJti.trim());
        }

        auditService.logDetails(userId, pendingDevice.getCustomerAccountId(), null, pendingDevice.getId(),
                "DEVICE_VERIFY_SUCCESS", AuditOutcome.SUCCESS, "user_device", pendingDevice.getId().toString(),
                AuditService.details("installationId", pendingDevice.getInstallationId()), clientIp);
    }

    @Override
    @Transactional
    public void revokeDevice(UUID deviceId, Long callerUserId, Role callerRole, String clientIp) {
        UserDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));

        AppUser callerUser = userRepository.findById(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Caller user not found"));
        if (callerUser.getStatus() != com.elecsarthi.enums.UserStatus.ACTIVE) {
            throw new ForbiddenException("Caller account is not active.");
        }
        Role effectiveRole = callerUser.getRole();
        if (callerRole != null && callerRole != effectiveRole) {
            throw new ForbiddenException("Caller role mismatch.");
        }

        // Role-based revocation authorization grounded in authenticated database user & role
        if (effectiveRole == Role.OWNER) {
            // The Owner may revoke any device
        } else if (effectiveRole == Role.SUBOWNER) {
            // Permission matrix: revoking Customer/Subuser devices is Owner-only; a Subowner manages Agents only.
            // Anything else is concealed as not found.
            boolean agentDevice = userRepository.findById(device.getUserId())
                    .map(deviceUser -> deviceUser.getRole() == Role.AGENT)
                    .orElse(false);
            if (!agentDevice) {
                throw new ResourceNotFoundException("Device not found");
            }
        } else if (effectiveRole == Role.AGENT) {
            // Agent can only revoke devices belonging to customers assigned to this agent (or their subusers)
            if (device.getUserId().equals(callerUserId)) {
                throw new ForbiddenException("Agents can only revoke devices for their assigned customers.");
            }
            // A device outside the Agent's own Customers is reported as not found.
            if (device.getCustomerAccountId() == null
                    || !customerAccountRepository.requireById(device.getCustomerAccountId()).isOwnedByAgent(callerUserId)) {
                throw new ResourceNotFoundException("Device not found");
            }
        } else if (effectiveRole == Role.CUSTOMER) {
            // Customer can only revoke subordinate SUBUSER devices within their own customer account
            if (device.getCustomerAccountId() == null || !device.getCustomerAccountId().equals(callerUser.getCustomerAccountId())) {
                throw new ResourceNotFoundException("Device not found");
            }
            AppUser deviceUser = userRepository.findById(device.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("Device user not found"));
            if (deviceUser.getRole() != Role.SUBUSER) {
                throw new ForbiddenException("Customers can only revoke devices belonging to subordinate subusers.");
            }
        } else {
            // SUBUSER cannot revoke any device
            throw new ForbiddenException("Subusers cannot revoke devices.");
        }

        Instant now = clock.instant();
        device.setStatus(DeviceStatus.REVOKED);
        device.setRevokedAt(now);
        device.setRevokedByUserId(callerUserId);
        deviceRepository.save(device);

        sessionRepository.terminateAllActiveByDeviceId(deviceId, SessionStatus.TERMINATED, now, "DEVICE_REVOKED", callerUserId);

        // Cascade-revoke any active offline leases associated with this device
        jdbcTemplate.update("UPDATE offline_lease SET revoked_at = ?, revoked_by_user_id = ? WHERE device_id = ? AND revoked_at IS NULL",
                java.sql.Timestamp.from(now), callerUserId, deviceId);

        auditService.logDetails(callerUserId, device.getCustomerAccountId(), null, device.getId(),
                "DEVICE_REVOKE", AuditOutcome.SUCCESS, "user_device", device.getId().toString(),
                AuditService.details("revokedByRole", effectiveRole.name()), clientIp);
    }

    @Override
    @Transactional
    public void revokeDevice(UUID deviceId, Long revokedByUserId, String clientIp) {
        AppUser caller = userRepository.findById(revokedByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Caller user not found"));
        revokeDevice(deviceId, revokedByUserId, caller.getRole(), clientIp);
    }

    @Override
    @Transactional(noRollbackFor = {ApiException.class})
    public UserDevice validateSignedRequest(UUID deviceId,
                                            String method,
                                            String path,
                                            byte[] body,
                                            long timestampEpochMs,
                                            String nonce,
                                            long counter,
                                            String leaseOrSyncId,
                                            String base64Signature) {
        UserDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));

        if (device.getStatus() != DeviceStatus.ACTIVE) {
            throw new ApiException(ErrorCode.DEVICE_REVOKED, "Device is not in ACTIVE status", HttpStatus.UNAUTHORIZED);
        }

        Instant now = clock.instant();
        long nowMs = now.toEpochMilli();
        long windowMs = appProperties.getSecurity().getDeviceSignatureWindowMinutes() * 60L * 1000L;

        if (Math.abs(nowMs - timestampEpochMs) > windowMs) {
            throw new BadRequestException("Signed request timestamp outside allowed window");
        }

        // Monotonic counter verification
        if (counter <= device.getLastRequestCounter()) {
            throw new BadRequestException("Request counter is not strictly increasing");
        }

        // Nonce uniqueness
        if (nonceRepository.existsByDeviceIdAndNonce(deviceId, nonce)) {
            throw new ApiException(ErrorCode.REPLAYED_NONCE, "Replayed nonce detected", HttpStatus.BAD_REQUEST);
        }

        // Build canonical string to sign
        String bodySha256 = CryptoUtils.sha256Hex(body != null ? body : new byte[0]);
        String payloadToVerify = String.join("\n",
                method.toUpperCase(Locale.ROOT),
                path,
                bodySha256,
                String.valueOf(timestampEpochMs),
                nonce,
                String.valueOf(counter),
                leaseOrSyncId != null ? leaseOrSyncId : ""
        );

        boolean valid = CryptoUtils.verifyEcdsaP256Signature(
                device.getPublicKey(),
                payloadToVerify.getBytes(StandardCharsets.UTF_8),
                base64Signature
        );

        if (!valid) {
            throw new ApiException(ErrorCode.INVALID_DEVICE_SIGNATURE, "Invalid request signature", HttpStatus.UNAUTHORIZED);
        }

        // Update device counter & record nonce
        device.setLastRequestCounter(counter);
        device.setLastUsedAt(now);
        deviceRepository.save(device);

        DeviceNonce deviceNonce = DeviceNonce.builder()
                .deviceId(deviceId)
                .nonce(nonce)
                .createdAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .build();
        nonceRepository.save(deviceNonce);

        return device;
    }
}
