package com.elecsarthi.dto.response;

import com.elecsarthi.enums.ActivationStatus;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerProvisionResponse {
    private Long id;
    private String customerName;
    private String contactPhone;
    private ActivationStatus activationStatus;
    private String activationCode;
    private Instant activationCodeExpiresAt;
}
