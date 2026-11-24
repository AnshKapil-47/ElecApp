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
public class UnmarkTurnoutRequest {
    @NotNull(message = "Entitlement ID is required")
    private Long entitlementId;

    @NotNull(message = "Voter row ID is required")
    private Long voterRowId;
}
