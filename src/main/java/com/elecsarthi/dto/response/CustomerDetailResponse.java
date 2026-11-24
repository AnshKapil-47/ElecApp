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
public class CustomerDetailResponse {
    private Long id;
    private String customerName;
    private String partyOrOrgName;
    private String contactPhone;
    private String contactEmail;
    private UserStatus status;
    private ActivationStatus activationStatus;
    private Long owningAgentUserId;
    private String owningAgentName;
    private Long createdByUserId;
    private Instant activatedAt;
    private Instant suspendedAt;
    private String suspensionReason;
    private int mobileAccessPolicyVersion;
    private Instant createdAt;
}
