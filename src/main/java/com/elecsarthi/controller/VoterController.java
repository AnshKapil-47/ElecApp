package com.elecsarthi.controller;

import com.elecsarthi.dto.request.SystemMobileConfigUpdateRequest;
import com.elecsarthi.dto.request.VoterSearchRequest;
import com.elecsarthi.dto.response.VoterDetailResponse;
import com.elecsarthi.dto.response.VoterSlipResponse;
import com.elecsarthi.dto.response.VoterSummaryResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.VoterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/voters")
@RequiredArgsConstructor
@Tag(name = "Voters", description = "Voter search, details, family clustering, and slip generation (Customer & Subuser only)")
public class VoterController {

    private final VoterService voterService;

    @PostMapping("/search")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'SUBUSER')")
    @Operation(summary = "Search voters", description = "Server-paginated search bounded by active entitlement. Subusers are bounded to assigned booths.")
    public ResponseEntity<Page<VoterSummaryResponse>> searchVoters(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @RequestBody VoterSearchRequest request) {
        Page<VoterSummaryResponse> page = voterService.searchVoters(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), request);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'SUBUSER')")
    @Operation(summary = "Get voter details", description = "Retrieve full voter details with display fields and masked/unmasked mobile number")
    public ResponseEntity<VoterDetailResponse> getVoterDetail(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long id,
            @RequestParam("entitlementId") Long entitlementId) {
        VoterDetailResponse response = voterService.getVoterDetail(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), entitlementId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/family")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'SUBUSER')")
    @Operation(summary = "Get family members", description = "Retrieve voters sharing the same booth and non-blank house number")
    public ResponseEntity<List<VoterSummaryResponse>> getFamilyMembers(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long id,
            @RequestParam("entitlementId") Long entitlementId) {
        List<VoterSummaryResponse> family = voterService.getFamilyMembers(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), entitlementId, id);
        return ResponseEntity.ok(family);
    }

    @GetMapping("/{id}/slip")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'SUBUSER')")
    @Operation(summary = "Get voter slip", description = "Generates formatted slip data using on_slip field metadata")
    public ResponseEntity<VoterSlipResponse> getVoterSlip(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long id,
            @RequestParam("entitlementId") Long entitlementId) {
        VoterSlipResponse slip = voterService.getVoterSlip(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), entitlementId, id);
        return ResponseEntity.ok(slip);
    }

    @PutMapping("/system/mobile-numbers")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Toggle global mobile numbers", description = "Allows OWNER only to enable or disable global voter mobile number visibility")
    public ResponseEntity<Map<String, Object>> toggleGlobalMobileNumbers(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @RequestBody SystemMobileConfigUpdateRequest request) {
        voterService.updateGlobalMobileNumbersEnabled(principal.getUserId(), principal.getRole(), request.isEnabled());
        return ResponseEntity.ok(Map.of("mobileNumbersEnabled", request.isEnabled()));
    }

    @GetMapping("/system/mobile-numbers")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Get global mobile numbers setting")
    public ResponseEntity<Map<String, Object>> getGlobalMobileNumbers() {
        return ResponseEntity.ok(Map.of("mobileNumbersEnabled", voterService.isGlobalMobileNumbersEnabled()));
    }
}
