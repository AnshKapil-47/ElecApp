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
public class ElectionTypeQuantityResponse {
    private String electionTypeCode;
    private long salesCount;
    private long voterCount;
}
