package com.elecsarthi.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncChunkResponse {
    private UUID sessionId;
    private int chunkNo;
    private int totalChunks;
    private List<VoterSummaryResponse> records;
    private String chunkSha256;
    private boolean isLastChunk;
}
