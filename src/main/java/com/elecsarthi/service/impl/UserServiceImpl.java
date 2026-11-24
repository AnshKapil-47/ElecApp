package com.elecsarthi.service.impl;

import com.elecsarthi.dto.request.CreateAgentRequest;
import com.elecsarthi.dto.request.CreateSubownerRequest;
import com.elecsarthi.dto.response.UserDetailResponse;
import com.elecsarthi.dto.response.UserSummaryResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.UserSession;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SessionStatus;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final AppUserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Clock clock;

    @Override
    @Transactional
    public UserDetailResponse createSubowner(CreateSubownerRequest request, Long callerUserId) {
        if (userRepository.existsByUsername(request.getUsername().trim())) {
            throw new ConflictException(ErrorCode.CONFLICT, "Username '" + request.getUsername() + "' is already taken.");
        }

        Instant now = clock.instant();
        AppUser subowner = AppUser.builder()
                .username(request.getUsername().trim())
                .fullName(request.getFullName().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.SUBOWNER)
                .status(UserStatus.ACTIVE)
                .createdByUserId(callerUserId)
                .mustChangePassword(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        subowner = userRepository.save(subowner);

        auditService.log(callerUserId, null, null, null, "USER_CREATE", AuditOutcome.SUCCESS,
                "app_user", subowner.getId().toString(), "{\"role\":\"SUBOWNER\",\"username\":\"" + subowner.getUsername() + "\"}", null);

        return mapToDetail(subowner, 0);
    }

    @Override
    @Transactional
    public UserDetailResponse createAgent(CreateAgentRequest request, Long callerUserId) {
        if (userRepository.existsByUsername(request.getUsername().trim())) {
            throw new ConflictException(ErrorCode.CONFLICT, "Username '" + request.getUsername() + "' is already taken.");
        }

        Instant now = clock.instant();
        AppUser agent = AppUser.builder()
                .username(request.getUsername().trim())
                .fullName(request.getFullName().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.AGENT)
                .status(UserStatus.ACTIVE)
                .createdByUserId(callerUserId)
                .mustChangePassword(false)
                .createdAt(now)
                .updatedAt(now)
                .build();

        agent = userRepository.save(agent);

        auditService.log(callerUserId, null, null, null, "USER_CREATE", AuditOutcome.SUCCESS,
                "app_user", agent.getId().toString(), "{\"role\":\"AGENT\",\"username\":\"" + agent.getUsername() + "\"}", null);

        return mapToDetail(agent, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserSummaryResponse> listUsersByRole(Role role, Pageable pageable) {
        return userRepository.findByRole(role, pageable)
                .map(this::mapToSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetailResponse getUser(Long id) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + id));

        int activeSessions = sessionRepository.findByUserIdAndStatus(id, SessionStatus.ACTIVE).size();
        return mapToDetail(user, activeSessions);
    }

    @Override
    @Transactional
    public void updateUserStatus(Long id, UserStatus newStatus, Long callerUserId, Role callerRole) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + id));

        validateCanManageUser(callerRole, user);

        user.setStatus(newStatus);
        user.setUpdatedAt(clock.instant());
        userRepository.save(user);

        if (newStatus == UserStatus.SUSPENDED) {
            terminateUserSessions(id, "USER_SUSPENDED", callerUserId, callerRole);
        }

        auditService.log(callerUserId, user.getCustomerAccountId(), null, null, "USER_STATUS_CHANGE", AuditOutcome.SUCCESS,
                "app_user", user.getId().toString(), "{\"status\":\"" + newStatus + "\"}", null);
    }

    @Override
    @Transactional
    public void resetPassword(Long id, String newPassword, Long callerUserId, Role callerRole) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + id));

        validateCanManageUser(callerRole, user);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setUpdatedAt(clock.instant());
        userRepository.save(user);

        // Terminate existing sessions on password reset
        terminateUserSessions(id, "PASSWORD_RESET", callerUserId, callerRole);

        auditService.log(callerUserId, user.getCustomerAccountId(), null, null, "PASSWORD_RESET", AuditOutcome.SUCCESS,
                "app_user", user.getId().toString(), null, null);
    }

    @Override
    @Transactional
    public void terminateUserSessions(Long id, String reason, Long callerUserId, Role callerRole) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + id));

        validateCanManageUser(callerRole, user);

        Instant now = clock.instant();
        List<UserSession> activeSessions = sessionRepository.findByUserIdAndStatus(id, SessionStatus.ACTIVE);
        for (UserSession s : activeSessions) {
            s.setStatus(SessionStatus.TERMINATED);
            s.setTerminatedAt(now);
            s.setTerminationReason(reason != null ? reason : "ADMIN_TERMINATED");
            s.setTerminatedByUserId(callerUserId);
            sessionRepository.save(s);
        }

        auditService.log(callerUserId, user.getCustomerAccountId(), null, null, "SESSION_TERMINATE", AuditOutcome.SUCCESS,
                "app_user", user.getId().toString(), "{\"count\":" + activeSessions.size() + ",\"reason\":\"" + reason + "\"}", null);
    }

    private void validateCanManageUser(Role callerRole, AppUser targetUser) {
        if (callerRole == Role.OWNER) {
            return; // Owner can manage anyone
        }
        if (callerRole == Role.SUBOWNER) {
            // Subowner can only manage AGENT users
            if (targetUser.getRole() != Role.AGENT) {
                throw new ForbiddenException("Subowners can only manage Agents.");
            }
            return;
        }
        throw new ForbiddenException("You do not have permission to manage this user.");
    }

    private UserSummaryResponse mapToSummary(AppUser u) {
        return UserSummaryResponse.builder()
                .id(u.getId())
                .username(u.getUsername())
                .fullName(u.getFullName())
                .role(u.getRole())
                .status(u.getStatus())
                .createdAt(u.getCreatedAt())
                .build();
    }

    private UserDetailResponse mapToDetail(AppUser u, int activeSessionCount) {
        return UserDetailResponse.builder()
                .id(u.getId())
                .username(u.getUsername())
                .fullName(u.getFullName())
                .role(u.getRole())
                .status(u.getStatus())
                .customerAccountId(u.getCustomerAccountId())
                .createdByUserId(u.getCreatedByUserId())
                .mustChangePassword(u.isMustChangePassword())
                .createdAt(u.getCreatedAt())
                .updatedAt(u.getUpdatedAt())
                .activeSessionCount(activeSessionCount)
                .build();
    }
}
