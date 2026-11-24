package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceVerifyRequest {

    @NotNull(message = "Device ID is required")
    private UUID deviceId;

    @NotBlank(message = "Base64 signature of challenge is required")
    private String challengeSignature;
}
