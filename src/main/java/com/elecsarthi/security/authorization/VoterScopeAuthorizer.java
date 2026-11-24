package com.elecsarthi.security.authorization;

import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.ElectionTypeField;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.FieldUsage;
import com.elecsarthi.enums.Role;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.BoothAssignmentRepository;
import com.elecsarthi.repository.ElectionTypeFieldRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class VoterScopeAuthorizer {

    private final ElectionTypeFieldRepository fieldRepository;
    private final BoothAssignmentRepository boothAssignmentRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public record VoterScope(
            String whereClause,
            List<Object> params,
            Set<Integer> allowedBooths,
            String boothColumn,
            Map<String, Object> canonicalCriteria
    ) {}

    public VoterScopeAuthorizer(ElectionTypeFieldRepository fieldRepository,
                                BoothAssignmentRepository boothAssignmentRepository,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.fieldRepository = fieldRepository;
        this.boothAssignmentRepository = boothAssignmentRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Builds fail-closed voter geographical scope predicates.
     * Enforces:
     * 1. Dataset membership: v.dataset_id = ?
     * 2. Canonical entitlement FILTER criteria (fail-closed: missing, empty, or unwhitelisted keys reject immediately).
     * 3. Subuser booth assignment restriction: v.<boothColumn> IN (?, ?).
     */
    public VoterScope buildVoterScope(Entitlement entitlement,
                                      ElectionType electionType,
                                      Long callerUserId,
                                      Role callerRole,
                                      String tableAlias) {
        String prefix = (tableAlias != null && !tableAlias.isBlank()) ? tableAlias + "." : "";
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();

        // 1. Dataset membership
        where.append(prefix).append("dataset_id = ? ");
        params.add(entitlement.getDatasetId());

        // 2. Fail-Closed Entitlement Criteria Validation (Finding 3)
        String criteriaJson = entitlement.getCriteria();
        if (criteriaJson == null || criteriaJson.trim().isBlank()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Entitlement criteria missing or corrupt; access denied.", HttpStatus.FORBIDDEN);
        }

        Map<String, Object> criteria;
        try {
            criteria = objectMapper.readValue(criteriaJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Malformed entitlement criteria JSON; query rejected.", HttpStatus.FORBIDDEN);
        }

        if (criteria == null || criteria.isEmpty()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Empty entitlement criteria; queries cannot run across full dataset.", HttpStatus.FORBIDDEN);
        }

        // Whitelist validation against database metadata for the election type
        List<ElectionTypeField> filterFields = fieldRepository.findByElectionTypeIdAndUsageOrderByFilterOrderAsc(
                electionType.getId(), FieldUsage.FILTER);
        Set<String> allowedFilterColumns = filterFields.stream()
                .map(f -> f.getSourceColumn().toLowerCase())
                .collect(Collectors.toSet());
        if (electionType.getGuardColumn() != null) {
            allowedFilterColumns.add(electionType.getGuardColumn().toLowerCase());
        }

        // Sort keys for deterministic parameter ordering
        List<String> sortedKeys = new ArrayList<>(criteria.keySet());
        Collections.sort(sortedKeys);

        for (String key : sortedKeys) {
            String col = key.trim().toLowerCase();
            if (!allowedFilterColumns.contains(col)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Criteria contains unapproved filter column: " + col, HttpStatus.FORBIDDEN);
            }
            Object val = criteria.get(key);
            if (val == null || (val instanceof String s && s.trim().isEmpty())) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Criteria value for column '" + col + "' cannot be null or empty", HttpStatus.FORBIDDEN);
            }
            where.append(" AND ").append(prefix).append(col).append(" = ? ");
            params.add(val);
        }

        // 3. Subuser booth assignment restriction
        Set<Integer> allowedBooths = null;
        String boothColumn = electionType.getBoothColumn();

        if (callerRole == Role.SUBUSER) {
            Instant now = clock.instant();
            List<BoothAssignment> assignments = boothAssignmentRepository.findActiveAssignmentsForSubuserAndEntitlement(
                    callerUserId, entitlement.getId(), now);
            if (assignments.isEmpty()) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Subuser has no active booth assignment for this entitlement", HttpStatus.FORBIDDEN);
            }
            allowedBooths = assignments.stream()
                    .map(BoothAssignment::getBoothValue)
                    .collect(Collectors.toSet());

            if (allowedBooths.isEmpty()) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Subuser has no valid active booths assigned", HttpStatus.FORBIDDEN);
            }

            where.append(" AND ").append(prefix).append(boothColumn).append(" IN (");
            String placeholders = String.join(",", Collections.nCopies(allowedBooths.size(), "?"));
            where.append(placeholders).append(") ");
            params.addAll(allowedBooths);
        }

        return new VoterScope(where.toString(), params, allowedBooths, boothColumn, criteria);
    }
}
