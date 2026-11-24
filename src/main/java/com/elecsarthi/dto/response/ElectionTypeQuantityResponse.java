package com.elecsarthi.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ElectionTypeQuantityResponse {
    private String electionTypeCode;
    private long salesCount;
    private long voterCount;
}
