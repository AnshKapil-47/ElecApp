package com.elecsarthi.controller;

import com.elecsarthi.dto.response.SalesReportResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Quantity-only sales reports (no pricing, commissions, or monetary values)")
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/sales")
    @PreAuthorize("hasAnyRole('OWNER', 'AGENT')")
    @Operation(summary = "Get sales report", description = "Quantity-only report. Subowners are strictly forbidden. Owners see all or filtered by agent. Agents see own only.")
    public ResponseEntity<SalesReportResponse> getSalesReport(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        SalesReportResponse response = reportService.getSalesReport(principal.getUserId(), principal.getRole(), agentId, from, to);
        return ResponseEntity.ok(response);
    }
}
