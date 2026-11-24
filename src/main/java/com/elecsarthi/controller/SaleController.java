package com.elecsarthi.controller;

import com.elecsarthi.dto.request.CreateSaleRequest;
import com.elecsarthi.dto.request.VoidSaleRequest;
import com.elecsarthi.dto.response.SaleResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.SaleService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sales")
@RequiredArgsConstructor
@Tag(name = "Sales", description = "Agent sales, idempotency, and void operations")
public class SaleController {

    private final SaleService saleService;

    @PostMapping
    @PreAuthorize("hasRole('AGENT')")
    @Operation(summary = "Submit an agent sale", description = "Allows an Agent to sell data areas to their own active Customer accounts")
    public ResponseEntity<SaleResponse> createSale(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @Valid @RequestBody CreateSaleRequest request) {
        SaleResponse response = saleService.createSale(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Void a sale", description = "Allows OWNER only to void an existing sale and revoke its entitlements")
    public ResponseEntity<SaleResponse> voidSale(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long id,
            @Valid @RequestBody VoidSaleRequest request) {
        SaleResponse response = saleService.voidSale(principal.getUserId(), id, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'AGENT')")
    @Operation(summary = "Get sale details", description = "Retrieve a sale by ID. Owners can view any; Agents can view only their own.")
    public ResponseEntity<SaleResponse> getSale(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("id") Long id) {
        SaleResponse response = saleService.getSale(principal.getUserId(), principal.getRole(), id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('AGENT')")
    @Operation(summary = "Get agent's own sales", description = "Returns all sales made by the calling Agent")
    public ResponseEntity<List<SaleResponse>> getMySales(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        List<SaleResponse> responses = saleService.getSalesByAgent(principal.getUserId());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/customer/{customerAccountId}")
    @PreAuthorize("hasAnyRole('OWNER', 'AGENT')")
    @Operation(summary = "Get sales for a customer", description = "Returns sales for a customer account. Agents can only view their own customers.")
    public ResponseEntity<List<SaleResponse>> getCustomerSales(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @PathVariable("customerAccountId") Long customerAccountId) {
        List<SaleResponse> responses = saleService.getSalesByCustomer(principal.getUserId(), principal.getRole(), customerAccountId);
        return ResponseEntity.ok(responses);
    }
}
