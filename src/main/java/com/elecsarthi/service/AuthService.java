package com.elecsarthi.service;

import com.elecsarthi.dto.request.CustomerActivateRequest;
import com.elecsarthi.dto.request.LoginRequest;
import com.elecsarthi.dto.request.RefreshTokenRequest;
import com.elecsarthi.dto.response.LoginResponse;
import com.elecsarthi.dto.response.TokenRefreshResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;

public interface AuthService {
    LoginResponse login(LoginRequest request, String clientIp);
    TokenRefreshResponse refreshToken(RefreshTokenRequest request, String clientIp);
    void logout(ElecSarthiUserPrincipal principal, String clientIp);
    void activateCustomer(CustomerActivateRequest request, String clientIp);
}
