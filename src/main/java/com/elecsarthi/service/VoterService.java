package com.elecsarthi.service;

import com.elecsarthi.dto.request.VoterSearchRequest;
import com.elecsarthi.dto.response.VoterDetailResponse;
import com.elecsarthi.dto.response.VoterSlipResponse;
import com.elecsarthi.dto.response.VoterSummaryResponse;
import com.elecsarthi.enums.Role;
import org.springframework.data.domain.Page;

import java.util.List;

public interface VoterService {
    Page<VoterSummaryResponse> searchVoters(Long callerUserId, Role callerRole, Long customerAccountId, VoterSearchRequest request);
    VoterDetailResponse getVoterDetail(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Long voterId);
    List<VoterSummaryResponse> getFamilyMembers(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Long voterId);
    VoterSlipResponse getVoterSlip(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Long voterId);
    void updateGlobalMobileNumbersEnabled(Long ownerUserId, Role callerRole, boolean enabled);
    boolean isGlobalMobileNumbersEnabled();
}
