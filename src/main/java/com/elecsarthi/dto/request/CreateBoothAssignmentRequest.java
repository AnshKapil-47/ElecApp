package com.elecsarthi.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
public class CreateBoothAssignmentRequest {

    @NotNull(message = "Entitlement ID is required")
    private Long entitlementId;

    @NotNull(message = "Booth value is required")
    private Integer boothValue;

    @Min(value = 1, message = "Slot must be 1 or 2")
    @Max(value = 2, message = "Slot must be 1 or 2")
    private short slot;

    @Min(value = 1, message = "Duration must be at least 1 day")
    @Max(value = 7, message = "Assignment cannot exceed 7 days")
    private int durationDays;
}
