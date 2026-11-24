package com.elecsarthi.dto.response;

import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
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
public class EntitlementResponse {
    private Long id;
    private Long customerAccountId;
    private Short electionTypeId;
    private String electionTypeCode;
    private String electionTypeName;
    private DatasetTier storageTier;
    private String criteria;
    private String criteriaHash;
    private String scopeLabel;
    private EntitlementSource source;
    private Long saleLineId;
    private EntitlementStatus status;
    private Instant validFrom;
    private Instant validUntil;
    private String remainingFormatted;
    private boolean mobileAccess;
    private Instant createdAt;
}
