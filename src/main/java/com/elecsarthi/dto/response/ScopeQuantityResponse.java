package com.elecsarthi.dto.response;

import lombok.*;

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
