package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevokeBoothAssignmentRequest {

    @NotBlank(message = "Revoke reason is required")
    private String revokeReason;
}
