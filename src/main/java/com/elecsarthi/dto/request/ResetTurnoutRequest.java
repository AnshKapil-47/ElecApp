package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotNull;
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
public class ResetTurnoutRequest {
    @NotNull(message = "Entitlement ID is required")
    private Long entitlementId;

    private Integer boothNo;
}
