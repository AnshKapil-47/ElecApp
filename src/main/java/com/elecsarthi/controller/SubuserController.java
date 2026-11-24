package com.elecsarthi.controller;

import com.elecsarthi.dto.request.CreateBoothAssignmentRequest;
import com.elecsarthi.dto.request.CreateSubuserRequest;
import com.elecsarthi.dto.request.RevokeBoothAssignmentRequest;
import com.elecsarthi.dto.response.BoothAssignmentResponse;
import com.elecsarthi.dto.response.SubuserDetailResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.SubuserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/subusers")
@RequiredArgsConstructor
@Tag(name = "Subusers", description = "Subuser provisioning and field booth assignments (Customer only)")
public class SubuserController {

    private final SubuserService subuserService;

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Create a subuser", description = "Allows Customer accounts to provision field subusers")
    public ResponseEntity<SubuserDetailResponse> createSubuser(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @Valid @RequestBody CreateSubuserRequest request) {
        SubuserDetailResponse response = subuserService.createSubuser(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OWNER')")
    @Operation(summary = "List subusers", description = "List subusers belonging to caller's customer account (or Owner support)")
    public ResponseEntity<List<SubuserDetailResponse>> getSubusers(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @RequestParam(value = "customerAccountId", required = false) Long targetCustomerAccountId) {
        Long customerId = (principal.getRole() == com.elecsarthi.enums.Role.OWNER && targetCustomerAccountId != null)
                ? targetCustomerAccountId : principal.getCustomerAccountId();
        List<SubuserDetailResponse> list = subuserService.getSubusers(principal.getUserId(), principal.getRole(), customerId);
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OWNER')")
    @Operation(summary = "Get subuser details")
    public ResponseEntity<SubuserDetailResponse> getSubuserDetail(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long id) {
        SubuserDetailResponse response = subuserService.getSubuserDetail(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/booth-assignments")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Assign booth to subuser", description = "Assigns up to 2 active booths (slots 1/2) for max 7 days bounded by entitlement")
    public ResponseEntity<BoothAssignmentResponse> assignBooth(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long subuserId,
            @Valid @RequestBody CreateBoothAssignmentRequest request) {
        BoothAssignmentResponse response = subuserService.assignBooth(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), subuserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/booth-assignments/{id}/revoke")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'OWNER')")
    @Operation(summary = "Revoke booth assignment", description = "Revokes an active booth assignment (Customer or Owner support)")
    public ResponseEntity<BoothAssignmentResponse> revokeBoothAssignment(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long assignmentId,
            @Valid @RequestBody RevokeBoothAssignmentRequest request) {
        BoothAssignmentResponse response = subuserService.revokeBoothAssignment(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), assignmentId, request);
        return ResponseEntity.ok(response);
    }
}
