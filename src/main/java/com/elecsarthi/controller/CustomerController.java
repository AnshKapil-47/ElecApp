package com.elecsarthi.controller;

import com.elecsarthi.dto.request.ProvisionCustomerRequest;
import com.elecsarthi.dto.request.ReassignAgentRequest;
import com.elecsarthi.dto.request.SuspendCustomerRequest;
import com.elecsarthi.dto.response.CustomerDetailResponse;
import com.elecsarthi.dto.response.CustomerProvisionResponse;
import com.elecsarthi.dto.response.CustomerSummaryResponse;
import com.elecsarthi.dto.response.ReissueCodeResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
@Tag(name = "Customers", description = "Customer account provisioning, lifecycle and management")
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER', 'AGENT')")
    @Operation(summary = "Provision a new Customer account and issue a 10-char Crockford activation code")
    public ResponseEntity<CustomerProvisionResponse> provision(
            @Valid @RequestBody ProvisionCustomerRequest request,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        CustomerProvisionResponse response = customerService.provisionCustomer(request, principal.getUserId(), principal.getRole());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER', 'AGENT')")
    @Operation(summary = "List customers (scoped: Agents see own, Owner/Subowner see all)")
    public ResponseEntity<Page<CustomerSummaryResponse>> list(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        return ResponseEntity.ok(customerService.listCustomers(pageable, principal.getUserId(), principal.getRole()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER', 'AGENT', 'CUSTOMER')")
    @Operation(summary = "Get customer details (scoped: Agents cannot see other agents' customers)")
    public ResponseEntity<CustomerDetailResponse> getById(
            @PathVariable Long id,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        // If customer role, only allow own account
        if (principal.getRole() == com.elecsarthi.enums.Role.CUSTOMER) {
            if (principal.getCustomerAccountId() == null || !principal.getCustomerAccountId().equals(id)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
        }
        return ResponseEntity.ok(customerService.getCustomer(id, principal.getUserId(), principal.getRole()));
    }

    @PostMapping("/{id}/reissue-activation-code")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER', 'AGENT')")
    @Operation(summary = "Re-issue a fresh 10-char activation code for an unactivated customer")
    public ResponseEntity<ReissueCodeResponse> reissueActivationCode(
            @PathVariable Long id,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        return ResponseEntity.ok(customerService.reissueActivationCode(id, principal.getUserId(), principal.getRole()));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Suspend customer account and immediately terminate all active sessions")
    public ResponseEntity<Void> suspend(
            @PathVariable Long id,
            @Valid @RequestBody SuspendCustomerRequest request,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        customerService.suspendCustomer(id, request.getReason(), principal.getUserId(), principal.getRole());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Reactivate a suspended customer account")
    public ResponseEntity<Void> reactivate(
            @PathVariable Long id,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        customerService.reactivateCustomer(id, principal.getUserId(), principal.getRole());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/reassign-agent")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Reassign customer to a different owning Agent (Owner only)")
    public ResponseEntity<Void> reassignAgent(
            @PathVariable Long id,
            @Valid @RequestBody ReassignAgentRequest request,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        customerService.reassignAgent(id, request.getOwningAgentUserId(), principal.getUserId());
        return ResponseEntity.noContent().build();
    }
}
