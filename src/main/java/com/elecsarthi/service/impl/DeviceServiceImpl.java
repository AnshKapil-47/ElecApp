package com.elecsarthi.service.impl;

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
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
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
            throw new ApiException(ErrorCode.FORBIDDEN, "Owner and Subowner users use the web application only.", HttpStatus.FORBIDDEN);
        }

        // Check if installation ID was ever revoked for this customer
        Optional<UserDevice> existingByInst = deviceRepository.findByInstallationId(request.getInstallationId());
        if (existingByInst.isPresent() && existingByInst.get().getStatus() == DeviceStatus.REVOKED) {
            throw new ConflictException(ErrorCode.DEVICE_REVOKED, "A revoked device installation cannot be re-registered.");
        }

        // Clean up any stale PENDING registration for this user
        Optional<UserDevice> pendingOpt = deviceRepository.findByUserIdAndStatus(userId, DeviceStatus.PENDING);
        pendingOpt.ifPresent(deviceRepository::delete);

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

        auditService.log(userId, user.getCustomerAccountId(), null, device.getId(),
                "DEVICE_REGISTER_INITIATED", AuditOutcome.SUCCESS, "user_device", device.getId().toString(),
                "{\"installationId\":\"" + request.getInstallationId() + "\"}", clientIp);

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
                throw new ApiException(ErrorCode.UNAUTHORIZED, "Registration token has already been consumed or invalidated.", HttpStatus.UNAUTHORIZED);
            }
        }

        UserDevice pendingDevice = deviceRepository.findById(request.getDeviceId())
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));

        if (!pendingDevice.getUserId().equals(userId) || pendingDevice.getStatus() != DeviceStatus.PENDING) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Invalid pending device verification request", HttpStatus.BAD_REQUEST);
        }

        Instant now = clock.instant();
        if (pendingDevice.getChallengeExpiresAt().isBefore(now)) {
            deviceRepository.delete(pendingDevice);
            throw new ApiException(ErrorCode.BAD_REQUEST, "Device challenge has expired. Please re-register.", HttpStatus.BAD_REQUEST);
        }

        // Verify ECDSA signature
        // Challenge data is the raw challenge bytes hashed, or raw bytes matching the stored hash
        // The signature is verified against the public key
        boolean signatureValid = deviceSignatureVerifier.verifySignature(
                pendingDevice.getPublicKey(),
                pendingDevice.getChallengeHash().getBytes(StandardCharsets.UTF_8),
                request.getChallengeSignature()
        );

        if (!signatureValid) {
            auditService.log(userId, pendingDevice.getCustomerAccountId(), null, pendingDevice.getId(),
                    "DEVICE_VERIFICATION", AuditOutcome.DENIED, "user_device", pendingDevice.getId().toString(),
                    "{\"reason\":\"Invalid signature\"}", clientIp);
            throw new ApiException(ErrorCode.INVALID_DEVICE_SIGNATURE, "Device challenge signature verification failed", HttpStatus.BAD_REQUEST);
        }

        // If user already had an active device, mark it as REPLACED
        Optional<UserDevice> currentActive = deviceRepository.findByUserIdAndStatus(userId, DeviceStatus.ACTIVE);
        if (currentActive.isPresent()) {
            UserDevice oldDevice = currentActive.get();
            oldDevice.setStatus(DeviceStatus.REPLACED);
            oldDevice.setReplacedByDeviceId(pendingDevice.getId());
            deviceRepository.save(oldDevice);

            // Terminate sessions of replaced device
            sessionRepository.terminateAllActiveByDeviceId(oldDevice.getId(), SessionStatus.TERMINATED, now, "DEVICE_REPLACED", userId);
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

        auditService.log(userId, pendingDevice.getCustomerAccountId(), null, pendingDevice.getId(),
                "DEVICE_VERIFY_SUCCESS", AuditOutcome.SUCCESS, "user_device", pendingDevice.getId().toString(),
                "{\"installationId\":\"" + pendingDevice.getInstallationId() + "\"}", clientIp);
    }

    @Override
    @Transactional
    public void revokeDevice(UUID deviceId, Long revokedByUserId, String clientIp) {
        UserDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));

        Instant now = clock.instant();
        device.setStatus(DeviceStatus.REVOKED);
        device.setRevokedAt(now);
        device.setRevokedByUserId(revokedByUserId);
        deviceRepository.save(device);

        sessionRepository.terminateAllActiveByDeviceId(deviceId, SessionStatus.TERMINATED, now, "DEVICE_REVOKED", revokedByUserId);

        // Cascade-revoke any active offline leases associated with this device
        jdbcTemplate.update("UPDATE offline_lease SET revoked_at = ?, revoked_by_user_id = ? WHERE device_id = ? AND revoked_at IS NULL",
                java.sql.Timestamp.from(now), revokedByUserId, deviceId);

        auditService.log(revokedByUserId, device.getCustomerAccountId(), null, device.getId(),
                "DEVICE_REVOKE", AuditOutcome.SUCCESS, "user_device", device.getId().toString(), null, clientIp);
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
            throw new ApiException(ErrorCode.BAD_REQUEST, "Signed request timestamp outside allowed window", HttpStatus.BAD_REQUEST);
        }

        // Monotonic counter verification
        if (counter <= device.getLastRequestCounter()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Request counter is not strictly increasing", HttpStatus.BAD_REQUEST);
        }

        // Nonce uniqueness
        if (nonceRepository.existsByDeviceIdAndNonce(deviceId, nonce)) {
            throw new ApiException(ErrorCode.REPLAYED_NONCE, "Replayed nonce detected", HttpStatus.BAD_REQUEST);
        }

        // Build canonical string to sign
        String bodySha256 = CryptoUtils.sha256Hex(body != null ? body : new byte[0]);
        String payloadToVerify = String.join("\n",
                method.toUpperCase(),
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
