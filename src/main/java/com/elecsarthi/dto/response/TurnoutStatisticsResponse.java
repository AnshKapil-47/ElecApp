package com.elecsarthi.dto.response;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TurnoutStatisticsResponse {
    private Long entitlementId;
    private String scopeLabel;
    private long totalVoters;
    private long totalVoted;
    private double turnoutPercentage;
    private List<BoothTurnoutStat> boothBreakdown;
    private List<GenderTurnoutStat> genderBreakdown;
}
