package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevokeBoothAssignmentRequest {

    @NotBlank(message = "Revoke reason is required")
    private String revokeReason;
}
