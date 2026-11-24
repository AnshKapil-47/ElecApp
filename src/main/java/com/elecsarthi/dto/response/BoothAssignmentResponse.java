package com.elecsarthi.dto.response;

import com.elecsarthi.enums.BoothAssignmentStatus;
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
public class BoothAssignmentResponse {
    private Long id;
    private Long customerAccountId;
    private Long subuserId;
    private Long entitlementId;
    private int boothValue;
    private short slot;
    private BoothAssignmentStatus status;
    private Instant validFrom;
    private Instant validUntil;
    private Long assignedByUserId;
    private Instant createdAt;
}
