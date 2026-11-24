package com.elecsarthi.dto.request;

import com.elecsarthi.enums.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegisterRequest {

    @NotBlank(message = "Installation ID is required")
    private String installationId;

    @NotBlank(message = "ECDSA P-256 public key (Base64) is required")
    private String publicKey;

    @NotNull(message = "Device platform is required")
    private DevicePlatform platform;

    private String deviceName;
}
