package com.elecsarthi.dto.response;

import com.elecsarthi.enums.SyncStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
