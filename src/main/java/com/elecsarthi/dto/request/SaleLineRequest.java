package com.elecsarthi.dto.request;

import com.elecsarthi.enums.DurationUnit;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleLineRequest {

    @NotEmpty(message = "Criteria cannot be empty")
    private Map<String, Object> criteria;

    @Min(value = 1, message = "Duration must be at least 1")
    private int durationValue;

    @NotNull(message = "Duration unit is required")
    private DurationUnit durationUnit;
}
