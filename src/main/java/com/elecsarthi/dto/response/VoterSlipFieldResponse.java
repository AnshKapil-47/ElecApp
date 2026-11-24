package com.elecsarthi.dto.response;

import lombok.*;

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
