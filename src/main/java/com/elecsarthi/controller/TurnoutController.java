package com.elecsarthi.controller;

import com.elecsarthi.dto.request.MarkTurnoutRequest;
import com.elecsarthi.dto.request.ResetTurnoutRequest;
import com.elecsarthi.dto.request.UnmarkTurnoutRequest;
import com.elecsarthi.dto.response.TurnoutResponse;
import com.elecsarthi.dto.response.TurnoutStatisticsResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.TurnoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/turnout")
@RequiredArgsConstructor
@Tag(name = "Turnout", description = "Voter turnout marking, unmarking, reset, and live statistics")
public class TurnoutController {

    private final TurnoutService turnoutService;

    @PostMapping("/mark")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'SUBUSER')")
    @Operation(summary = "Mark voter as voted", description = "Customer or Subuser marks a voter as voted. First mark wins (ON CONFLICT DO NOTHING).")
    public ResponseEntity<TurnoutResponse> markVoted(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @Valid @RequestBody MarkTurnoutRequest request) {
        TurnoutResponse response = turnoutService.markVoted(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/unmark")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Unmark turnout", description = "Customer only (online). Subusers are strictly forbidden from unmarking.")
    public ResponseEntity<Map<String, String>> unmarkVoted(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @Valid @RequestBody UnmarkTurnoutRequest request) {
        turnoutService.unmarkVoted(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), request);
        return ResponseEntity.ok(Map.of("status", "UNMARKED"));
    }

    @PostMapping("/reset")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Reset turnout for area or booth", description = "Customer only. Clears turnout for an entitlement or specific booth.")
    public ResponseEntity<Map<String, String>> resetTurnout(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @Valid @RequestBody ResetTurnoutRequest request) {
        turnoutService.resetTurnout(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), request);
        return ResponseEntity.ok(Map.of("status", "RESET_COMPLETE"));
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'SUBUSER')")
    @Operation(summary = "Get turnout statistics", description = "Live counts and percentages broken down by booth and gender")
    public ResponseEntity<TurnoutStatisticsResponse> getTurnoutStatistics(
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal,
            @RequestParam("entitlementId") Long entitlementId,
            @RequestParam(value = "boothNo", required = false) Integer boothNo) {
        TurnoutStatisticsResponse stats = turnoutService.getTurnoutStatistics(
                principal.getUserId(), principal.getRole(), principal.getCustomerAccountId(), entitlementId, boothNo);
        return ResponseEntity.ok(stats);
    }
}
