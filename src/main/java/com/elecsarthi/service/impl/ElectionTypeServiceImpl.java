package com.elecsarthi.service.impl;

import java.util.Locale;
import com.elecsarthi.dto.response.CascadingFilterOptionResponse;
import com.elecsarthi.dto.response.ElectionTypeFieldResponse;
import com.elecsarthi.dto.response.ElectionTypeResponse;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.ElectionTypeField;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.repository.ElectionTypeFieldRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.service.ElectionTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.elecsarthi.enums.FieldUsage;
import com.elecsarthi.enums.FieldValueType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ElectionTypeServiceImpl implements ElectionTypeService {

    private final ElectionTypeRepository electionTypeRepository;
    private final ElectionTypeFieldRepository fieldRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<ElectionTypeResponse> getAllElectionTypes() {
        return electionTypeRepository.findAll().stream()
                .filter(ElectionType::isActive)
                .sorted(Comparator.comparing(ElectionType::getId))
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ElectionTypeResponse getElectionType(String codeOrId) {
        ElectionType electionType = resolveElectionType(codeOrId);
        ElectionTypeResponse resp = mapToResponse(electionType);
        resp.setFields(getFields(codeOrId));
        return resp;
    }

    @Override
    public List<ElectionTypeFieldResponse> getFields(String codeOrId) {
        ElectionType electionType = resolveElectionType(codeOrId);
        List<ElectionTypeField> fields = fieldRepository.findByElectionTypeIdOrderByDisplayOrderAsc(electionType.getId());
        return fields.stream()
                .map(f -> ElectionTypeFieldResponse.builder()
                        .id(f.getId())
                        .sourceColumn(f.getSourceColumn())
                        .valueType(f.getValueType())
                        .labelEn(f.getLabelEn())
                        .labelMt(f.getLabelMt())
                        .usage(f.getUsage())
                        .filterOrder(f.getFilterOrder())
                        .displayOrder(f.getDisplayOrder())
                        .onSlip(f.isOnSlip())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public CascadingFilterOptionResponse getCascadingFilterOptions(String codeOrId, String targetField, Map<String, String> parentFilters) {
        ElectionType electionType = resolveElectionType(codeOrId);

        // Every column (target and all narrowing filters) is validated against trusted metadata BEFORE any
        // voter SQL exists. Only FILTER columns and the type's guard column are allowed; the SQL identifiers
        // below come from that metadata, never from the request.
        Map<String, FieldValueType> allowed = allowedFilterColumns(electionType);

        String safeTargetColumn = requireAllowedColumn(allowed, targetField);

        record Narrowing(String column, Object value) { }
        List<Narrowing> narrowings = new ArrayList<>();
        if (parentFilters != null) {
            for (Map.Entry<String, String> entry : parentFilters.entrySet()) {
                String parentCol = requireAllowedColumn(allowed, entry.getKey());
                String parentVal = entry.getValue();

                if (parentVal == null || parentVal.isBlank()) continue;

                if (allowed.get(parentCol) == FieldValueType.INT) {
                    try {
                        narrowings.add(new Narrowing(parentCol, Integer.parseInt(parentVal.trim())));
                    } catch (NumberFormatException e) {
                        throw new BadRequestException("A numeric filter value is not a valid integer");
                    }
                } else {
                    narrowings.add(new Narrowing(parentCol, parentVal.trim()));
                }
            }
        }

        StringBuilder sql = new StringBuilder("SELECT DISTINCT ")
                .append(safeTargetColumn)
                .append(" FROM voter WHERE dataset_id IN (SELECT id FROM dataset WHERE status = 'IMPORTED')");
        List<Object> params = new ArrayList<>();
        for (Narrowing n : narrowings) {
            sql.append(" AND ").append(n.column()).append(" = ?");
            params.add(n.value());
        }

        sql.append(" AND ").append(safeTargetColumn).append(" IS NOT NULL ORDER BY ").append(safeTargetColumn).append(" ASC LIMIT 500");

        List<Object> distinctValues = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> rs.getObject(1), params.toArray());

        return CascadingFilterOptionResponse.builder()
                .targetField(safeTargetColumn)
                .values(distinctValues)
                .build();
    }

    /**
     * The only columns the sale-form cascade may read or narrow by: the election type's FILTER fields plus its
     * guard column (which may be a DISPLAY field). Keys are lower-case source columns from trusted metadata.
     */
    private Map<String, FieldValueType> allowedFilterColumns(ElectionType electionType) {
        Map<String, FieldValueType> allowed = new HashMap<>();
        for (ElectionTypeField f : fieldRepository.findByElectionTypeIdAndUsageOrderByFilterOrderAsc(
                electionType.getId(), FieldUsage.FILTER)) {
            allowed.put(f.getSourceColumn().toLowerCase(Locale.ROOT), f.getValueType());
        }
        String guard = electionType.getGuardColumn();
        if (guard != null && !guard.isBlank()) {
            String guardCol = guard.toLowerCase(Locale.ROOT);
            FieldValueType type = fieldRepository.findByElectionTypeIdAndSourceColumn(electionType.getId(), guardCol)
                    .map(ElectionTypeField::getValueType).orElse(FieldValueType.TEXT);
            allowed.putIfAbsent(guardCol, type);
        }
        return allowed;
    }

    /** Returns the metadata-owned column name, or rejects. The message never says whether the column exists. */
    private String requireAllowedColumn(Map<String, FieldValueType> allowed, String requested) {
        String normalized = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        if (!allowed.containsKey(normalized)) {
            throw new BadRequestException("Unsupported filter field");
        }
        // containsKey matched exactly, so this equals a metadata-owned key, never free-form caller text.
        return normalized;
    }

    private ElectionType resolveElectionType(String codeOrId) {
        if (codeOrId == null || codeOrId.isBlank()) {
            throw new BadRequestException("Election type identifier is required.");
        }

        try {
            short id = Short.parseShort(codeOrId);
            return electionTypeRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Election type not found with ID: " + id));
        } catch (NumberFormatException ignored) {
            // Treat as code
            try {
                ElectionTypeCode code = ElectionTypeCode.valueOf(codeOrId.trim().toUpperCase(Locale.ROOT));
                return electionTypeRepository.findByCode(code)
                        .orElseThrow(() -> new ResourceNotFoundException("Election type not found with code: " + code));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown election type: " + codeOrId);
            }
        }
    }

    private ElectionTypeResponse mapToResponse(ElectionType et) {
        return ElectionTypeResponse.builder()
                .id(et.getId())
                .code(et.getCode())
                .displayName(et.getDisplayName())
                .boothColumn(et.getBoothColumn())
                .guardColumn(et.getGuardColumn())
                .active(et.isActive())
                .build();
    }
}
