package com.elecsarthi.dto.response;

import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
