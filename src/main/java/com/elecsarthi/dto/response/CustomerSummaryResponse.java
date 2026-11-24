package com.elecsarthi.dto.response;

import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.UserStatus;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerSummaryResponse {
    private Long id;
    private String customerName;
    private String partyOrOrgName;
    private String contactPhone;
    private UserStatus status;
    private ActivationStatus activationStatus;
    private Long owningAgentUserId;
    private String owningAgentName;
    private Instant createdAt;
}
