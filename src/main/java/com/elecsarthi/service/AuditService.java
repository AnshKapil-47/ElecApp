package com.elecsarthi.service;

import com.elecsarthi.enums.AuditOutcome;

import java.util.UUID;

public interface AuditService {
    void log(Long actorUserId,
             Long customerAccountId,
             Long datasetId,
             UUID deviceId,
             String actionType,
             AuditOutcome outcome,
             String targetEntity,
             String targetId,
             String details,
             String ipAddress);
}
