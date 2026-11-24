package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.VoterSearchRequest;
import com.elecsarthi.dto.response.*;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.*;
import com.elecsarthi.security.authorization.VoterAccessAuthorizer;
import com.elecsarthi.security.authorization.VoterScopeAuthorizer;
import com.elecsarthi.security.authorization.VoterScopeAuthorizer.VoterScope;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.VoterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
    private final VoterScopeAuthorizer voterScopeAuthorizer;
    private final AuditService auditService;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initGlobalSettings() {
        try {
            List<String> details = jdbcTemplate.query(
                    "SELECT details FROM audit_log WHERE action_type = 'GLOBAL_MOBILE_TOGGLE' ORDER BY created_at DESC LIMIT 1",
                    (rs, rowNum) -> rs.getString("details")
            );
            if (!details.isEmpty()) {
                JsonNode node = objectMapper.readTree(details.get(0));
                if (node.has("enabled")) {
                    boolean enabled = node.get("enabled").asBoolean();
                    appProperties.setMobileNumbersEnabled(enabled);
                    log.info("Restored global mobileNumbersEnabled setting from audit_log: {}", enabled);
                }
            }
        } catch (Exception e) {
            log.warn("Could not restore global mobile toggle from audit_log: {}", e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VoterSummaryResponse> searchVoters(Long callerUserId, Role callerRole, Long customerAccountId, VoterSearchRequest request) {
        if (request.getEntitlementId() == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Entitlement ID is required", HttpStatus.BAD_REQUEST);
        }

        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, request.getEntitlementId());
        ElectionType electionType = electionTypeRepository.findById(ent.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        VoterScope scope = voterScopeAuthorizer.buildVoterScope(ent, electionType, callerUserId, callerRole, "v");
        String boothColumn = scope.boothColumn();

        int limit = Math.min(Math.max(1, request.getLimit()), 100);
        int page = Math.max(0, request.getPage());
        int offset = page * limit;

        StringBuilder whereClause = new StringBuilder(" WHERE ").append(scope.whereClause());
        List<Object> params = new ArrayList<>(scope.params());

        // User request filters
        if (request.getBooth() != null) {
            if (scope.allowedBooths() != null && !scope.allowedBooths().contains(request.getBooth())) {
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

            Integer boothValue = rs.getObject(boothColumn, Integer.class);

            return VoterSummaryResponse.builder()
                    .id(rs.getLong("id"))
                    .datasetId(rs.getLong("dataset_id"))
                    .voterId(rs.getString("voter_id"))
                    .serialNo(rs.getObject("serial_no", Integer.class))
                    .partNo(rs.getObject("part_no", Integer.class))
                    .boothNo(boothValue != null ? boothValue : rs.getObject("booth_no", Integer.class))
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

        VoterScope scope = voterScopeAuthorizer.buildVoterScope(ent, electionType, callerUserId, callerRole, "v");
        String boothColumn = scope.boothColumn();

        String sql = "SELECT v.*, vt.marked_at AS turnout_marked_at, (vt.marked_at IS NOT NULL) AS is_voted " +
                "FROM voter v " +
                "LEFT JOIN voter_turnout vt ON vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id " +
                "WHERE v.id = ? AND " + scope.whereClause();

        List<Object> selectParams = new ArrayList<>();
        selectParams.add(customerAccountId);
        selectParams.add(voterId);
        selectParams.addAll(scope.params());

        List<VoterDetailResponse> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
            byte[] encMobile = rs.getBytes("mobile_no_enc");
            String mobileStr = processMobileNumber(encMobile, callerRole, ent.isMobileAccess());

            Timestamp turnoutTs = rs.getTimestamp("turnout_marked_at");
            Instant votedAt = (turnoutTs != null) ? turnoutTs.toInstant() : null;

            Integer boothValue = rs.getObject(boothColumn, Integer.class);

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
                    .boothNo(boothValue != null ? boothValue : rs.getObject("booth_no", Integer.class))
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
        }, selectParams.toArray());

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

        VoterScope scope = voterScopeAuthorizer.buildVoterScope(ent, electionType, callerUserId, callerRole, "v");
        String boothColumn = scope.boothColumn();

        // 1. Fetch target voter strictly within caller's geographical entitlement scope
        String voterSql = "SELECT v." + boothColumn + " AS target_booth, v.house_no " +
                "FROM voter v WHERE v.id = ? AND " + scope.whereClause();

        List<Object> targetParams = new ArrayList<>();
        targetParams.add(voterId);
        targetParams.addAll(scope.params());

        List<Map<String, Object>> targetRows = jdbcTemplate.queryForList(voterSql, targetParams.toArray());
        if (targetRows.isEmpty()) {
            throw new ResourceNotFoundException("Target voter not found with ID: " + voterId);
        }

        Map<String, Object> target = targetRows.get(0);
        Integer boothVal = (Integer) target.get("target_booth");
        String houseNo = (String) target.get("house_no");

        if (houseNo == null || houseNo.trim().isBlank()) {
            // Cannot match family without a house number; return empty list
            return Collections.emptyList();
        }

        // 2. Fetch family members matching booth, house_no, and entitlement scope
        String familySql = "SELECT v.*, vt.marked_at AS turnout_marked_at, (vt.marked_at IS NOT NULL) AS is_voted " +
                "FROM voter v " +
                "LEFT JOIN voter_turnout vt ON vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id " +
                "WHERE v.house_no = ? AND v." + boothColumn + " = ? AND " + scope.whereClause() +
                " ORDER BY v.serial_no ASC";

        List<Object> familyParams = new ArrayList<>();
        familyParams.add(customerAccountId);
        familyParams.add(houseNo.trim());
        familyParams.add(boothVal);
        familyParams.addAll(scope.params());

        return jdbcTemplate.query(familySql, (rs, rowNum) -> {
            byte[] encMobile = rs.getBytes("mobile_no_enc");
            String mobileStr = processMobileNumber(encMobile, callerRole, ent.isMobileAccess());

            Timestamp turnoutTs = rs.getTimestamp("turnout_marked_at");
            Instant votedAt = (turnoutTs != null) ? turnoutTs.toInstant() : null;

            Integer boothValue = rs.getObject(boothColumn, Integer.class);

            return VoterSummaryResponse.builder()
                    .id(rs.getLong("id"))
                    .datasetId(rs.getLong("dataset_id"))
                    .voterId(rs.getString("voter_id"))
                    .serialNo(rs.getObject("serial_no", Integer.class))
                    .partNo(rs.getObject("part_no", Integer.class))
                    .boothNo(boothValue != null ? boothValue : rs.getObject("booth_no", Integer.class))
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
        }, familyParams.toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public VoterSlipResponse getVoterSlip(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Long voterId) {
        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, entitlementId);
        ElectionType electionType = electionTypeRepository.findById(ent.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        VoterScope scope = voterScopeAuthorizer.buildVoterScope(ent, electionType, callerUserId, callerRole, "v");
        String boothColumn = scope.boothColumn();

        String sql = "SELECT * FROM voter v WHERE v.id = ? AND " + scope.whereClause();
        List<Object> params = new ArrayList<>();
        params.add(voterId);
        params.addAll(scope.params());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Voter not found with ID: " + voterId);
        }

        Map<String, Object> voterRow = rows.get(0);
        Integer boothValue = (Integer) voterRow.get(boothColumn);

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
                .boothNo(boothValue != null ? boothValue : (Integer) voterRow.get("booth_no"))
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
    @Transactional
    public void updateGlobalMobileNumbersEnabled(Long ownerUserId, Role callerRole, boolean enabled) {
        if (callerRole != Role.OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only OWNER can toggle global mobile numbers setting", HttpStatus.FORBIDDEN);
        }

        // 1. Update memory configuration
        appProperties.setMobileNumbersEnabled(enabled);

        // 2. Audit the toggle to audit_log (Finding 13: persistent, audited)
        auditService.log(ownerUserId, null, null, null,
                "GLOBAL_MOBILE_TOGGLE", AuditOutcome.SUCCESS,
                "system_setting", "mobile_numbers_enabled",
                "{\"enabled\":" + enabled + "}", null);

        // 3. Increment mobile_access_policy_version on all active customer accounts so mobile clients re-evaluate policy
        jdbcTemplate.update("UPDATE customer_account SET mobile_access_policy_version = mobile_access_policy_version + 1 WHERE status = 'ACTIVE'");

        log.info("Global mobileNumbersEnabled updated to {} by owner {}, policy version bumped", enabled, ownerUserId);
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
            // Finding 14: Never serve raw bytes if decryption fails; log warning and return null
            log.warn("Voter mobile number decryption failed: {}", e.getMessage());
            return null;
        }
    }
}
