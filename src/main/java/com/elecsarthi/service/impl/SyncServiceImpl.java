package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.StartSyncSessionRequest;
import com.elecsarthi.dto.response.SyncChunkResponse;
import com.elecsarthi.dto.response.SyncSessionResponse;
import com.elecsarthi.dto.response.VoterSummaryResponse;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.entity.SyncSession;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SyncStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.SyncSessionRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.SyncService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncServiceImpl implements SyncService {

    private final SyncSessionRepository syncSessionRepository;
    private final EntitlementRepository entitlementRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final AppProperties appProperties;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int DEFAULT_CHUNK_SIZE = 1000;

    @Override
    @Transactional
    public SyncSessionResponse startSyncSession(Long customerUserId, Role callerRole, Long customerAccountId, UUID deviceId, StartSyncSessionRequest request) {
        if (callerRole != Role.CUSTOMER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bulk synchronization is strictly reserved for Customer primary login; Subusers and Agents are forbidden", HttpStatus.FORBIDDEN);
        }

        if (deviceId == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Device ID is required for sync session", HttpStatus.BAD_REQUEST);
        }

        Entitlement ent = entitlementRepository.findById(request.getEntitlementId())
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found"));

        if (!customerAccountId.equals(ent.getCustomerAccountId()) || ent.getStatus() != EntitlementStatus.ACTIVE) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Entitlement is not active or belongs to another customer", HttpStatus.FORBIDDEN);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(ent.getValidFrom()) || !now.isBefore(ent.getValidUntil())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Entitlement is expired or not yet valid", HttpStatus.BAD_REQUEST);
        }

        // Count total voters matching entitlement criteria
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM voter v WHERE v.dataset_id = ? ");
        List<Object> countParams = new ArrayList<>();
        countParams.add(ent.getDatasetId());
        applyEntitlementCriteria(countSql, countParams, ent.getCriteria());

        Long totalRecordsCount = jdbcTemplate.queryForObject(countSql.toString(), Long.class, countParams.toArray());
        int totalRecords = (totalRecordsCount != null) ? totalRecordsCount.intValue() : 0;

        int chunkSize = DEFAULT_CHUNK_SIZE;
        int totalChunks = (totalRecords > 0) ? ((totalRecords + chunkSize - 1) / chunkSize) : 1;

        // Offline valid until = min(entitlement.validUntil, now + 24 hours)
        Instant max24h = now.plus(24, ChronoUnit.HOURS);
        Instant offlineValidUntil = max24h;
        if (ent.getValidUntil().isBefore(offlineValidUntil)) {
            offlineValidUntil = ent.getValidUntil();
        }

        Instant sessionExpiresAt = now.plus(2, ChronoUnit.HOURS); // Session active window

        String manifestPayload = customerAccountId + ":" + ent.getId() + ":" + totalRecords + ":" + totalChunks + ":" + ent.getCriteriaHash();
        String manifestSha256 = CryptoUtils.sha256Hex(manifestPayload);

        boolean includesMobile = appProperties.isMobileNumbersEnabled() && ent.isMobileAccess();

        SyncSession session = SyncSession.builder()
                .customerAccountId(customerAccountId)
                .userId(customerUserId)
                .entitlementId(ent.getId())
                .deviceId(deviceId)
                .protocolVersion(request.getProtocolVersion())
                .manifestSha256(manifestSha256)
                .scopeSnapshot(ent.getCriteria())
                .policyVersion(1)
                .includesMobile(includesMobile)
                .totalRecords(totalRecords)
                .totalChunks(totalChunks)
                .chunkSize(chunkSize)
                .chunksDone(0)
                .signingKeyVersion((short) appProperties.getSecurity().getEd25519KeyVersion())
                .offlineValidUntil(offlineValidUntil)
                .status(SyncStatus.ACTIVE)
                .createdAt(now)
                .expiresAt(sessionExpiresAt)
                .build();

        session = syncSessionRepository.save(session);

        auditService.log(customerUserId, customerAccountId, ent.getDatasetId(), deviceId,
                "SYNC_SESSION_STARTED", AuditOutcome.SUCCESS, "sync_session", session.getId().toString(),
                "{\"totalRecords\":" + totalRecords + ",\"chunks\":" + totalChunks + "}", null);

        return mapToResponse(session);
    }

    @Override
    @Transactional
    public SyncChunkResponse getChunk(Long customerUserId, Role callerRole, Long customerAccountId, UUID sessionId, int chunkNo) {
        if (callerRole != Role.CUSTOMER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Access denied to bulk sync chunks", HttpStatus.FORBIDDEN);
        }

        SyncSession session = syncSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Sync session not found"));

        if (!session.getCustomerAccountId().equals(customerAccountId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Session does not belong to this customer account", HttpStatus.FORBIDDEN);
        }

        if (session.getStatus() != SyncStatus.ACTIVE) {
            throw new ApiException(ErrorCode.SYNC_SESSION_INVALID, "Sync session is not active (status: " + session.getStatus() + ")", HttpStatus.BAD_REQUEST);
        }

        if (Instant.now().isAfter(session.getExpiresAt())) {
            session.setStatus(SyncStatus.EXPIRED);
            syncSessionRepository.save(session);
            throw new ApiException(ErrorCode.SYNC_SESSION_INVALID, "Sync session has expired", HttpStatus.BAD_REQUEST);
        }

        if (chunkNo < 0 || chunkNo >= session.getTotalChunks()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Invalid chunk number: " + chunkNo + " (total: " + session.getTotalChunks() + ")", HttpStatus.BAD_REQUEST);
        }

        Entitlement ent = entitlementRepository.findById(session.getEntitlementId())
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found"));
        ElectionType electionType = electionTypeRepository.findById(ent.getElectionTypeId()).orElseThrow();
        String boothColumn = electionType.getBoothColumn();

        int offset = chunkNo * session.getChunkSize();

        StringBuilder sql = new StringBuilder("SELECT v.*, vt.marked_at AS turnout_marked_at, (vt.marked_at IS NOT NULL) AS is_voted " +
                "FROM voter v " +
                "LEFT JOIN voter_turnout vt ON vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id " +
                "WHERE v.dataset_id = ? ");

        List<Object> params = new ArrayList<>();
        params.add(customerAccountId);
        params.add(ent.getDatasetId());
        applyEntitlementCriteria(sql, params, session.getScopeSnapshot());

        sql.append(" ORDER BY v.").append(boothColumn).append(" ASC, v.serial_no ASC LIMIT ? OFFSET ?");
        params.add(session.getChunkSize());
        params.add(offset);

        List<VoterSummaryResponse> records = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
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
        }, params.toArray());

        session.setChunksDone(Math.max(session.getChunksDone(), chunkNo + 1));
        syncSessionRepository.save(session);

        String chunkSha256 = CryptoUtils.sha256Hex(sessionId + ":" + chunkNo + ":" + records.size());
        boolean isLast = (chunkNo + 1 >= session.getTotalChunks());

        return SyncChunkResponse.builder()
                .sessionId(sessionId)
                .chunkNo(chunkNo)
                .totalChunks(session.getTotalChunks())
                .records(records)
                .chunkSha256(chunkSha256)
                .isLastChunk(isLast)
                .build();
    }

    @Override
    @Transactional
    public SyncSessionResponse completeSyncSession(Long customerUserId, Role callerRole, Long customerAccountId, UUID sessionId) {
        if (callerRole != Role.CUSTOMER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Access denied to complete sync session", HttpStatus.FORBIDDEN);
        }

        SyncSession session = syncSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Sync session not found"));

        if (!session.getCustomerAccountId().equals(customerAccountId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Session does not belong to this customer account", HttpStatus.FORBIDDEN);
        }

        session.setStatus(SyncStatus.COMPLETED);
        session.setCompletedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        session = syncSessionRepository.save(session);

        auditService.log(customerUserId, customerAccountId, null, session.getDeviceId(),
                "SYNC_SESSION_COMPLETED", AuditOutcome.SUCCESS, "sync_session", session.getId().toString(),
                "{\"chunksDone\":" + session.getChunksDone() + "}", null);

        return mapToResponse(session);
    }

    private SyncSessionResponse mapToResponse(SyncSession s) {
        return SyncSessionResponse.builder()
                .sessionId(s.getId())
                .customerAccountId(s.getCustomerAccountId())
                .entitlementId(s.getEntitlementId())
                .deviceId(s.getDeviceId())
                .totalRecords(s.getTotalRecords())
                .totalChunks(s.getTotalChunks())
                .chunkSize(s.getChunkSize())
                .includesMobile(s.isIncludesMobile())
                .status(s.getStatus())
                .offlineValidUntil(s.getOfflineValidUntil())
                .expiresAt(s.getExpiresAt())
                .manifestSha256(s.getManifestSha256())
                .build();
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

    private String processMobileNumber(byte[] mobileNoEnc, Role callerRole, boolean entitlementMobileAccess) {
        if (mobileNoEnc == null || mobileNoEnc.length == 0) {
            return null;
        }

        boolean canViewUnmasked = appProperties.isMobileNumbersEnabled() &&
                callerRole == Role.CUSTOMER &&
                entitlementMobileAccess;

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
            String raw = new String(mobileNoEnc, StandardCharsets.UTF_8);
            if (canViewUnmasked) return raw;
            if (raw.length() >= 4) return "+91*****" + raw.substring(raw.length() - 4);
            return "+91*****XXXX";
        }
    }
}
