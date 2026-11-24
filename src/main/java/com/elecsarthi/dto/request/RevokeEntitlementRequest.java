package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevokeEntitlementRequest {

    @NotBlank(message = "Revocation reason is required")
    private String reason;
}
