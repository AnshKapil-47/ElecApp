package com.elecsarthi.dto.response;

import com.elecsarthi.enums.SyncStatus;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncSessionResponse {
    private UUID sessionId;
    private Long customerAccountId;
    private Long entitlementId;
    private UUID deviceId;
    private int totalRecords;
    private int totalChunks;
    private int chunkSize;
    private boolean includesMobile;
    private SyncStatus status;
    private Instant offlineValidUntil;
    private Instant expiresAt;
    private String manifestSha256;
}
