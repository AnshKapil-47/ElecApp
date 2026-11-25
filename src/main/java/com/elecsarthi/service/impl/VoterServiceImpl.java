package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.VoterSearchRequest;
import com.elecsarthi.dto.response.VoterDetailResponse;
import com.elecsarthi.dto.response.VoterSlipFieldResponse;
import com.elecsarthi.dto.response.VoterSlipResponse;
import com.elecsarthi.dto.response.VoterSummaryResponse;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.ElectionTypeField;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.mapper.VoterMapper;
import com.elecsarthi.repository.ElectionTypeFieldRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.security.authorization.VoterAccessAuthorizer;
import com.elecsarthi.security.authorization.VoterScopeAuthorizer.VoterScope;
import com.elecsarthi.security.authorization.VoterScopeAuthorizer;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.VoterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every read of voter data goes through {@link #authorize}: the Customer's entitlement is checked against
 * the database and {@link VoterScopeAuthorizer} supplies the SQL that restricts the query to the entitled area
 * (and, for a Subuser, to the assigned booths). Client-sent ids are only lookup hints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoterServiceImpl implements VoterService {

    private static final String ACTION_GLOBAL_MOBILE_TOGGLE = "GLOBAL_MOBILE_TOGGLE";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private static final String VOTER_WITH_TURNOUT = "SELECT v.*, vt.marked_at AS turnout_marked_at, (vt.marked_at IS NOT NULL) AS is_voted "
            + "FROM voter v "
            + "LEFT JOIN voter_turnout vt ON vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id ";

    private final EntitlementRepository entitlementRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final ElectionTypeFieldRepository fieldRepository;
    private final VoterAccessAuthorizer voterAccessAuthorizer;
    private final VoterScopeAuthorizer voterScopeAuthorizer;
    private final VoterMapper voterMapper;
    private final AuditService auditService;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** An authorized request: the entitlement, its election type and the SQL scope of what it may read. */
    private record Access(Entitlement entitlement, ElectionType electionType, VoterScope scope, boolean mobileUnmasked) {
        String boothColumn() {
            return scope.boothColumn();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VoterSummaryResponse> searchVoters(Long callerUserId, Role callerRole, Long customerAccountId, VoterSearchRequest request) {
        if (request.getEntitlementId() == null) {
            throw new BadRequestException("Entitlement ID is required");
        }
        Access access = authorize(callerUserId, callerRole, customerAccountId, request.getEntitlementId());
        VoterScope scope = access.scope();

        int limit = Math.min(Math.max(1, request.getLimit() != null ? request.getLimit() : DEFAULT_PAGE_SIZE), MAX_PAGE_SIZE);
        int page = Math.max(0, request.getPage() != null ? request.getPage() : 0);
        PageRequest pageRequest = PageRequest.of(page, limit);

        if (request.getBooth() != null && scope.allowedBooths() != null && !scope.allowedBooths().contains(request.getBooth())) {
            return new PageImpl<>(Collections.emptyList(), pageRequest, 0);
        }

        StringBuilder where = new StringBuilder(" WHERE ").append(scope.whereClause());
        List<Object> params = new ArrayList<>(scope.params());
        appendSearchFilters(request, access.boothColumn(), customerAccountId, where, params);

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voter v " + where, Long.class, params.toArray());
        if (total == null || total == 0) {
            return new PageImpl<>(Collections.emptyList(), pageRequest, 0);
        }

        List<Object> selectParams = new ArrayList<>();
        selectParams.add(customerAccountId);
        selectParams.addAll(params);
        selectParams.add(limit);
        selectParams.add((long) page * limit);
        String sql = VOTER_WITH_TURNOUT + where
                + " ORDER BY v." + access.boothColumn() + " ASC, v.serial_no ASC LIMIT ? OFFSET ?";
        List<VoterSummaryResponse> voters = jdbcTemplate.query(sql,
                voterMapper.summaryRowMapper(access.boothColumn(), access.mobileUnmasked(), access.electionType().getId()), selectParams.toArray());
        return new PageImpl<>(voters, pageRequest, total);
    }

    /** Adds the user's optional filters; column names are fixed here or come from the scope, values are bind parameters. */
    private static void appendSearchFilters(VoterSearchRequest request, String boothColumn, Long customerAccountId,
                                            StringBuilder where, List<Object> params) {
        if (request.getBooth() != null) {
            where.append(" AND v.").append(boothColumn).append(" = ? ");
            params.add(request.getBooth());
        }
        if (hasText(request.getEpic())) {
            where.append(" AND UPPER(v.voter_id) = UPPER(?) ");
            params.add(request.getEpic().trim());
        }
        if (hasText(request.getName())) {
            where.append(" AND (v.v_fname_en ILIKE ? OR v.v_lname_en ILIKE ? "
                    + "OR (coalesce(v.v_fname_en,'') || ' ' || coalesce(v.v_lname_en,'')) ILIKE ? "
                    + "OR v.v_fname_mt ILIKE ? OR v.v_lname_mt ILIKE ?) ");
            params.addAll(Collections.nCopies(5, "%" + request.getName().trim() + "%"));
        }
        if (request.getSectionNo() != null) {
            where.append(" AND v.section_no = ? ");
            params.add(request.getSectionNo());
        }
        if (hasText(request.getGender())) {
            where.append(" AND UPPER(v.sex) = UPPER(?) ");
            params.add(request.getGender().trim());
        }
        if (request.getMinAge() != null) {
            where.append(" AND v.age >= ? ");
            params.add(request.getMinAge());
        }
        if (request.getMaxAge() != null) {
            where.append(" AND v.age <= ? ");
            params.add(request.getMaxAge());
        }
        if (hasText(request.getHouseNo())) {
            where.append(" AND v.house_no = ? ");
            params.add(request.getHouseNo().trim());
        }
        if (request.getVoted() != null) {
            where.append(request.getVoted() ? " AND EXISTS " : " AND NOT EXISTS ")
                    .append("(SELECT 1 FROM voter_turnout vt WHERE vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id) ");
            params.add(customerAccountId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public VoterDetailResponse getVoterDetail(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Long voterId) {
        Access access = authorize(callerUserId, callerRole, customerAccountId, entitlementId);

        List<Object> params = new ArrayList<>();
        params.add(customerAccountId);
        params.add(voterId);
        params.addAll(access.scope().params());
        List<VoterDetailResponse> found = jdbcTemplate.query(
                VOTER_WITH_TURNOUT + "WHERE v.id = ? AND " + access.scope().whereClause(),
                voterMapper.detailRowMapper(access.boothColumn(), access.mobileUnmasked(), access.electionType().getId()), params.toArray());
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Voter not found with ID: " + voterId);
        }
        return found.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VoterSummaryResponse> getFamilyMembers(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Long voterId) {
        Access access = authorize(callerUserId, callerRole, customerAccountId, entitlementId);
        String boothColumn = access.boothColumn();

        // The target voter must itself lie inside the caller's scope; family is then matched on booth and house number.
        List<Object> targetParams = new ArrayList<>();
        targetParams.add(voterId);
        targetParams.addAll(access.scope().params());
        List<Map<String, Object>> targetRows = jdbcTemplate.queryForList(
                "SELECT v." + boothColumn + " AS target_booth, v.house_no FROM voter v WHERE v.id = ? AND " + access.scope().whereClause(),
                targetParams.toArray());
        if (targetRows.isEmpty()) {
            throw new ResourceNotFoundException("Target voter not found with ID: " + voterId);
        }
        String houseNo = (String) targetRows.get(0).get("house_no");
        if (houseNo == null || houseNo.isBlank()) {
            return Collections.emptyList();
        }

        List<Object> familyParams = new ArrayList<>();
        familyParams.add(customerAccountId);
        familyParams.add(houseNo.trim());
        familyParams.add(targetRows.get(0).get("target_booth"));
        familyParams.addAll(access.scope().params());
        return jdbcTemplate.query(
                VOTER_WITH_TURNOUT + "WHERE v.house_no = ? AND v." + boothColumn + " = ? AND " + access.scope().whereClause()
                        + " ORDER BY v.serial_no ASC",
                voterMapper.summaryRowMapper(boothColumn, access.mobileUnmasked(), access.electionType().getId()), familyParams.toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public VoterSlipResponse getVoterSlip(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Long voterId) {
        Access access = authorize(callerUserId, callerRole, customerAccountId, entitlementId);

        List<Object> params = new ArrayList<>();
        params.add(voterId);
        params.addAll(access.scope().params());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM voter v WHERE v.id = ? AND " + access.scope().whereClause(), params.toArray());
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Voter not found with ID: " + voterId);
        }
        Map<String, Object> voter = rows.get(0);

        List<ElectionTypeField> slipConfig = fieldRepository
                .findByElectionTypeIdAndOnSlipTrueOrderByDisplayOrderAsc(access.electionType().getId());
        List<VoterSlipFieldResponse> slipFields = slipConfig.stream()
                .map((ElectionTypeField field) -> VoterSlipFieldResponse.builder()
                        .sourceColumn(field.getSourceColumn())
                        .labelEn(field.getLabelEn())
                        .labelMt(field.getLabelMt())
                        .value(voter.get(field.getSourceColumn()))
                        .build())
                .toList();
        // The fixed header of the slip shows only what this election type puts on a slip.
        Map<String, Object> onSlip = new java.util.HashMap<>();
        slipConfig.forEach(field -> onSlip.put(field.getSourceColumn(), voter.get(field.getSourceColumn())));

        Integer boothValue = (Integer) voter.get(access.boothColumn());
        return VoterSlipResponse.builder()
                .voterRowId(voterId)
                .voterId((String) onSlip.get("voter_id"))
                .serialNo((Integer) onSlip.get("serial_no"))
                .partNo((Integer) onSlip.get("part_no"))
                .boothNo(boothValue != null ? boothValue : (Integer) voter.get("booth_no"))
                .fullNameEn(fullName(onSlip.get("v_fname_en"), onSlip.get("v_lname_en")))
                .fullNameMt(fullName(onSlip.get("v_fname_mt"), onSlip.get("v_lname_mt")))
                .relationNameEn(fullName(onSlip.get("vr_fname_en"), onSlip.get("vr_lname_en")))
                .houseNo((String) onSlip.get("house_no"))
                .age((Integer) onSlip.get("age"))
                .gender((String) onSlip.get("sex"))
                .pollingStationEn((String) onSlip.get("booth_name_en"))
                .pollingStationAddressEn((String) onSlip.get("booth_address_en"))
                .slipFields(slipFields)
                .build();
    }

    @Override
    @Transactional
    public void updateGlobalMobileNumbersEnabled(Long ownerUserId, Role callerRole, boolean enabled) {
        if (callerRole != Role.OWNER) {
            throw new ForbiddenException("Only OWNER can toggle global mobile numbers setting");
        }
        // The switch is the latest GLOBAL_MOBILE_TOGGLE audit row (see isGlobalMobileNumbersEnabled).
        auditService.logDetails(ownerUserId, null, null, null, ACTION_GLOBAL_MOBILE_TOGGLE, AuditOutcome.SUCCESS,
                "system_setting", "mobile_numbers_enabled", Map.of("enabled", enabled), null);

        // Apps re-check their mobile-number access when the policy version changes.
        jdbcTemplate.update("UPDATE customer_account SET mobile_access_policy_version = mobile_access_policy_version + 1 WHERE status = 'ACTIVE'");
        log.info("Global mobileNumbersEnabled updated to {} by owner {}, policy version bumped", enabled, ownerUserId);
    }

    /**
     * Gate 1 of the mobile-number rule. The latest toggle in the audit log wins; before the first toggle the
     * configured default applies. On any error the switch is treated as off, so numbers stay masked.
     */
    @Override
    public boolean isGlobalMobileNumbersEnabled() {
        try {
            Optional<String> latest = jdbcTemplate.query(
                    "SELECT details FROM audit_log WHERE action_type = '" + ACTION_GLOBAL_MOBILE_TOGGLE + "' ORDER BY created_at DESC LIMIT 1",
                    (rs, rowNum) -> rs.getString("details")).stream().findFirst();
            if (latest.isPresent()) {
                JsonNode toggle = objectMapper.readTree(latest.get());
                if (toggle.has("enabled")) {
                    return toggle.get("enabled").asBoolean();
                }
            }
            return appProperties.isMobileNumbersEnabled();
        } catch (Exception e) {
            log.warn("Could not read the global mobile toggle, failing closed (false): {}", e.getMessage());
            return false;
        }
    }

    /**
     * The central voter access check: role, the Customer's own ACTIVE entitlement valid right now, the
     * area/booth scope, and the mobile-number decision (all three gates) for this caller.
     */
    private Access authorize(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId) {
        voterAccessAuthorizer.validateVoterAccessRole(callerRole);

        Entitlement entitlement = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found: " + entitlementId));
        if (!entitlement.getCustomerAccountId().equals(customerAccountId)) {
            throw new ResourceNotFoundException("Entitlement not found: " + entitlementId);
        }
        if (entitlement.getStatus() != EntitlementStatus.ACTIVE) {
            throw new ForbiddenException("Entitlement is revoked or not active");
        }
        Instant now = clock.instant();
        if (now.isBefore(entitlement.getValidFrom()) || !now.isBefore(entitlement.getValidUntil())) {
            throw new ForbiddenException("Entitlement has expired or is not yet active");
        }

        ElectionType electionType = electionTypeRepository.requireById(entitlement.getElectionTypeId());
        VoterScope scope = voterScopeAuthorizer.buildVoterScope(entitlement, electionType, callerUserId, callerRole, "v");
        boolean mobileUnmasked = voterAccessAuthorizer.isMobileUnmasked(
                isGlobalMobileNumbersEnabled(), callerRole, entitlement.isMobileAccess());
        return new Access(entitlement, electionType, scope, mobileUnmasked);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String fullName(Object first, Object last) {
        return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
    }
}
