package com.elecsarthi.service;

import com.elecsarthi.dto.request.CreateBoothAssignmentRequest;
import com.elecsarthi.dto.request.CreateSubuserRequest;
import com.elecsarthi.dto.request.RevokeBoothAssignmentRequest;
import com.elecsarthi.dto.response.BoothAssignmentResponse;
import com.elecsarthi.dto.response.SubuserDetailResponse;
import com.elecsarthi.enums.Role;

import java.util.List;

public interface SubuserService {
    SubuserDetailResponse createSubuser(Long customerUserId, Role callerRole, Long customerAccountId, CreateSubuserRequest request);
    BoothAssignmentResponse assignBooth(Long customerUserId, Role callerRole, Long customerAccountId, Long subuserId, CreateBoothAssignmentRequest request);
    BoothAssignmentResponse revokeBoothAssignment(Long callerUserId, Role callerRole, Long customerAccountId, Long assignmentId, RevokeBoothAssignmentRequest request);
    List<SubuserDetailResponse> getSubusers(Long callerUserId, Role callerRole, Long customerAccountId);
    SubuserDetailResponse getSubuserDetail(Long callerUserId, Role callerRole, Long customerAccountId, Long subuserId);
}
