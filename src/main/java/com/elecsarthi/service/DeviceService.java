package com.elecsarthi.service;

import com.elecsarthi.dto.request.DeviceRegisterRequest;
import com.elecsarthi.dto.request.DeviceVerifyRequest;
import com.elecsarthi.dto.response.DeviceRegisterResponse;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.Role;

import java.util.UUID;

public interface DeviceService {
    DeviceRegisterResponse registerDevice(Long userId, DeviceRegisterRequest request, String clientIp);
    void verifyDevice(Long userId, DeviceVerifyRequest request, String clientIp);
    void verifyDevice(Long userId, DeviceVerifyRequest request, String clientIp, String registrationJti);
    void revokeDevice(UUID deviceId, Long revokedByUserId, String clientIp);
    void revokeDevice(UUID deviceId, Long callerUserId, Role callerRole, String clientIp);
    UserDevice validateSignedRequest(UUID deviceId,
                                     String method,
                                     String path,
                                     byte[] body,
                                     long timestampEpochMs,
                                     String nonce,
                                     long counter,
                                     String leaseOrSyncId,
                                     String base64Signature);
}
