package com.elecsarthi.service;

import com.elecsarthi.enums.AuditOutcome;

import java.util.LinkedHashMap;
import java.util.Map;
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

    void logDetails(Long actorUserId,
                    Long customerAccountId,
                    Long datasetId,
                    UUID deviceId,
                    String actionType,
                    AuditOutcome outcome,
                    String targetEntity,
                    String targetId,
                    Map<String, Object> detailsMap,
                    String ipAddress);

    /**
     * Builds structured audit details from alternating keys and values. Jackson serializes the values, so quotes,
     * backslashes, newlines and Unicode in client-supplied text are escaped, and a null value is kept as JSON null.
     * Details must always be built this way; request data is never concatenated into JSON text.
     */
    static Map<String, Object> details(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Audit details are key/value pairs");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            details.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return details;
    }
}
