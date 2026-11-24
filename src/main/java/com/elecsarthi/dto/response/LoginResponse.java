package com.elecsarthi.dto.response;

import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.Role;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponse {
    private UUID sessionId;
    private Long userId;
    private String username;
    private String fullName;
    private Role role;
    private Long customerAccountId;
    private ClientType clientType;
    private String accessToken;
    private String refreshToken; // Present only for MOBILE clients
    private String registrationToken; // Present when device registration is required
    private boolean deviceRegistrationRequired;
    private Instant expiresAt;
    private boolean mustChangePassword;
}
