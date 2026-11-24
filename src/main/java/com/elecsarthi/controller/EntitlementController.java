package com.elecsarthi.controller;

import com.elecsarthi.dto.request.DirectEntitlementRequest;
import com.elecsarthi.dto.request.MobileAccessRequest;
import com.elecsarthi.dto.request.RevokeEntitlementRequest;
import com.elecsarthi.dto.response.EntitlementResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.EntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/entitlements")
@RequiredArgsConstructor
@Tag(name = "Entitlements", description = "Customer entitlements, direct grants, and mobile access control")
public class EntitlementController {

    private final EntitlementService entitlementService;

    @PostMapping("/direct")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Create direct entitlement", description = "Allows Owner or Subowner to grant direct access to a Customer")
    public ResponseEntity<EntitlementResponse> createDirectEntitlement(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @Valid @RequestBody DirectEntitlementRequest request) {
        EntitlementResponse response = entitlementService.createDirectEntitlement(principal.getUserId(), principal.getRole(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}/mobile-access")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Toggle mobile access", description = "Allows OWNER only to enable/disable voter mobile number access on an entitlement")
    public ResponseEntity<EntitlementResponse> updateMobileAccess(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long id,
            @RequestBody MobileAccessRequest request) {
        EntitlementResponse response = entitlementService.updateMobileAccess(principal.getUserId(), id, request.isEnabled());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Revoke entitlement", description = "Allows Owner or Subowner to revoke an active entitlement")
    public ResponseEntity<EntitlementResponse> revokeEntitlement(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long id,
            @RequestBody(required = false) RevokeEntitlementRequest request) {
        String reason = (request != null && request.getReason() != null && !request.getReason().isBlank())
                ? request.getReason()
                : "Revoked by " + principal.getRole().name();
        EntitlementResponse response = entitlementService.revokeEntitlement(
                principal.getUserId(), principal.getRole(), id, reason);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get my entitlements", description = "Returns active, future, and expired entitlements for the logged-in Customer")
    public ResponseEntity<List<EntitlementResponse>> getMyEntitlements(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        List<EntitlementResponse> responses = entitlementService.getMyEntitlements(principal.getCustomerAccountId());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/customer/{customerAccountId}")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER', 'AGENT', 'CUSTOMER')")
    @Operation(summary = "Get entitlements for customer", description = "Returns entitlements for a customer. Agents can view own customers only.")
    public ResponseEntity<List<EntitlementResponse>> getCustomerEntitlements(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("customerAccountId") Long customerAccountId) {
        List<EntitlementResponse> responses = entitlementService.getEntitlementsByCustomer(
                principal.getUserId(), principal.getRole(), customerAccountId);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER', 'AGENT', 'CUSTOMER')")
    @Operation(summary = "Get entitlement by ID")
    public ResponseEntity<EntitlementResponse> getEntitlementById(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long id) {
        EntitlementResponse response = entitlementService.getEntitlementById(
                principal.getUserId(), principal.getRole(), id);
        return ResponseEntity.ok(response);
    }
}
