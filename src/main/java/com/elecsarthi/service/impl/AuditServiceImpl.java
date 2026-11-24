package com.elecsarthi.service.impl;

import com.elecsarthi.entity.AuditLog;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.repository.AuditLogRepository;
import com.elecsarthi.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuditServiceImpl implements AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditServiceImpl(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    @Transactional
    public void log(Long actorUserId,
                    Long customerAccountId,
                    Long datasetId,
                    UUID deviceId,
                    String actionType,
                    AuditOutcome outcome,
                    String targetEntity,
                    String targetId,
                    String details,
                    String ipAddress) {
        AuditLog auditLog = AuditLog.builder()
                .actorUserId(actorUserId)
                .customerAccountId(customerAccountId)
                .datasetId(datasetId)
                .deviceId(deviceId)
                .actionType(actionType)
                .outcome(outcome)
                .targetEntity(targetEntity)
                .targetId(targetId)
                .details(details)
                .ipAddress(ipAddress)
                .createdAt(Instant.now())
                .build();

        auditLogRepository.save(auditLog);
    }
}
