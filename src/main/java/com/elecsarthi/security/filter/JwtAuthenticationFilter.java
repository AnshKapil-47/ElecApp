package com.elecsarthi.security.filter;

import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.entity.UserSession;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SessionStatus;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.security.jwt.JwtTokenProvider;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserSessionRepository sessionRepository;
    private final AppUserRepository userRepository;
    private final CustomerAccountRepository customerAccountRepository;
    private final UserDeviceRepository deviceRepository;
    private final Clock clock;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   UserSessionRepository sessionRepository,
                                   AppUserRepository userRepository,
                                   CustomerAccountRepository customerAccountRepository,
                                   UserDeviceRepository deviceRepository,
                                   Clock clock) {
        this.tokenProvider = tokenProvider;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.customerAccountRepository = customerAccountRepository;
        this.deviceRepository = deviceRepository;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            try {
                Claims claims = tokenProvider.parseClaims(token);
                String sidStr = claims.get("sid", String.class);
                if (sidStr != null) {
                    UUID sessionId = UUID.fromString(sidStr);
                    Instant now = clock.instant();

                    Optional<UserSession> sessionOpt = sessionRepository.findByIdAndStatus(sessionId, SessionStatus.ACTIVE);
                    if (sessionOpt.isPresent()) {
                        UserSession session = sessionOpt.get();

                        // Validate absolute & idle expirations
                        if (session.getAbsoluteExpiresAt().isAfter(now) &&
                            (session.getIdleExpiresAt() == null || session.getIdleExpiresAt().isAfter(now))) {

                            Optional<AppUser> userOpt = userRepository.findById(session.getUserId());
                            if (userOpt.isPresent() && userOpt.get().getStatus() == UserStatus.ACTIVE) {
                                AppUser user = userOpt.get();

                                boolean customerOk = true;
                                if (user.getCustomerAccountId() != null) {
                                    Optional<CustomerAccount> custOpt = customerAccountRepository.findById(user.getCustomerAccountId());
                                    customerOk = custOpt.isPresent() && custOpt.get().getStatus() == UserStatus.ACTIVE;
                                }

                                boolean deviceOk = true;
                                if (session.getDeviceId() != null) {
                                    Optional<UserDevice> devOpt = deviceRepository.findById(session.getDeviceId());
                                    deviceOk = devOpt.isPresent() && devOpt.get().getStatus() == DeviceStatus.ACTIVE;
                                }

                                if (customerOk && deviceOk) {
                                    session.setLastSeenAt(now);
                                    sessionRepository.save(session);

                                    ElecSarthiUserPrincipal principal = new ElecSarthiUserPrincipal(
                                            session.getId(),
                                            user.getId(),
                                            user.getUsername(),
                                            user.getRole(),
                                            user.getCustomerAccountId(),
                                            session.getDeviceId()
                                    );

                                    UsernamePasswordAuthenticationToken authentication =
                                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                                    SecurityContextHolder.getContext().setAuthentication(authentication);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
