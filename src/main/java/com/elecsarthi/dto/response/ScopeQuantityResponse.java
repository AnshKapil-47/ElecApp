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
public class ScopeQuantityResponse {
    private String scopeLabel;
    private String criteriaHash;
    private long timesSold;
    private long voterCount;
}
