package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.VoterSearchRequest;
import com.elecsarthi.dto.response.*;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.*;
import com.elecsarthi.security.authorization.VoterAccessAuthorizer;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.VoterService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoterServiceImpl implements VoterService {

    private final EntitlementRepository entitlementRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final ElectionTypeFieldRepository fieldRepository;
    private final BoothAssignmentRepository boothAssignmentRepository;
    private final VoterRepository voterRepository;
    private final VoterAccessAuthorizer voterAccessAuthorizer;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(readOnly = true)
    public Page<VoterSummaryResponse> searchVoters(Long callerUserId, Role callerRole, Long customerAccountId, VoterSearchRequest request) {
        if (request.getEntitlementId() == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Entitlement ID is required", HttpStatus.BAD_REQUEST);
        }

        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, request.getEntitlementId());
        ElectionType electionType = electionTypeRepository.findById(ent.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        Set<Integer> allowedBooths = validateSubuserAndGetAllowedBooths(callerUserId, callerRole, ent.getId());
        String boothColumn = electionType.getBoothColumn();

        int limit = Math.min(Math.max(1, request.getLimit()), 100);
        int page = Math.max(0, request.getPage());
        int offset = page * limit;

        StringBuilder whereClause = new StringBuilder(" WHERE v.dataset_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(ent.getDatasetId());

        // 1. Entitlement scope criteria
        applyEntitlementCriteria(whereClause, params, ent.getCriteria());

        // 2. Subuser booth restrictions
        if (allowedBooths != null) {
            if (allowedBooths.isEmpty()) {
                return new PageImpl<>(Collections.emptyList(), PageRequest.of(page, limit), 0);
            }
            whereClause.append(" AND v.").append(boothColumn).append(" IN (")
                    .append(allowedBooths.stream().map(String::valueOf).collect(Collectors.joining(",")))
                    .append(") ");
        }

        // 3. User request filters
        if (request.getBooth() != null) {
            if (allowedBooths != null && !allowedBooths.contains(request.getBooth())) {
                return new PageImpl<>(Collections.emptyList(), PageRequest.of(page, limit), 0);
            }
            whereClause.append(" AND v.").append(boothColumn).append(" = ? ");
            params.add(request.getBooth());
        }

        if (request.getEpic() != null && !request.getEpic().isBlank()) {
            whereClause.append(" AND UPPER(v.voter_id) = UPPER(?) ");
            params.add(request.getEpic().trim());
        }

        if (request.getName() != null && !request.getName().isBlank()) {
            String nameTerm = "%" + request.getName().trim() + "%";
            whereClause.append(" AND (v.v_fname_en ILIKE ? OR v.v_lname_en ILIKE ? OR (coalesce(v.v_fname_en,'') || ' ' || coalesce(v.v_lname_en,'')) ILIKE ? OR v.v_fname_mt ILIKE ? OR v.v_lname_mt ILIKE ?) ");
            params.add(nameTerm);
            params.add(nameTerm);
            params.add(nameTerm);
            params.add(nameTerm);
            params.add(nameTerm);
        }

        if (request.getSectionNo() != null) {
            whereClause.append(" AND v.section_no = ? ");
            params.add(request.getSectionNo());
        }

        if (request.getGender() != null && !request.getGender().isBlank()) {
            whereClause.append(" AND UPPER(v.sex) = UPPER(?) ");
            params.add(request.getGender().trim());
        }

        if (request.getMinAge() != null) {
            whereClause.append(" AND v.age >= ? ");
            params.add(request.getMinAge());
        }

        if (request.getMaxAge() != null) {
            whereClause.append(" AND v.age <= ? ");
            params.add(request.getMaxAge());
        }

        if (request.getHouseNo() != null && !request.getHouseNo().isBlank()) {
            whereClause.append(" AND v.house_no = ? ");
            params.add(request.getHouseNo().trim());
        }

        if (request.getVoted() != null) {
            if (request.getVoted()) {
                whereClause.append(" AND EXISTS (SELECT 1 FROM voter_turnout vt WHERE vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id) ");
            } else {
                whereClause.append(" AND NOT EXISTS (SELECT 1 FROM voter_turnout vt WHERE vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id) ");
            }
            params.add(customerAccountId);
        }

        // Count query
        String countSql = "SELECT COUNT(*) FROM voter v " + whereClause;
        Long totalCount = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long total = (totalCount != null) ? totalCount : 0L;

        if (total == 0) {
            return new PageImpl<>(Collections.emptyList(), PageRequest.of(page, limit), 0);
        }

        // Data query with LEFT JOIN on turnout
        StringBuilder selectSql = new StringBuilder("SELECT v.*, vt.marked_at AS turnout_marked_at, (vt.marked_at IS NOT NULL) AS is_voted " +
                "FROM voter v " +
                "LEFT JOIN voter_turnout vt ON vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id ");

        List<Object> selectParams = new ArrayList<>();
        selectParams.add(customerAccountId);
        selectParams.addAll(params);

        selectSql.append(whereClause);
        selectSql.append(" ORDER BY v.").append(boothColumn).append(" ASC, v.serial_no ASC LIMIT ? OFFSET ?");
        selectParams.add(limit);
        selectParams.add(offset);

        List<VoterSummaryResponse> voters = jdbcTemplate.query(selectSql.toString(), (rs, rowNum) -> {
            byte[] encMobile = rs.getBytes("mobile_no_enc");
            String mobileStr = processMobileNumber(encMobile, callerRole, ent.isMobileAccess());

            Timestamp turnoutTs = rs.getTimestamp("turnout_marked_at");
            Instant votedAt = (turnoutTs != null) ? turnoutTs.toInstant() : null;

            return VoterSummaryResponse.builder()
                    .id(rs.getLong("id"))
                    .datasetId(rs.getLong("dataset_id"))
                    .voterId(rs.getString("voter_id"))
                    .serialNo(rs.getObject("serial_no", Integer.class))
                    .partNo(rs.getObject("part_no", Integer.class))
                    .boothNo(rs.getObject("booth_no", Integer.class))
                    .firstNameEn(rs.getString("v_fname_en"))
                    .lastNameEn(rs.getString("v_lname_en"))
                    .firstNameMt(rs.getString("v_fname_mt"))
                    .lastNameMt(rs.getString("v_lname_mt"))
                    .relationFirstNameEn(rs.getString("vr_fname_en"))
                    .relationLastNameEn(rs.getString("vr_lname_en"))
                    .houseNo(rs.getString("house_no"))
                    .age(rs.getObject("age", Integer.class))
                    .gender(rs.getString("sex"))
                    .sectionNameEn(rs.getString("section_name_en"))
                    .mobileNumber(mobileStr)
                    .voted(rs.getBoolean("is_voted"))
                    .votedAt(votedAt)
                    .build();
        }, selectParams.toArray());

        return new PageImpl<>(voters, PageRequest.of(page, limit), total);
    }

    @Override
    @Transactional(readOnly = true)
    public VoterDetailResponse getVoterDetail(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Long voterId) {
        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, entitlementId);
        ElectionType electionType = electionTypeRepository.findById(ent.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        Set<Integer> allowedBooths = validateSubuserAndGetAllowedBooths(callerUserId, callerRole, ent.getId());
        String boothColumn = electionType.getBoothColumn();

        String sql = "SELECT v.*, vt.marked_at AS turnout_marked_at, (vt.marked_at IS NOT NULL) AS is_voted " +
                "FROM voter v " +
                "LEFT JOIN voter_turnout vt ON vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id " +
                "WHERE v.dataset_id = ? AND v.id = ?";

        List<VoterDetailResponse> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Integer boothVal = rs.getObject(boothColumn, Integer.class);
            if (allowedBooths != null && (boothVal == null || !allowedBooths.contains(boothVal))) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Access to voter in this booth is not permitted for your assigned booth slots", HttpStatus.FORBIDDEN);
            }

            byte[] encMobile = rs.getBytes("mobile_no_enc");
            String mobileStr = processMobileNumber(encMobile, callerRole, ent.isMobileAccess());

            Timestamp turnoutTs = rs.getTimestamp("turnout_marked_at");
            Instant votedAt = (turnoutTs != null) ? turnoutTs.toInstant() : null;

            return VoterDetailResponse.builder()
                    .id(rs.getLong("id"))
                    .datasetId(rs.getLong("dataset_id"))
                    .voterId(rs.getString("voter_id"))
                    .serialNo(rs.getObject("serial_no", Integer.class))
                    .state(rs.getString("state"))
                    .district(rs.getString("district"))
                    .pcNo(rs.getObject("pc_no", Integer.class))
                    .pcNameEn(rs.getString("pc_name_en"))
                    .acNo(rs.getObject("ac_no", Integer.class))
                    .acNameEn(rs.getString("ac_name_en"))
                    .partNo(rs.getObject("part_no", Integer.class))
                    .boothNo(rs.getObject("booth_no", Integer.class))
                    .boothNameEn(rs.getString("booth_name_en"))
                    .boothAddressEn(rs.getString("booth_address_en"))
                    .firstNameEn(rs.getString("v_fname_en"))
                    .lastNameEn(rs.getString("v_lname_en"))
                    .firstNameMt(rs.getString("v_fname_mt"))
                    .lastNameMt(rs.getString("v_lname_mt"))
                    .relationFirstNameEn(rs.getString("vr_fname_en"))
                    .relationLastNameEn(rs.getString("vr_lname_en"))
                    .houseNo(rs.getString("house_no"))
                    .sectionNo(rs.getObject("section_no", Integer.class))
                    .sectionNameEn(rs.getString("section_name_en"))
                    .age(rs.getObject("age", Integer.class))
                    .gender(rs.getString("sex"))
                    .mobileNumber(mobileStr)
                    .voted(rs.getBoolean("is_voted"))
                    .votedAt(votedAt)
                    .build();
        }, customerAccountId, ent.getDatasetId(), voterId);

        if (results.isEmpty()) {
            throw new ResourceNotFoundException("Voter not found with ID: " + voterId);
        }

        return results.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VoterSummaryResponse> getFamilyMembers(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Long voterId) {
        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, entitlementId);
        ElectionType electionType = electionTypeRepository.findById(ent.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        Set<Integer> allowedBooths = validateSubuserAndGetAllowedBooths(callerUserId, callerRole, ent.getId());
        String boothColumn = electionType.getBoothColumn();

        // 1. Fetch target voter
        String voterSql = "SELECT v." + boothColumn + " AS target_booth, v.house_no " +
                "FROM voter v WHERE v.dataset_id = ? AND v.id = ?";
        List<Map<String, Object>> targetRows = jdbcTemplate.queryForList(voterSql, ent.getDatasetId(), voterId);
        if (targetRows.isEmpty()) {
            throw new ResourceNotFoundException("Target voter not found");
        }

        Map<String, Object> target = targetRows.get(0);
        Integer boothVal = (Integer) target.get("target_booth");
        String houseNo = (String) target.get("house_no");

        if (allowedBooths != null && (boothVal == null || !allowedBooths.contains(boothVal))) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Access to voter in this booth is not permitted", HttpStatus.FORBIDDEN);
        }

        if (houseNo == null || houseNo.trim().isBlank()) {
            // Cannot match family without a house number; return empty list
            return Collections.emptyList();
        }

        String familySql = "SELECT v.*, vt.marked_at AS turnout_marked_at, (vt.marked_at IS NOT NULL) AS is_voted " +
                "FROM voter v " +
                "LEFT JOIN voter_turnout vt ON vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id " +
                "WHERE v.dataset_id = ? AND v." + boothColumn + " = ? AND v.house_no = ? " +
                "ORDER BY v.serial_no ASC";

        return jdbcTemplate.query(familySql, (rs, rowNum) -> {
            byte[] encMobile = rs.getBytes("mobile_no_enc");
            String mobileStr = processMobileNumber(encMobile, callerRole, ent.isMobileAccess());

            Timestamp turnoutTs = rs.getTimestamp("turnout_marked_at");
            Instant votedAt = (turnoutTs != null) ? turnoutTs.toInstant() : null;

            return VoterSummaryResponse.builder()
                    .id(rs.getLong("id"))
                    .datasetId(rs.getLong("dataset_id"))
                    .voterId(rs.getString("voter_id"))
                    .serialNo(rs.getObject("serial_no", Integer.class))
                    .partNo(rs.getObject("part_no", Integer.class))
                    .boothNo(rs.getObject("booth_no", Integer.class))
                    .firstNameEn(rs.getString("v_fname_en"))
                    .lastNameEn(rs.getString("v_lname_en"))
                    .firstNameMt(rs.getString("v_fname_mt"))
                    .lastNameMt(rs.getString("v_lname_mt"))
                    .relationFirstNameEn(rs.getString("vr_fname_en"))
                    .relationLastNameEn(rs.getString("vr_lname_en"))
                    .houseNo(rs.getString("house_no"))
                    .age(rs.getObject("age", Integer.class))
                    .gender(rs.getString("sex"))
                    .sectionNameEn(rs.getString("section_name_en"))
                    .mobileNumber(mobileStr)
                    .voted(rs.getBoolean("is_voted"))
                    .votedAt(votedAt)
                    .build();
        }, customerAccountId, ent.getDatasetId(), boothVal, houseNo);
    }

    @Override
    @Transactional(readOnly = true)
    public VoterSlipResponse getVoterSlip(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Long voterId) {
        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, entitlementId);
        ElectionType electionType = electionTypeRepository.findById(ent.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        Set<Integer> allowedBooths = validateSubuserAndGetAllowedBooths(callerUserId, callerRole, ent.getId());
        String boothColumn = electionType.getBoothColumn();

        String sql = "SELECT * FROM voter WHERE dataset_id = ? AND id = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, ent.getDatasetId(), voterId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Voter not found");
        }

        Map<String, Object> voterRow = rows.get(0);
        Integer boothVal = (Integer) voterRow.get(boothColumn);
        if (allowedBooths != null && (boothVal == null || !allowedBooths.contains(boothVal))) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Access to voter slip in this booth is not permitted", HttpStatus.FORBIDDEN);
        }

        List<ElectionTypeField> onSlipFields = fieldRepository.findByElectionTypeIdAndOnSlipTrueOrderByDisplayOrderAsc(electionType.getId());

        List<VoterSlipFieldResponse> slipFields = onSlipFields.stream()
                .map(f -> {
                    Object val = voterRow.get(f.getSourceColumn());
                    return VoterSlipFieldResponse.builder()
                            .sourceColumn(f.getSourceColumn())
                            .labelEn(f.getLabelEn())
                            .labelMt(f.getLabelMt())
                            .value(val)
                            .build();
                })
                .collect(Collectors.toList());

        String fnameEn = (String) voterRow.get("v_fname_en");
        String lnameEn = (String) voterRow.get("v_lname_en");
        String fullNameEn = ((fnameEn != null ? fnameEn : "") + " " + (lnameEn != null ? lnameEn : "")).trim();

        String fnameMt = (String) voterRow.get("v_fname_mt");
        String lnameMt = (String) voterRow.get("v_lname_mt");
        String fullNameMt = ((fnameMt != null ? fnameMt : "") + " " + (lnameMt != null ? lnameMt : "")).trim();

        String rfnameEn = (String) voterRow.get("vr_fname_en");
        String rlnameEn = (String) voterRow.get("vr_lname_en");
        String relNameEn = ((rfnameEn != null ? rfnameEn : "") + " " + (rlnameEn != null ? rlnameEn : "")).trim();

        return VoterSlipResponse.builder()
                .voterRowId(voterId)
                .voterId((String) voterRow.get("voter_id"))
                .serialNo((Integer) voterRow.get("serial_no"))
                .partNo((Integer) voterRow.get("part_no"))
                .boothNo((Integer) voterRow.get("booth_no"))
                .fullNameEn(fullNameEn)
                .fullNameMt(fullNameMt)
                .relationNameEn(relNameEn)
                .houseNo((String) voterRow.get("house_no"))
                .age((Integer) voterRow.get("age"))
                .gender((String) voterRow.get("sex"))
                .pollingStationEn((String) voterRow.get("booth_name_en"))
                .pollingStationAddressEn((String) voterRow.get("booth_address_en"))
                .slipFields(slipFields)
                .build();
    }

    @Override
    public void updateGlobalMobileNumbersEnabled(Long ownerUserId, Role callerRole, boolean enabled) {
        if (callerRole != Role.OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only OWNER can toggle global mobile numbers setting", HttpStatus.FORBIDDEN);
        }
        appProperties.setMobileNumbersEnabled(enabled);
        log.info("Global mobileNumbersEnabled updated to {} by owner {}", enabled, ownerUserId);
    }

    @Override
    public boolean isGlobalMobileNumbersEnabled() {
        return appProperties.isMobileNumbersEnabled();
    }

    private Entitlement validateAccessAndGetEntitlement(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId) {
        voterAccessAuthorizer.validateVoterAccessRole(callerRole);

        Entitlement ent = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found: " + entitlementId));

        if (!ent.getCustomerAccountId().equals(customerAccountId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Entitlement does not belong to your customer account", HttpStatus.FORBIDDEN);
        }

        if (ent.getStatus() != EntitlementStatus.ACTIVE) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Entitlement is revoked or not active", HttpStatus.FORBIDDEN);
        }

        Instant now = Instant.now();
        if (now.isBefore(ent.getValidFrom()) || !now.isBefore(ent.getValidUntil())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Entitlement has expired or is not yet active", HttpStatus.FORBIDDEN);
        }

        return ent;
    }

    private Set<Integer> validateSubuserAndGetAllowedBooths(Long subuserId, Role role, Long entitlementId) {
        if (role != Role.SUBUSER) {
            return null; // Customer has full access to all booths in entitlement
        }

        Instant now = Instant.now();
        List<BoothAssignment> assignments = boothAssignmentRepository.findActiveAssignmentsForSubuserAndEntitlement(subuserId, entitlementId, now);
        if (assignments.isEmpty()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Subuser has no active booth assignment for this entitlement", HttpStatus.FORBIDDEN);
        }

        return assignments.stream()
                .map(BoothAssignment::getBoothValue)
                .collect(Collectors.toSet());
    }

    private void applyEntitlementCriteria(StringBuilder whereClause, List<Object> params, String criteriaJson) {
        try {
            Map<String, Object> criteria = objectMapper.readValue(criteriaJson, new TypeReference<Map<String, Object>>() {});
            for (Map.Entry<String, Object> entry : criteria.entrySet()) {
                String col = entry.getKey().trim().toLowerCase();
                whereClause.append(" AND v.").append(col).append(" = ? ");
                params.add(entry.getValue());
            }
        } catch (Exception e) {
            log.error("Failed to parse entitlement criteria JSON: {}", criteriaJson, e);
        }
    }

    private String processMobileNumber(byte[] mobileNoEnc, Role callerRole, boolean entitlementMobileAccess) {
        if (mobileNoEnc == null || mobileNoEnc.length == 0) {
            return null;
        }

        boolean canViewUnmasked = voterAccessAuthorizer.isMobileUnmasked(
                appProperties.isMobileNumbersEnabled(), callerRole, entitlementMobileAccess);

        try {
            byte[] key = HexFormat.of().parseHex(appProperties.getSecurity().getVoterMobileAesKeyHex());
            byte[] decrypted = CryptoUtils.aesGcmDecrypt(mobileNoEnc, key);
            String plain = new String(decrypted, StandardCharsets.UTF_8).trim();
            if (canViewUnmasked) {
                return plain;
            } else {
                if (plain.length() >= 4) {
                    return "+91*****" + plain.substring(plain.length() - 4);
                } else {
                    return "+91**********";
                }
            }
        } catch (Exception e) {
            // Mock bytes fallback
            String raw = new String(mobileNoEnc, StandardCharsets.UTF_8);
            if (canViewUnmasked) {
                return raw;
            }
            if (raw.length() >= 4) {
                return "+91*****" + raw.substring(raw.length() - 4);
            }
            return "+91*****XXXX";
        }
    }
}
