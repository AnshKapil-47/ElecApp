package com.elecsarthi.dto.response;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReissueCodeResponse {
    private Long customerId;
    private String contactPhone;
    private String activationCode;
    private Instant expiresAt;
}
