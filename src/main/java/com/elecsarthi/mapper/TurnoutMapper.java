package com.elecsarthi.mapper;

import com.elecsarthi.dto.response.BoothTurnoutStat;
import com.elecsarthi.dto.response.GenderTurnoutStat;
import com.elecsarthi.dto.response.TurnoutResponse;
import com.elecsarthi.dto.response.TurnoutStatisticsResponse;
import com.elecsarthi.entity.VoterTurnout;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TurnoutMapper {

    public TurnoutResponse toResponse(VoterTurnout t, boolean voted) {
        if (t == null) return null;
        return TurnoutResponse.builder()
                .customerAccountId(t.getCustomerAccountId())
                .voterRowId(t.getVoterRowId())
                .voted(voted)
                .markedAt(t.getMarkedAt())
                .source(t.getSource())
                .build();
    }

    public TurnoutStatisticsResponse toStatsResponse(
            Long entitlementId,
            String scopeLabel,
            long totalVoters,
            long totalVoted,
            List<GenderTurnoutStat> genderBreakdown,
            List<BoothTurnoutStat> boothBreakdown) {

        double percentage = totalVoters > 0
                ? Math.round(((double) totalVoted / totalVoters) * 10000.0) / 100.0
                : 0.0;

        return TurnoutStatisticsResponse.builder()
                .entitlementId(entitlementId)
                .scopeLabel(scopeLabel)
                .totalVoters(totalVoters)
                .totalVoted(totalVoted)
                .turnoutPercentage(percentage)
                .genderBreakdown(genderBreakdown)
                .boothBreakdown(boothBreakdown)
                .build();
    }
}
