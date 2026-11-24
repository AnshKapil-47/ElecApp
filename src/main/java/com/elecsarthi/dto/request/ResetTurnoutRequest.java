package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

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
