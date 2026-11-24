package com.elecsarthi.service.impl;

import java.time.Clock;
import com.elecsarthi.entity.AuditLog;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.repository.AuditLogRepository;
import com.elecsarthi.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AuditServiceImpl implements AuditService {

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;
    private final TransactionTemplate requiresNewTxTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditServiceImpl(AuditLogRepository auditLogRepository, PlatformTransactionManager transactionManager, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
                .createdAt(clock.instant())
                .build();

        // Security requirement: DENIED audit events must survive caller transaction rollback
        if (outcome == AuditOutcome.DENIED) {
            requiresNewTxTemplate.executeWithoutResult(status -> auditLogRepository.save(auditLog));
        } else {
            auditLogRepository.save(auditLog);
        }
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
                detailsJson = objectMapper.writeValueAsString(jsonbSafe(detailsMap));
            } catch (Exception e) {
                log.warn("Failed to serialize audit details map to JSON", e);
                detailsJson = "{}";
            }
        }
        log(actorUserId, customerAccountId, datasetId, deviceId, actionType, outcome, targetEntity, targetId, detailsJson, ipAddress);
    }

    /**
     * PostgreSQL jsonb cannot store NUL or an unpaired surrogate, and the audit insert would fail (aborting the
     * business operation for a SUCCESS audit). Client-supplied text containing them is stored with U+FFFD instead.
     */
    private static Object jsonbSafe(Object value) {
        if (value instanceof String text) {
            return text.codePoints()
                    .map(cp -> cp == 0 || (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) ? 0xFFFD : cp)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, entry) -> safe.put((String) jsonbSafe(String.valueOf(key)), jsonbSafe(entry)));
            return safe;
        }
        if (value instanceof Collection<?> items) {
            return items.stream().map(AuditServiceImpl::jsonbSafe).toList();
        }
        return value;
    }
}
