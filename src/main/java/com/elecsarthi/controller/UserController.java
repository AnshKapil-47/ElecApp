package com.elecsarthi.controller;

import com.elecsarthi.dto.request.CreateAgentRequest;
import com.elecsarthi.dto.request.CreateSubownerRequest;
import com.elecsarthi.dto.request.ResetPasswordRequest;
import com.elecsarthi.dto.request.UserStatusUpdateRequest;
import com.elecsarthi.dto.response.UserDetailResponse;
import com.elecsarthi.dto.response.UserSummaryResponse;
import com.elecsarthi.enums.Role;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.UserService;
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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Platform user management (Subowners, Agents, Owner operations)")
public class UserController {

    private final UserService userService;

    @PostMapping("/subowners")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Create a new Subowner account (Owner only)")
    public ResponseEntity<UserDetailResponse> createSubowner(
            @Valid @RequestBody CreateSubownerRequest request,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createSubowner(request, principal.getUserId()));
    }

    @GetMapping("/subowners")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "List Subowners (Owner only)")
    public ResponseEntity<Page<UserSummaryResponse>> listSubowners(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(userService.listUsersByRole(Role.SUBOWNER, pageable));
    }

    @PostMapping("/agents")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Create a new Agent account (Owner or Subowner)")
    public ResponseEntity<UserDetailResponse> createAgent(
            @Valid @RequestBody CreateAgentRequest request,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createAgent(request, principal.getUserId()));
    }

    @GetMapping("/agents")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "List Agents (Owner or Subowner)")
    public ResponseEntity<Page<UserSummaryResponse>> listAgents(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(userService.listUsersByRole(Role.AGENT, pageable));
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Get user details")
    public ResponseEntity<UserDetailResponse> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUser(id));
    }

    @PatchMapping("/users/{id}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Update user status (Owner can update anyone, Subowner can update Agents only)")
    public ResponseEntity<Void> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UserStatusUpdateRequest request,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        userService.updateUserStatus(id, request.getStatus(), principal.getUserId(), principal.getRole());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Reset user password and terminate existing sessions")
    public ResponseEntity<Void> resetPassword(
            @PathVariable Long id,
            @Valid @RequestBody ResetPasswordRequest request,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        userService.resetPassword(id, request.getNewPassword(), principal.getUserId(), principal.getRole());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/terminate-sessions")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Immediately terminate all active sessions for a user")
    public ResponseEntity<Void> terminateSessions(
            @PathVariable Long id,
            @RequestParam(value = "reason", required = false, defaultValue = "ADMIN_TERMINATED") String reason,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        userService.terminateUserSessions(id, reason, principal.getUserId(), principal.getRole());
        return ResponseEntity.noContent().build();
    }
}
