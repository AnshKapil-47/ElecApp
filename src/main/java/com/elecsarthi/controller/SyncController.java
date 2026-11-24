package com.elecsarthi.controller;

import com.elecsarthi.dto.request.StartSyncSessionRequest;
import com.elecsarthi.dto.response.SyncChunkResponse;
import com.elecsarthi.dto.response.SyncSessionResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
@Tag(name = "Bulk Sync", description = "Customer offline bulk sync sessions and chunk retrieval (Customer only; Subusers forbidden)")
public class SyncController {

    private final SyncService syncService;

    @PostMapping("/sessions")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Start bulk sync session", description = "Customer mobile app initiates chunked bulk sync for offline storage")
    public ResponseEntity<SyncSessionResponse> startSyncSession(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @Valid @RequestBody StartSyncSessionRequest request) {
        SyncSessionResponse response = syncService.startSyncSession(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), principal.getDeviceId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/sessions/{sessionId}/chunks/{chunkNo}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get sync chunk", description = "Retrieve a chunk of voter records with verification hash")
    public ResponseEntity<SyncChunkResponse> getChunk(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("sessionId") UUID sessionId,
            @PathVariable("chunkNo") int chunkNo) {
        SyncChunkResponse response = syncService.getChunk(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), sessionId, chunkNo);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sessions/{sessionId}/complete")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Complete sync session")
    public ResponseEntity<SyncSessionResponse> completeSyncSession(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("sessionId") UUID sessionId) {
        SyncSessionResponse response = syncService.completeSyncSession(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), sessionId);
        return ResponseEntity.ok(response);
    }
}
