package com.elecsarthi.service;

import com.elecsarthi.dto.request.CreateAgentRequest;
import com.elecsarthi.dto.request.CreateSubownerRequest;
import com.elecsarthi.dto.response.UserDetailResponse;
import com.elecsarthi.dto.response.UserSummaryResponse;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    UserDetailResponse createSubowner(CreateSubownerRequest request, Long callerUserId);

    UserDetailResponse createAgent(CreateAgentRequest request, Long callerUserId);

    Page<UserSummaryResponse> listUsersByRole(Role role, Pageable pageable);

    UserDetailResponse getUser(Long id);

    void updateUserStatus(Long id, UserStatus newStatus, Long callerUserId, Role callerRole);

    void resetPassword(Long id, String newPassword, Long callerUserId, Role callerRole);

    void terminateUserSessions(Long id, String reason, Long callerUserId, Role callerRole);
}
