package com.elecsarthi.dto.response;

import com.elecsarthi.enums.SaleStatus;
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
public class SaleRecordResponse {
    private Long saleId;
    private Instant soldAt;
    private Long agentId;
    private Long customerAccountId;
    private String customerName;
    private String electionTypeCode;
    private String scopeLabel;
    private int voterCount;
    private String duration;
    private Instant validFrom;
    private Instant validUntil;
    private SaleStatus status;
}
