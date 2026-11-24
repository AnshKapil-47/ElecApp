package com.elecsarthi.service;

import com.elecsarthi.dto.request.MarkTurnoutRequest;
import com.elecsarthi.dto.request.ResetTurnoutRequest;
import com.elecsarthi.dto.request.UnmarkTurnoutRequest;
import com.elecsarthi.dto.response.TurnoutResponse;
import com.elecsarthi.dto.response.TurnoutStatisticsResponse;
import com.elecsarthi.enums.Role;

public interface TurnoutService {
    TurnoutResponse markVoted(Long callerUserId, Role callerRole, Long customerAccountId, MarkTurnoutRequest request);
    void unmarkVoted(Long callerUserId, Role callerRole, Long customerAccountId, UnmarkTurnoutRequest request);
    void resetTurnout(Long callerUserId, Role callerRole, Long customerAccountId, ResetTurnoutRequest request);
    TurnoutStatisticsResponse getTurnoutStatistics(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Integer boothNo);
}
