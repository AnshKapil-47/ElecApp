package com.elecsarthi.security.authorization;

import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Validates tenant isolation between customer accounts, agents, and subusers.
 */
@Slf4j
@Component
public class TenantIsolationAuthorizer {

    /**
     * Asserts that a resource belonging to a specific customer account matches the caller's tenant.
     *
     * @param callerTenantId Caller's customer account ID
     * @param targetTenantId Target resource customer account ID
     */
    public void validateTenantMatch(Long callerTenantId, Long targetTenantId) {
        if (callerTenantId == null || !callerTenantId.equals(targetTenantId)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Access denied: resource belongs to a different customer tenant",
                    HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Asserts that an agent is the owning agent of a customer account.
     *
     * @param agentUserId            Caller's user ID
     * @param customerOwningAgentId Customer's owning agent user ID
     */
    public void validateAgentOwnership(Long agentUserId, Long customerOwningAgentId) {
        if (customerOwningAgentId == null || !customerOwningAgentId.equals(agentUserId)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Access denied: you are not the owning agent for this customer",
                    HttpStatus.FORBIDDEN);
        }
    }
}
