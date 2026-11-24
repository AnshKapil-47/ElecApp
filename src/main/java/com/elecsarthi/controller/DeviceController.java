package com.elecsarthi.controller;

import com.elecsarthi.dto.request.DeviceRegisterRequest;
import com.elecsarthi.dto.request.DeviceVerifyRequest;
import com.elecsarthi.dto.response.DeviceRegisterResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.DeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "Device Management", description = "ECDSA P-256 device registration, verification, and revocation APIs")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/register")
    @PreAuthorize("hasAnyRole('AGENT', 'CUSTOMER', 'SUBUSER')")
    @Operation(summary = "Initiate device registration", description = "Register phone public key and receive 32-byte cryptographic challenge")
    public ResponseEntity<DeviceRegisterResponse> registerDevice(@AuthenticationPrincipal ElecSarthiUserPrincipal principal,
                                                                 @Valid @RequestBody DeviceRegisterRequest request,
                                                                 HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        DeviceRegisterResponse response = deviceService.registerDevice(principal.getUserId(), request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify")
    @PreAuthorize("hasAnyRole('AGENT', 'CUSTOMER', 'SUBUSER')")
    @Operation(summary = "Verify device challenge", description = "Verify ECDSA P-256 signature over registration challenge to activate device")
    public ResponseEntity<Map<String, String>> verifyDevice(@AuthenticationPrincipal ElecSarthiUserPrincipal principal,
                                                            @Valid @RequestBody DeviceVerifyRequest request,
                                                            HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        deviceService.verifyDevice(principal.getUserId(), request, clientIp);
        return ResponseEntity.ok(Map.of("message", "Device verified and activated successfully"));
    }

    @PostMapping("/{deviceId}/revoke")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Revoke device", description = "Owner-only operation to revoke an active device and terminate its sessions")
    public ResponseEntity<Map<String, String>> revokeDevice(@PathVariable UUID deviceId,
                                                            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
                                                            HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        deviceService.revokeDevice(deviceId, principal.getUserId(), clientIp);
        return ResponseEntity.ok(Map.of("message", "Device revoked successfully"));
    }
}
