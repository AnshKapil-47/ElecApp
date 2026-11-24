package com.elecsarthi.security.filter;

import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.DeviceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSignatureFilter extends OncePerRequestFilter {

    private final DeviceService deviceService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ElecSarthiUserPrincipal principal) {
            UUID deviceId = principal.getDeviceId();
            if (deviceId != null && isSignatureRequiredEndpoint(request)) {
                String devIdHeader = request.getHeader("X-Device-Id");
                if (devIdHeader != null && !devIdHeader.isBlank()) {
                    try {
                        UUID presentedDevId = UUID.fromString(devIdHeader.trim());
                        if (!deviceId.equals(presentedDevId)) {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"errorCode\":\"UNAUTHORIZED\",\"message\":\"Device ID header does not match authenticated session device\"}");
                            return;
                        }
                    } catch (IllegalArgumentException e) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"errorCode\":\"UNAUTHORIZED\",\"message\":\"Invalid device ID header format\"}");
                        return;
                    }
                }

                String sig = request.getHeader("X-Device-Signature");
                String tsStr = request.getHeader("X-Device-Timestamp");
                String nonce = request.getHeader("X-Device-Nonce");
                String counterStr = request.getHeader("X-Device-Counter");
                String contextId = request.getHeader("X-Device-Context-Id");

                if (sig == null || tsStr == null || nonce == null || counterStr == null) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"errorCode\":\"INVALID_DEVICE_SIGNATURE\",\"message\":\"Missing required device signature headers\"}");
                    return;
                }

                try {
                    long ts = Long.parseLong(tsStr.trim());
                    long counter = Long.parseLong(counterStr.trim());

                    CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
                    byte[] body = cachedRequest.getCachedBody();

                    deviceService.validateSignedRequest(
                            deviceId,
                            request.getMethod(),
                            request.getRequestURI(),
                            body,
                            ts,
                            nonce,
                            counter,
                            contextId,
                            sig
                    );

                    filterChain.doFilter(cachedRequest, response);
                    return;
                } catch (Exception e) {
                    log.warn("Device signature verification failed for device {}: {}", deviceId, e.getMessage());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    Map<String, String> errMap = Map.of(
                            "errorCode", "INVALID_DEVICE_SIGNATURE",
                            "message", e.getMessage() != null ? e.getMessage() : "Invalid device signature"
                    );
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValue(response.getWriter(), errMap);
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isSignatureRequiredEndpoint(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.contains("/api/v1/sync") ||
                uri.contains("/api/v1/leases") ||
                uri.contains("/api/v1/turnout") ||
                uri.contains("/api/v1/voters") ||
                request.getHeader("X-Device-Signature") != null;
    }
}
