package com.elecsarthi.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BoothTurnoutStat {
    private int boothNo;
    private String boothName;
    private long totalVoters;
    private long totalVoted;
    private double turnoutPercentage;
}
