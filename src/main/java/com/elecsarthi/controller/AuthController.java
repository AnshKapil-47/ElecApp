package com.elecsarthi.controller;

import com.elecsarthi.dto.request.CustomerActivateRequest;
import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.dto.request.RefreshTokenRequest;
import com.elecsarthi.dto.response.LoginResponse;
import com.elecsarthi.dto.response.TokenRefreshResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Authentication, sessions, token rotation, and account activation APIs")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "User login", description = "Authenticate with credentials and client type (WEB or MOBILE)")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        LoginResponse response = authService.login(request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate refresh token", description = "Exchange mobile refresh token for new access and rotated refresh token")
    public ResponseEntity<TokenRefreshResponse> refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        TokenRefreshResponse response = authService.refreshToken(request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout user", description = "Terminate the active user session")
    public ResponseEntity<Map<String, String>> logout(@AuthenticationPrincipal ElecSarthiUserPrincipal principal, HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        authService.logout(principal, clientIp);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @PostMapping("/activate")
    @Operation(summary = "Activate Customer Account", description = "Activate primary Customer account using Crockford Base32 activation code")
    public ResponseEntity<Map<String, String>> activate(@Valid @RequestBody CustomerActivateRequest request, HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        authService.activateCustomer(request, clientIp);
        return ResponseEntity.ok(Map.of("message", "Customer account activated successfully"));
    }
}
