package com.elecsarthi.dto.response;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfflineLeaseResponse {
    private UUID leaseId;
    private Long boothAssignmentId;
    private Long subuserId;
    private UUID deviceId;
    private long leaseSeq;
    private int boothValue;
    private Long entitlementId;
    private Long datasetId;
    private Instant issuedAt;
    private Instant expiresAt;
    private String payloadJson;
    private String serverSignature;
    private short signingKeyVersion;
}
