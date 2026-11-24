package com.elecsarthi.mapper;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import com.elecsarthi.dto.response.EntitlementResponse;
import com.elecsarthi.entity.Entitlement;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class EntitlementMapper {

    private final Clock clock;

    public EntitlementResponse toResponse(Entitlement e, String electionTypeCode, String electionTypeName) {
        if (e == null) return null;
        Instant now = clock.instant();
        String remainingFormatted = "Expired";
        if (e.getValidUntil() != null && e.getValidUntil().isAfter(now)) {
            Duration dur = Duration.between(now, e.getValidUntil());
            long days = dur.toDays();
            long hours = dur.toHoursPart();
            remainingFormatted = days + "d " + hours + "h remaining";
        }

        return EntitlementResponse.builder()
                .id(e.getId())
                .customerAccountId(e.getCustomerAccountId())
                .electionTypeId(e.getElectionTypeId())
                .electionTypeCode(electionTypeCode)
                .electionTypeName(electionTypeName)
                .storageTier(e.getStorageTier())
                .criteria(e.getCriteria())
                .criteriaHash(e.getCriteriaHash())
                .scopeLabel(e.getScopeLabel())
                .source(e.getSource())
                .saleLineId(e.getSaleLineId())
                .status(e.getStatus())
                .validFrom(e.getValidFrom())
                .validUntil(e.getValidUntil())
                .remainingFormatted(remainingFormatted)
                .mobileAccess(e.isMobileAccess())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
