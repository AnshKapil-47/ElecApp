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

import java.util.ArrayList;
import java.util.Comparator;
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

        // Verify targetField is a valid column for this election type
        ElectionTypeField targetDef = fieldRepository.findByElectionTypeIdAndSourceColumn(electionType.getId(), targetField.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new BadRequestException("Invalid target field: " + targetField));

        String safeTargetColumn = targetDef.getSourceColumn();

        StringBuilder sql = new StringBuilder("SELECT DISTINCT ")
                .append(safeTargetColumn)
                .append(" FROM voter WHERE dataset_id IN (SELECT id FROM dataset WHERE status = 'IMPORTED')");

        List<Object> params = new ArrayList<>();

        if (parentFilters != null && !parentFilters.isEmpty()) {
            for (Map.Entry<String, String> entry : parentFilters.entrySet()) {
                String parentCol = entry.getKey().trim().toLowerCase(Locale.ROOT);
                String parentVal = entry.getValue();

                if (parentVal == null || parentVal.isBlank()) continue;

                // Validate parentCol against election_type_field
                ElectionTypeField parentDef = fieldRepository.findByElectionTypeIdAndSourceColumn(electionType.getId(), parentCol)
                        .orElseThrow(() -> new BadRequestException("Invalid parent field: " + parentCol));

                sql.append(" AND ").append(parentDef.getSourceColumn()).append(" = ?");

                if (parentDef.getValueType() == com.elecsarthi.enums.FieldValueType.INT) {
                    try {
                        params.add(Integer.parseInt(parentVal.trim()));
                    } catch (NumberFormatException e) {
                        throw new BadRequestException("Parent field " + parentCol + " must be an integer");
                    }
                } else {
                    params.add(parentVal.trim());
                }
            }
        }

        sql.append(" AND ").append(safeTargetColumn).append(" IS NOT NULL ORDER BY ").append(safeTargetColumn).append(" ASC LIMIT 500");

        List<Object> distinctValues = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> rs.getObject(1), params.toArray());

        return CascadingFilterOptionResponse.builder()
                .targetField(safeTargetColumn)
                .values(distinctValues)
                .build();
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
