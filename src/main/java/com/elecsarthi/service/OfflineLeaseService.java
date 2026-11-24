package com.elecsarthi.service;

import com.elecsarthi.dto.request.OfflineWritebackRequest;
import com.elecsarthi.dto.request.RequestOfflineLeaseRequest;
import com.elecsarthi.dto.response.OfflineLeaseResponse;
import com.elecsarthi.dto.response.OfflineWritebackResponse;
import com.elecsarthi.enums.Role;

import java.util.UUID;

public interface OfflineLeaseService {
    OfflineLeaseResponse requestLease(Long subuserId, Role callerRole, UUID deviceId, RequestOfflineLeaseRequest request);
    OfflineWritebackResponse processWriteback(Long subuserId, Role callerRole, UUID deviceId, OfflineWritebackRequest request);
}
