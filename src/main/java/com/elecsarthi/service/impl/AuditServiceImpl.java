package com.elecsarthi.service.impl;

import com.elecsarthi.entity.AuditLog;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.repository.AuditLogRepository;
import com.elecsarthi.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AuditServiceImpl implements AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        String safeDetails = details;
        if (safeDetails != null && !safeDetails.isBlank()) {
            try {
                // Verify if it is valid JSON; if not, wrap safely
                objectMapper.readTree(safeDetails);
            } catch (Exception e) {
                try {
                    safeDetails = objectMapper.writeValueAsString(Map.of("message", details));
                } catch (Exception ex) {
                    safeDetails = "{\"message\":\"" + details.replace("\"", "\\\"") + "\"}";
                }
            }
        }

        AuditLog auditLog = AuditLog.builder()
                .actorUserId(actorUserId)
                .customerAccountId(customerAccountId)
                .datasetId(datasetId)
                .deviceId(deviceId)
                .actionType(actionType)
                .outcome(outcome)
                .targetEntity(targetEntity)
                .targetId(targetId)
                .details(safeDetails)
                .ipAddress(ipAddress)
                .createdAt(Instant.now())
                .build();

        auditLogRepository.save(auditLog);
    }

    @Override
    @Transactional
    public void logDetails(Long actorUserId,
                           Long customerAccountId,
                           Long datasetId,
                           UUID deviceId,
                           String actionType,
                           AuditOutcome outcome,
                           String targetEntity,
                           String targetId,
                           Map<String, Object> detailsMap,
                           String ipAddress) {
        String detailsJson = null;
        if (detailsMap != null) {
            try {
                detailsJson = objectMapper.writeValueAsString(detailsMap);
            } catch (Exception e) {
                log.warn("Failed to serialize audit details map to JSON", e);
                detailsJson = "{}";
            }
        }
        log(actorUserId, customerAccountId, datasetId, deviceId, actionType, outcome, targetEntity, targetId, detailsJson, ipAddress);
    }
}
