package com.elecsarthi.dto.response;

import com.elecsarthi.enums.BoothAssignmentStatus;
import lombok.*;

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
