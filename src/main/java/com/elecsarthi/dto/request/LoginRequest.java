package com.elecsarthi.dto.request;

import com.elecsarthi.enums.ClientType;
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
public class LoginRequest {

    @NotBlank(message = "Username or contact phone is required")
    private String identifier;

    @NotBlank(message = "Password is required")
    private String password;

    @NotNull(message = "Client type (WEB or MOBILE) is required")
    private ClientType clientType;

    // Required if clientType == MOBILE
    private String installationId;

    // Device proof signature if replacing session on same device
    private String deviceSignature;
}
