package com.elecsarthi.controller;

import com.elecsarthi.dto.request.OfflineWritebackRequest;
import com.elecsarthi.dto.request.RequestOfflineLeaseRequest;
import com.elecsarthi.dto.response.OfflineLeaseResponse;
import com.elecsarthi.dto.response.OfflineWritebackResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.OfflineLeaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/leases")
@RequiredArgsConstructor
@Tag(name = "Offline Leases", description = "Subuser offline booth leases and offline turnout writeback")
public class OfflineLeaseController {

    private final OfflineLeaseService leaseService;

    @PostMapping("/booth")
    @PreAuthorize("hasRole('SUBUSER')")
    @Operation(summary = "Request offline booth lease", description = "Subusers request cryptographically signed Ed25519 lease for offline booth work (max 24h)")
    public ResponseEntity<OfflineLeaseResponse> requestLease(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @Valid @RequestBody RequestOfflineLeaseRequest request) {
        OfflineLeaseResponse response = leaseService.requestLease(
                principal.getUserId(), principal.getRole(), principal.getDeviceId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/writeback")
    @PreAuthorize("hasRole('SUBUSER')")
    @Operation(summary = "Sync offline turnout marks", description = "Subusers submit offline turnout marks recorded under an active lease")
    public ResponseEntity<OfflineWritebackResponse> processWriteback(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @Valid @RequestBody OfflineWritebackRequest request) {
        OfflineWritebackResponse response = leaseService.processWriteback(
                principal.getUserId(), principal.getRole(), principal.getDeviceId(), request);
        return ResponseEntity.ok(response);
    }
}
