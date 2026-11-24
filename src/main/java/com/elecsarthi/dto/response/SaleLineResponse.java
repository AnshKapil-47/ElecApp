package com.elecsarthi.dto.response;

import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.DurationUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleLineResponse {
    private Long id;
    private short lineNo;
    private DatasetTier storageTier;
    private String scopeLabel;
    private String criteriaHash;
    private int voterCountSnapshot;
    private int durationValue;
    private DurationUnit durationUnit;
    private Instant validFrom;
    private Instant validUntil;
    private Long entitlementId;
}
