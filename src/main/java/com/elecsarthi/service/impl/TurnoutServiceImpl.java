package com.elecsarthi.service.impl;

import com.elecsarthi.dto.request.MarkTurnoutRequest;
import com.elecsarthi.dto.request.ResetTurnoutRequest;
import com.elecsarthi.dto.request.UnmarkTurnoutRequest;
import com.elecsarthi.dto.response.BoothTurnoutStat;
import com.elecsarthi.dto.response.GenderTurnoutStat;
import com.elecsarthi.dto.response.TurnoutResponse;
import com.elecsarthi.dto.response.TurnoutStatisticsResponse;
import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.TurnoutSource;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.BoothAssignmentRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.service.TurnoutService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TurnoutServiceImpl implements TurnoutService {

    private final EntitlementRepository entitlementRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final BoothAssignmentRepository boothAssignmentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public TurnoutResponse markVoted(Long callerUserId, Role callerRole, Long customerAccountId, MarkTurnoutRequest request) {
        if (callerRole != Role.CUSTOMER && callerRole != Role.SUBUSER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only Customers and Subusers can mark turnout", HttpStatus.FORBIDDEN);
        }

        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, request.getEntitlementId());
        ElectionType electionType = electionTypeRepository.findById(ent.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        String boothColumn = electionType.getBoothColumn();

        if (callerRole == Role.SUBUSER) {
            Set<Integer> allowedBooths = getActiveAssignedBooths(callerUserId, ent.getId());
            // Verify voter belongs to one of the allowed booths
            String checkBoothSql = "SELECT " + boothColumn + " FROM voter WHERE dataset_id = ? AND id = ?";
            List<Integer> voterBooths = jdbcTemplate.query(checkBoothSql, (rs, rowNum) -> rs.getObject(1, Integer.class),
                    ent.getDatasetId(), request.getVoterRowId());

            if (voterBooths.isEmpty()) {
                throw new ResourceNotFoundException("Voter not found with ID: " + request.getVoterRowId());
            }

            Integer voterBooth = voterBooths.get(0);
            if (voterBooth == null || !allowedBooths.contains(voterBooth)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Subusers can only mark turnout in assigned booths", HttpStatus.FORBIDDEN);
            }
        }

        Instant now = Instant.now();
        String insertSql = "INSERT INTO voter_turnout (dataset_id, customer_account_id, voter_row_id, marked_by_user_id, marked_at, source) " +
                "VALUES (?, ?, ?, ?, clock_timestamp(), 'ONLINE') " +
                "ON CONFLICT (dataset_id, customer_account_id, voter_row_id) DO NOTHING";

        jdbcTemplate.update(insertSql, ent.getDatasetId(), customerAccountId, request.getVoterRowId(), callerUserId);

        return TurnoutResponse.builder()
                .datasetId(ent.getDatasetId())
                .customerAccountId(customerAccountId)
                .voterRowId(request.getVoterRowId())
                .voted(true)
                .markedAt(now)
                .source(TurnoutSource.ONLINE)
                .build();
    }

    @Override
    @Transactional
    public void unmarkVoted(Long callerUserId, Role callerRole, Long customerAccountId, UnmarkTurnoutRequest request) {
        if (callerRole != Role.CUSTOMER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the Customer can unmark turnout online; Subusers are strictly forbidden", HttpStatus.FORBIDDEN);
        }

        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, request.getEntitlementId());

        String deleteSql = "DELETE FROM voter_turnout WHERE dataset_id = ? AND customer_account_id = ? AND voter_row_id = ?";
        jdbcTemplate.update(deleteSql, ent.getDatasetId(), customerAccountId, request.getVoterRowId());
        log.info("Turnout unmarked for dataset={}, customer={}, voter={}", ent.getDatasetId(), customerAccountId, request.getVoterRowId());
    }

    @Override
    @Transactional
    public void resetTurnout(Long callerUserId, Role callerRole, Long customerAccountId, ResetTurnoutRequest request) {
        if (callerRole != Role.CUSTOMER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the Customer can reset turnout", HttpStatus.FORBIDDEN);
        }

        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, request.getEntitlementId());
        ElectionType electionType = electionTypeRepository.findById(ent.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        String boothColumn = electionType.getBoothColumn();

        if (request.getBoothNo() != null) {
            String sql = "DELETE FROM voter_turnout vt WHERE vt.dataset_id = ? AND vt.customer_account_id = ? " +
                    "AND vt.voter_row_id IN (SELECT v.id FROM voter v WHERE v.dataset_id = ? AND v." + boothColumn + " = ?)";
            jdbcTemplate.update(sql, ent.getDatasetId(), customerAccountId, ent.getDatasetId(), request.getBoothNo());
        } else {
            String sql = "DELETE FROM voter_turnout WHERE dataset_id = ? AND customer_account_id = ?";
            jdbcTemplate.update(sql, ent.getDatasetId(), customerAccountId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TurnoutStatisticsResponse getTurnoutStatistics(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Integer boothNo) {
        if (callerRole != Role.CUSTOMER && callerRole != Role.SUBUSER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Access denied to turnout statistics", HttpStatus.FORBIDDEN);
        }

        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, entitlementId);
        ElectionType electionType = electionTypeRepository.findById(ent.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        String boothColumn = electionType.getBoothColumn();
        Set<Integer> allowedBooths = null;

        if (callerRole == Role.SUBUSER) {
            allowedBooths = getActiveAssignedBooths(callerUserId, ent.getId());
            if (boothNo != null && !allowedBooths.contains(boothNo)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Access to unassigned booth statistics is forbidden", HttpStatus.FORBIDDEN);
            }
        }

        StringBuilder whereSql = new StringBuilder(" WHERE v.dataset_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(ent.getDatasetId());

        applyEntitlementCriteria(whereSql, params, ent.getCriteria());

        if (boothNo != null) {
            whereSql.append(" AND v.").append(boothColumn).append(" = ? ");
            params.add(boothNo);
        } else if (allowedBooths != null) {
            if (allowedBooths.isEmpty()) {
                return TurnoutStatisticsResponse.builder()
                        .entitlementId(entitlementId)
                        .scopeLabel(ent.getScopeLabel())
                        .totalVoters(0)
                        .totalVoted(0)
                        .turnoutPercentage(0.0)
                        .boothBreakdown(Collections.emptyList())
                        .genderBreakdown(Collections.emptyList())
                        .build();
            }
            whereSql.append(" AND v.").append(boothColumn).append(" IN (")
                    .append(allowedBooths.stream().map(String::valueOf).collect(Collectors.joining(",")))
                    .append(") ");
        }

        // Total Voters
        String totalVotersSql = "SELECT COUNT(*) FROM voter v " + whereSql;
        Long totalVoters = jdbcTemplate.queryForObject(totalVotersSql, Long.class, params.toArray());
        long totalV = (totalVoters != null) ? totalVoters : 0L;

        // Total Voted
        String totalVotedSql = "SELECT COUNT(*) FROM voter_turnout vt JOIN voter v ON v.dataset_id = vt.dataset_id AND v.id = vt.voter_row_id " +
                whereSql + " AND vt.customer_account_id = ? ";
        List<Object> votedParams = new ArrayList<>(params);
        votedParams.add(customerAccountId);
        Long totalVoted = jdbcTemplate.queryForObject(totalVotedSql, Long.class, votedParams.toArray());
        long votedCount = (totalVoted != null) ? totalVoted : 0L;

        double percentage = (totalV > 0) ? (votedCount * 100.0 / totalV) : 0.0;

        // Booth breakdown
        String boothSql = "SELECT v." + boothColumn + " AS b_no, coalesce(v.booth_name_en, 'Booth ' || v." + boothColumn + ") AS b_name, " +
                "COUNT(v.id) AS total_in_booth, " +
                "COUNT(vt.voter_row_id) AS voted_in_booth " +
                "FROM voter v " +
                "LEFT JOIN voter_turnout vt ON vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id " +
                whereSql +
                " GROUP BY v." + boothColumn + ", v.booth_name_en ORDER BY v." + boothColumn + " ASC";

        List<Object> bParams = new ArrayList<>();
        bParams.add(customerAccountId);
        bParams.addAll(params);

        List<BoothTurnoutStat> boothBreakdown = jdbcTemplate.query(boothSql, (rs, rowNum) -> {
            int bNum = rs.getInt("b_no");
            String bName = rs.getString("b_name");
            long bTotal = rs.getLong("total_in_booth");
            long bVoted = rs.getLong("voted_in_booth");
            double bPct = (bTotal > 0) ? (bVoted * 100.0 / bTotal) : 0.0;
            return BoothTurnoutStat.builder()
                    .boothNo(bNum)
                    .boothName(bName)
                    .totalVoters(bTotal)
                    .totalVoted(bVoted)
                    .turnoutPercentage(Math.round(bPct * 100.0) / 100.0)
                    .build();
        }, bParams.toArray());

        // Gender breakdown
        String genderSql = "SELECT coalesce(v.sex, 'UNKNOWN') AS g_sex, " +
                "COUNT(v.id) AS total_in_gender, " +
                "COUNT(vt.voter_row_id) AS voted_in_gender " +
                "FROM voter v " +
                "LEFT JOIN voter_turnout vt ON vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id " +
                whereSql +
                " GROUP BY v.sex ORDER BY v.sex ASC";

        List<GenderTurnoutStat> genderBreakdown = jdbcTemplate.query(genderSql, (rs, rowNum) -> {
            String g = rs.getString("g_sex");
            long gTotal = rs.getLong("total_in_gender");
            long gVoted = rs.getLong("voted_in_gender");
            double gPct = (gTotal > 0) ? (gVoted * 100.0 / gTotal) : 0.0;
            return GenderTurnoutStat.builder()
                    .gender(g)
                    .totalVoters(gTotal)
                    .totalVoted(gVoted)
                    .turnoutPercentage(Math.round(gPct * 100.0) / 100.0)
                    .build();
        }, bParams.toArray());

        return TurnoutStatisticsResponse.builder()
                .entitlementId(entitlementId)
                .scopeLabel(ent.getScopeLabel())
                .totalVoters(totalV)
                .totalVoted(votedCount)
                .turnoutPercentage(Math.round(percentage * 100.0) / 100.0)
                .boothBreakdown(boothBreakdown)
                .genderBreakdown(genderBreakdown)
                .build();
    }

    private Entitlement validateAccessAndGetEntitlement(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId) {
        if (callerRole == Role.OWNER || callerRole == Role.SUBOWNER || callerRole == Role.AGENT) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Administrative and Agent roles are not permitted to access voter turnout", HttpStatus.FORBIDDEN);
        }

        Entitlement ent = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found: " + entitlementId));

        if (!ent.getCustomerAccountId().equals(customerAccountId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Entitlement does not belong to your customer account", HttpStatus.FORBIDDEN);
        }

        if (ent.getStatus() != EntitlementStatus.ACTIVE) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Entitlement is not active", HttpStatus.FORBIDDEN);
        }

        Instant now = Instant.now();
        if (now.isBefore(ent.getValidFrom()) || !now.isBefore(ent.getValidUntil())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Entitlement is expired or not yet valid", HttpStatus.FORBIDDEN);
        }

        return ent;
    }

    private Set<Integer> getActiveAssignedBooths(Long subuserId, Long entitlementId) {
        Instant now = Instant.now();
        List<BoothAssignment> assignments = boothAssignmentRepository.findActiveAssignmentsForSubuserAndEntitlement(subuserId, entitlementId, now);
        if (assignments.isEmpty()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Subuser has no active booth assignment for this entitlement", HttpStatus.FORBIDDEN);
        }
        return assignments.stream().map(BoothAssignment::getBoothValue).collect(Collectors.toSet());
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
            log.error("Failed to parse entitlement criteria JSON", e);
        }
    }
}
