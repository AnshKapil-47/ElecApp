package com.elecsarthi.dto.request;

import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.DurationUnit;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DirectEntitlementRequest {

    @NotNull(message = "Customer account ID is required")
    private Long customerAccountId;

    @NotNull(message = "Election type ID is required")
    private Short electionTypeId;

    @NotNull(message = "Storage tier is required")
    private DatasetTier storageTier;

    @NotEmpty(message = "Criteria cannot be empty")
    private Map<String, Object> criteria;

    @Min(value = 1, message = "Duration must be at least 1")
    private int durationValue;

    @NotNull(message = "Duration unit is required")
    private DurationUnit durationUnit;

    @Builder.Default
    private boolean mobileAccess = false;
}
