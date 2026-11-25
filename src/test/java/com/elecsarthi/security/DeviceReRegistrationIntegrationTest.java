package com.elecsarthi.security;

import com.elecsarthi.dto.request.DeviceRegisterRequest;
import com.elecsarthi.dto.request.DeviceVerifyRequest;
import com.elecsarthi.dto.response.DeviceRegisterResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.service.DeviceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Agent audit batch 3: a user whose device registration was abandoned (registered, never verified) must be able to
 * register again. Before the fix the stale PENDING row was deleted but the new row was inserted first, which violated
 * ux_device_pending_per_user and answered 409 forever.
 */
class DeviceReRegistrationIntegrationTest extends SecurityIntegrationTestSupport {

    @Autowired private DeviceService deviceService;
    @Autowired private AppUserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private DeviceRegisterRequest request(String installationId, KeyPair keys) {
        return DeviceRegisterRequest.builder()
                .installationId(installationId).platform(DevicePlatform.ANDROID).deviceName("Audit phone")
                .publicKey(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())).build();
    }

    @Test
    @DisplayName("Registering again replaces the abandoned PENDING device (same or new installation id); only the newest challenge verifies")
    void abandonedRegistrationCanBeRetried() throws Exception {
        AppUser agent = userRepository.save(AppUser.builder().username("rr_agent").fullName("RR Agent")
                .passwordHash(passwordEncoder.encode("Passw0rd!xyz")).role(Role.AGENT).status(UserStatus.ACTIVE).build());
        KeyPair keys = generateEcKeyPair();
        String installation = UUID.randomUUID().toString();

        DeviceRegisterResponse first = deviceService.registerDevice(agent.getId(), request(installation, keys), "127.0.0.1");
        // retry with the same installation id (the app crashed before verifying) ...
        DeviceRegisterResponse second = deviceService.registerDevice(agent.getId(), request(installation, keys), "127.0.0.1");
        // ... and once more with a fresh installation id
        DeviceRegisterResponse third = deviceService.registerDevice(agent.getId(), request(UUID.randomUUID().toString(), keys), "127.0.0.1");

        assertEquals(1, deviceRepository.findAll().stream().filter(d -> d.getUserId().equals(agent.getId())).count(),
                "exactly one device row remains");
        assertEquals(DeviceStatus.PENDING, deviceRepository.findById(third.getDeviceId()).orElseThrow().getStatus());
        assertEquals(false, deviceRepository.findById(first.getDeviceId()).isPresent());
        assertEquals(false, deviceRepository.findById(second.getDeviceId()).isPresent());

        // an old registration can no longer be verified
        String oldSignature = signData(keys.getPrivate(), first.getChallenge().getBytes(StandardCharsets.UTF_8));
        assertThrows(ApiException.class, () -> deviceService.verifyDevice(agent.getId(),
                DeviceVerifyRequest.builder().deviceId(first.getDeviceId()).challengeSignature(oldSignature).build(), "127.0.0.1"));
        assertEquals(DeviceStatus.PENDING, deviceRepository.findById(third.getDeviceId()).orElseThrow().getStatus());

        // the newest one verifies and becomes the single ACTIVE device
        String signature = signData(keys.getPrivate(), third.getChallenge().getBytes(StandardCharsets.UTF_8));
        deviceService.verifyDevice(agent.getId(),
                DeviceVerifyRequest.builder().deviceId(third.getDeviceId()).challengeSignature(signature).build(), "127.0.0.1");
        assertEquals(DeviceStatus.ACTIVE, deviceRepository.findById(third.getDeviceId()).orElseThrow().getStatus());
    }
}
