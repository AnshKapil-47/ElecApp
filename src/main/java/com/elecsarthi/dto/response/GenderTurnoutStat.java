package com.elecsarthi.dto.response;

import lombok.*;

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
