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
public class VoterSlipFieldResponse {
    private String sourceColumn;
    private String labelEn;
    private String labelMt;
    private Object value;
}
