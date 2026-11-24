package com.elecsarthi.service.impl;

import com.elecsarthi.dto.request.MarkTurnoutRequest;
import com.elecsarthi.dto.request.ResetTurnoutRequest;
import com.elecsarthi.dto.request.UnmarkTurnoutRequest;
import com.elecsarthi.dto.response.BoothTurnoutStat;
import com.elecsarthi.dto.response.GenderTurnoutStat;
import com.elecsarthi.dto.response.TurnoutResponse;
import com.elecsarthi.dto.response.TurnoutStatisticsResponse;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.TurnoutSource;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.security.authorization.VoterScopeAuthorizer.VoterScope;
import com.elecsarthi.security.authorization.VoterScopeAuthorizer;
import com.elecsarthi.service.TurnoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TurnoutServiceImpl implements TurnoutService {

    private final EntitlementRepository entitlementRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final VoterScopeAuthorizer voterScopeAuthorizer;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public TurnoutResponse markVoted(Long callerUserId, Role callerRole, Long customerAccountId, MarkTurnoutRequest request) {
        if (callerRole != Role.CUSTOMER && callerRole != Role.SUBUSER) {
            throw new ForbiddenException("Only Customers and Subusers can mark turnout");
        }

        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, request.getEntitlementId());
        ElectionType electionType = electionTypeRepository.requireById(ent.getElectionTypeId());

        VoterScope scope = voterScopeAuthorizer.buildVoterScope(ent, electionType, callerUserId, callerRole, "v");

        // Verify voter is within the entitlement geographical boundary AND subuser assigned booth
        String checkSql = "SELECT 1 FROM voter v WHERE v.id = ? AND " + scope.whereClause();
        List<Object> checkParams = new ArrayList<>();
        checkParams.add(request.getVoterRowId());
        checkParams.addAll(scope.params());

        List<Integer> matches = jdbcTemplate.query(checkSql, (rs, rowNum) -> 1, checkParams.toArray());
        if (matches.isEmpty()) {
            throw new ResourceNotFoundException("Voter not found in entitlement area or assigned booth: " + request.getVoterRowId());
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
            throw new ForbiddenException("Only the Customer can unmark turnout online; Subusers are strictly forbidden");
        }

        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, request.getEntitlementId());
        ElectionType electionType = electionTypeRepository.requireById(ent.getElectionTypeId());

        VoterScope scope = voterScopeAuthorizer.buildVoterScope(ent, electionType, callerUserId, callerRole, "v");

        // Scoped deletion: only unmark if voter belongs to this customer's entitlement area
        String deleteSql = "DELETE FROM voter_turnout vt WHERE vt.dataset_id = ? AND vt.customer_account_id = ? AND vt.voter_row_id = ? " +
                "AND vt.voter_row_id IN (SELECT v.id FROM voter v WHERE v.id = ? AND " + scope.whereClause() + ")";

        List<Object> deleteParams = new ArrayList<>();
        deleteParams.add(ent.getDatasetId());
        deleteParams.add(customerAccountId);
        deleteParams.add(request.getVoterRowId());
        deleteParams.add(request.getVoterRowId());
        deleteParams.addAll(scope.params());

        jdbcTemplate.update(deleteSql, deleteParams.toArray());
        log.info("Turnout unmarked for dataset={}, customer={}, voter={}", ent.getDatasetId(), customerAccountId, request.getVoterRowId());
    }

    @Override
    @Transactional
    public void resetTurnout(Long callerUserId, Role callerRole, Long customerAccountId, ResetTurnoutRequest request) {
        if (callerRole != Role.CUSTOMER) {
            throw new ForbiddenException("Only the Customer can reset turnout");
        }

        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, request.getEntitlementId());
        ElectionType electionType = electionTypeRepository.requireById(ent.getElectionTypeId());

        VoterScope scope = voterScopeAuthorizer.buildVoterScope(ent, electionType, callerUserId, callerRole, "v");

        // Ensure turnout reset is strictly limited to this entitlement area, never across other ACs
        StringBuilder deleteSql = new StringBuilder("DELETE FROM voter_turnout vt WHERE vt.dataset_id = ? AND vt.customer_account_id = ? ")
                .append("AND vt.voter_row_id IN (SELECT v.id FROM voter v WHERE ").append(scope.whereClause());

        List<Object> params = new ArrayList<>();
        params.add(ent.getDatasetId());
        params.add(customerAccountId);
        params.addAll(scope.params());

        if (request.getBoothNo() != null) {
            deleteSql.append(" AND v.").append(scope.boothColumn()).append(" = ?");
            params.add(request.getBoothNo());
        }
        deleteSql.append(")");

        jdbcTemplate.update(deleteSql.toString(), params.toArray());
        log.info("Turnout reset for entitlement={}, booth={}", ent.getId(), request.getBoothNo());
    }

    @Override
    @Transactional(readOnly = true)
    public TurnoutStatisticsResponse getTurnoutStatistics(Long callerUserId, Role callerRole, Long customerAccountId, Long entitlementId, Integer boothNo) {
        if (callerRole != Role.CUSTOMER && callerRole != Role.SUBUSER) {
            throw new ForbiddenException("Access denied to turnout statistics");
        }

        Entitlement ent = validateAccessAndGetEntitlement(callerUserId, callerRole, customerAccountId, entitlementId);
        ElectionType electionType = electionTypeRepository.requireById(ent.getElectionTypeId());

        VoterScope scope = voterScopeAuthorizer.buildVoterScope(ent, electionType, callerUserId, callerRole, "v");
        String boothColumn = scope.boothColumn();

        if (callerRole == Role.SUBUSER && boothNo != null) {
            if (scope.allowedBooths() != null && !scope.allowedBooths().contains(boothNo)) {
                throw new ForbiddenException("Access to unassigned booth statistics is forbidden");
            }
        }

        StringBuilder whereSql = new StringBuilder(" WHERE ").append(scope.whereClause());
        List<Object> params = new ArrayList<>(scope.params());

        if (boothNo != null) {
            whereSql.append(" AND v.").append(boothColumn).append(" = ? ");
            params.add(boothNo);
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
            throw new ForbiddenException("Administrative and Agent roles are not permitted to access voter turnout");
        }

        Entitlement ent = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found: " + entitlementId));

        if (!ent.getCustomerAccountId().equals(customerAccountId)) {
            throw new ForbiddenException("Entitlement does not belong to your customer account");
        }

        if (ent.getStatus() != EntitlementStatus.ACTIVE) {
            throw new ForbiddenException("Entitlement is not active");
        }

        Instant now = Instant.now();
        if (now.isBefore(ent.getValidFrom()) || !now.isBefore(ent.getValidUntil())) {
            throw new ForbiddenException("Entitlement is expired or not yet valid");
        }

        return ent;
    }
}
