package com.elecsarthi.service;

import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.ElectionTypeField;
import com.elecsarthi.enums.ElectionTypeCode;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.repository.ElectionTypeFieldRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class CriteriaNormalizer {

    private final ElectionTypeFieldRepository fieldRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    @Getter
    public static class NormalizedCriteria {
        private final String canonicalJson;
        private final String criteriaHash;
        private final Map<String, Object> criteriaMap;
        private final String scopeLabel;

        public NormalizedCriteria(String canonicalJson, String criteriaHash, Map<String, Object> criteriaMap, String scopeLabel) {
            this.canonicalJson = canonicalJson;
            this.criteriaHash = criteriaHash;
            this.criteriaMap = criteriaMap;
            this.scopeLabel = scopeLabel;
        }
    }

    public NormalizedCriteria normalize(ElectionType electionType, Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Criteria cannot be empty.", HttpStatus.BAD_REQUEST);
        }

        List<ElectionTypeField> filterFields = fieldRepository.findByElectionTypeIdAndUsageOrderByFilterOrderAsc(
                electionType.getId(), com.elecsarthi.enums.FieldUsage.FILTER);

        Set<String> requiredFilterColumns = new HashSet<>();
        Map<String, ElectionTypeField> fieldMap = new HashMap<>();
        for (ElectionTypeField f : filterFields) {
            requiredFilterColumns.add(f.getSourceColumn());
            fieldMap.put(f.getSourceColumn(), f);
        }

        // Also permit guard column if present
        if (electionType.getGuardColumn() != null) {
            fieldRepository.findByElectionTypeIdAndSourceColumn(electionType.getId(), electionType.getGuardColumn())
                    .ifPresent(gf -> fieldMap.put(gf.getSourceColumn(), gf));
        }

        Map<String, Object> normalizedMap = new TreeMap<>(); // sorted lexicographically

        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String colName = entry.getKey().trim().toLowerCase();
            Object rawValue = entry.getValue();

            if (rawValue == null) continue;

            ElectionTypeField fieldDef = fieldMap.get(colName);
            if (fieldDef == null) {
                // Reject invalid column not applicable to this election type's filter criteria
                throw new ApiException(ErrorCode.BAD_REQUEST, "Field '" + colName + "' is not a valid filter field for election type " + electionType.getCode(), HttpStatus.BAD_REQUEST);
            }

            if (fieldDef.getValueType() == com.elecsarthi.enums.FieldValueType.INT) {
                long numVal;
                if (rawValue instanceof Number n) {
                    numVal = n.longValue();
                } else {
                    String str = rawValue.toString().trim();
                    try {
                        numVal = Long.parseLong(str);
                    } catch (NumberFormatException e) {
                        throw new ApiException(ErrorCode.BAD_REQUEST, "Field '" + colName + "' must be an integer.", HttpStatus.BAD_REQUEST);
                    }
                }
                if (numVal <= 0) {
                    throw new ApiException(ErrorCode.BAD_REQUEST, "Field '" + colName + "' must be a positive integer.", HttpStatus.BAD_REQUEST);
                }
                normalizedMap.put(colName, numVal);
            } else {
                String strVal = rawValue.toString();
                String normalizedStr = normalizeText(strVal);
                if (normalizedStr.isEmpty()) {
                    throw new ApiException(ErrorCode.BAD_REQUEST, "Field '" + colName + "' cannot be blank.", HttpStatus.BAD_REQUEST);
                }
                normalizedMap.put(colName, normalizedStr);
            }
        }

        // Verify all required filter fields are present
        for (String reqCol : requiredFilterColumns) {
            if (!normalizedMap.containsKey(reqCol)) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Missing required filter field: " + reqCol, HttpStatus.BAD_REQUEST);
            }
        }

        // RFC 8785 canonical JSON formatting (keys sorted, no spaces between tokens)
        String canonicalJson = buildCanonicalJson(normalizedMap);
        String criteriaHash = CryptoUtils.sha256Hex(canonicalJson);
        String scopeLabel = buildScopeLabel(electionType, normalizedMap);

        return new NormalizedCriteria(canonicalJson, criteriaHash, normalizedMap, scopeLabel);
    }

    public static String normalizeText(String input) {
        if (input == null) return "";
        // 1. Unicode NFC normalization
        String nfc = Normalizer.normalize(input, Normalizer.Form.NFC);
        // 2. Trim and single spaces
        String collapsed = WHITESPACE_PATTERN.matcher(nfc.trim()).replaceAll(" ");
        // 3. Latin upper-case
        return collapsed.toUpperCase(Locale.ROOT);
    }

    public static String buildCanonicalJson(Map<String, Object> sortedMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : sortedMap.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(entry.getKey()).append("\":");
            Object val = entry.getValue();
            if (val instanceof Number) {
                sb.append(val);
            } else {
                sb.append("\"").append(escapeJson(val.toString())).append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch <= '\u001F') {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    public String buildScopeLabel(ElectionType electionType, Map<String, Object> criteria) {
        String typeName = electionType.getDisplayName();
        String state = criteria.getOrDefault("state", "").toString();

        return switch (electionType.getCode()) {
            case LOK_SABHA -> String.format("%s · %s · PC %s · AC %s",
                    typeName, state, criteria.getOrDefault("pc_no", ""), criteria.getOrDefault("ac_no", ""));
            case VIDHAN_SABHA -> String.format("%s · %s · AC %s",
                    typeName, state, criteria.getOrDefault("ac_no", ""));
            case NAGAR_NIGAM -> String.format("%s · %s · %s · Ward %s",
                    typeName, state, criteria.getOrDefault("district", ""), criteria.getOrDefault("nagar_nigam_ward_no", ""));
            case NAGAR_PALIKA -> String.format("%s · %s · %s · Ward %s",
                    typeName, state, criteria.getOrDefault("district", ""), criteria.getOrDefault("nagar_palika_ward_no", ""));
            case NAGAR_PARISHAD -> String.format("%s · %s · %s · Ward %s",
                    typeName, state, criteria.getOrDefault("district", ""), criteria.getOrDefault("nagar_parishad_ward_no", ""));
            case ZILLA_PANCHAYAT -> String.format("%s · %s · %s · Ward %s",
                    typeName, state, criteria.getOrDefault("district", ""), criteria.getOrDefault("zilla_parishad_name_ward_no", ""));
            case PANCHAYAT_SAMITI -> String.format("%s · %s · %s · Ward %s",
                    typeName, state, criteria.getOrDefault("district", ""), criteria.getOrDefault("panchayat_samiti_name_ward_no", ""));
            case PANCHAYAT -> String.format("%s · %s · %s · Panchayat %s",
                    typeName, state, criteria.getOrDefault("district", ""), criteria.getOrDefault("panchayat_no", ""));
        };
    }
}
