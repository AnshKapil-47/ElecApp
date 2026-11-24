package com.elecsarthi.dto.response;

import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.SaleStatus;
import lombok.*;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleResponse {
    private Long id;
    private Long soldByUserId;
    private Long customerAccountId;
    private Short electionTypeId;
    private String electionTypeCode;
    private DatasetTier storageTier;
    private String idempotencyKey;
    private Instant soldAt;
    private SaleStatus status;
    private Instant voidedAt;
    private String voidReason;
    private List<SaleLineResponse> lines;
}
