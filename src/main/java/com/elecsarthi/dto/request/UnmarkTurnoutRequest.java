package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnmarkTurnoutRequest {
    @NotNull(message = "Entitlement ID is required")
    private Long entitlementId;

    @NotNull(message = "Voter row ID is required")
    private Long voterRowId;
}
