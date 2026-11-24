package com.elecsarthi.service;

import com.elecsarthi.dto.request.StartSyncSessionRequest;
import com.elecsarthi.dto.response.SyncChunkResponse;
import com.elecsarthi.dto.response.SyncSessionResponse;
import com.elecsarthi.enums.Role;

import java.util.UUID;

public interface SyncService {
    SyncSessionResponse startSyncSession(Long customerUserId, Role callerRole, Long customerAccountId, UUID deviceId, StartSyncSessionRequest request);
    SyncChunkResponse getChunk(Long customerUserId, Role callerRole, Long customerAccountId, UUID sessionId, int chunkNo);
    SyncSessionResponse completeSyncSession(Long customerUserId, Role callerRole, Long customerAccountId, UUID sessionId);
}
