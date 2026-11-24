package com.elecsarthi.mapper;

import com.elecsarthi.dto.response.SaleLineResponse;
import com.elecsarthi.dto.response.SaleResponse;
import com.elecsarthi.entity.AgentSale;
import com.elecsarthi.entity.AgentSaleLine;
import com.elecsarthi.enums.DatasetTier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SaleMapper {

    public SaleResponse toResponse(AgentSale sale, String electionTypeCode, List<SaleLineResponse> lines) {
        if (sale == null) return null;
        return SaleResponse.builder()
                .id(sale.getId())
                .soldByUserId(sale.getSoldByUserId())
                .customerAccountId(sale.getCustomerAccountId())
                .electionTypeId(sale.getElectionTypeId())
                .electionTypeCode(electionTypeCode)
                .storageTier(sale.getStorageTier())
                .idempotencyKey(sale.getIdempotencyKey())
                .soldAt(sale.getSoldAt())
                .status(sale.getStatus())
                .voidedAt(sale.getVoidedAt())
                .voidReason(sale.getVoidReason())
                .lines(lines)
                .build();
    }

    public SaleLineResponse toLineResponse(AgentSaleLine line, DatasetTier tier, String scopeLabel, Long entitlementId) {
        if (line == null) return null;
        return SaleLineResponse.builder()
                .id(line.getId())
                .lineNo(line.getLineNo())
                .storageTier(tier)
                .scopeLabel(scopeLabel)
                .criteriaHash(line.getCriteriaHash())
                .voterCountSnapshot(line.getVoterCountSnapshot())
                .durationValue(line.getDurationValue())
                .durationUnit(line.getDurationUnit())
                .validFrom(line.getValidFrom())
                .validUntil(line.getValidUntil())
                .entitlementId(entitlementId)
                .build();
    }
}
