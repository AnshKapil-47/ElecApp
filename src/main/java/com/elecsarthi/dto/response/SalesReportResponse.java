package com.elecsarthi.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesReportResponse {
    private Long agentId;
    private long totalSales;
    private long totalLines;
    private long totalVoters;
    private long totalCustomersAcquired;
    private long voidedSalesCount;
    private long voidedLinesCount;
    private List<ElectionTypeQuantityResponse> electionTypeBreakdown;
    private List<ScopeQuantityResponse> scopeBreakdown;
    private List<SaleRecordResponse> records;
}
