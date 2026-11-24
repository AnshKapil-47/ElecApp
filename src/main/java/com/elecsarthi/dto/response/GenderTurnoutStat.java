package com.elecsarthi.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenderTurnoutStat {
    private String gender;
    private long totalVoters;
    private long totalVoted;
    private double turnoutPercentage;
}
