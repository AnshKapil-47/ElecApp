package com.elecsarthi.service;

import com.elecsarthi.dto.request.DirectEntitlementRequest;
import com.elecsarthi.dto.response.EntitlementResponse;
import com.elecsarthi.enums.Role;

import java.util.List;

public interface EntitlementService {
    EntitlementResponse createDirectEntitlement(Long callerUserId, Role callerRole, DirectEntitlementRequest request);
    EntitlementResponse updateMobileAccess(Long ownerUserId, Long entitlementId, boolean enabled);
    List<EntitlementResponse> getEntitlementsByCustomer(Long callerUserId, Role callerRole, Long customerAccountId);
    List<EntitlementResponse> getMyEntitlements(Long customerAccountId);
    EntitlementResponse getEntitlementById(Long callerUserId, Role callerRole, Long entitlementId);
    EntitlementResponse revokeEntitlement(Long callerUserId, Role callerRole, Long entitlementId, String reason);
}
