package com.elecsarthi.security.authorization;

import com.elecsarthi.enums.Role;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Central authorization gate for voter data queries and mobile number masking.
 * Enforces the strict security rule that administrative and agent roles cannot access voter data,
 * and enforces the 3-gate Mobile Number Protection rule.
 */
@Slf4j
@Component
public class VoterAccessAuthorizer {

    /**
     * Enforces the central voter access rule.
     * Only Customer and Subuser roles may query voter data.
     * Owner, Subowner, and Agent roles are strictly forbidden (HTTP 403).
     *
     * @param callerRole The role of the requesting authenticated user
     */
    public void validateVoterAccessRole(Role callerRole) {
        if (callerRole == Role.OWNER || callerRole == Role.SUBOWNER || callerRole == Role.AGENT) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Administrative and Agent roles are not permitted to access voter data",
                    HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Evaluates the 3-gate Mobile Number Protection rule:
     * Mobile numbers are displayed UNMASKED if and only if:
     * 1. Global setting mobileNumbersEnabled == true
     * 2. Caller role is strictly CUSTOMER (Subusers NEVER see unmasked mobile numbers)
     * 3. The customer's entitlement has mobileAccess == true
     *
     * @param globalEnabled          Server-wide mobileNumbersEnabled configuration
     * @param callerRole             Role of the authenticated caller
     * @param entitlementMobileAccess Mobile access flag on the active entitlement
     * @return true if unmasked, false if masked
     */
    public boolean isMobileUnmasked(boolean globalEnabled, Role callerRole, boolean entitlementMobileAccess) {
        return globalEnabled && (callerRole == Role.CUSTOMER) && entitlementMobileAccess;
    }
}
