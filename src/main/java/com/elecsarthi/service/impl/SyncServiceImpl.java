package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.StartSyncSessionRequest;
import com.elecsarthi.dto.response.SyncChunkResponse;
import com.elecsarthi.dto.response.SyncSessionResponse;
import com.elecsarthi.dto.response.VoterSummaryResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.entity.SyncSession;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SyncStatus;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.SyncSessionRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.security.authorization.VoterScopeAuthorizer.VoterScope;
import com.elecsarthi.security.authorization.VoterScopeAuthorizer;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.SyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final AppUserRepository userRepository;
    private final CustomerAccountRepository customerAccountRepository;
    private final UserDeviceRepository deviceRepository;
    private final VoterScopeAuthorizer voterScopeAuthorizer;
    private final AppProperties appProperties;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    private static final int DEFAULT_CHUNK_SIZE = 1000;

    @Override
    @Transactional
    public SyncSessionResponse startSyncSession(Long customerUserId, Role callerRole, Long customerAccountId, UUID deviceId, StartSyncSessionRequest request) {
        if (callerRole != Role.CUSTOMER) {
            throw new ForbiddenException("Bulk synchronization is strictly reserved for Customer primary login; Subusers and Agents are forbidden");
        }

        if (deviceId == null) {
            throw new BadRequestException("Device ID is required for sync session");
        }

        Entitlement ent = entitlementRepository.requireById(request.getEntitlementId());

        if (!customerAccountId.equals(ent.getCustomerAccountId()) || ent.getStatus() != EntitlementStatus.ACTIVE) {
            throw new ForbiddenException("Entitlement is not active or belongs to another customer");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(ent.getValidFrom()) || !now.isBefore(ent.getValidUntil())) {
            throw new BadRequestException("Entitlement is expired or not yet valid");
        }

        ElectionType electionType = electionTypeRepository.requireById(ent.getElectionTypeId());

        VoterScope scope = voterScopeAuthorizer.buildVoterScope(ent, electionType, customerUserId, callerRole, "v");

        // Count total voters matching entitlement criteria
        String countSql = "SELECT COUNT(*) FROM voter v WHERE " + scope.whereClause();
        Long totalRecordsCount = jdbcTemplate.queryForObject(countSql, Long.class, scope.params().toArray());
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
            throw new ForbiddenException("Access denied to bulk sync chunks");
        }

        SyncSession session = syncSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Sync session not found"));

        if (!session.getCustomerAccountId().equals(customerAccountId)) {
            throw new ForbiddenException("Session does not belong to this customer account");
        }

        // Recheck user matches session
        if (!session.getUserId().equals(customerUserId)) {
            throw new ForbiddenException("Sync session was initiated by a different user");
        }

        // Revalidate User status is ACTIVE
        AppUser user = userRepository.findById(customerUserId)
                .orElseThrow(() -> new ForbiddenException("User account not found"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "User account is suspended or inactive", HttpStatus.FORBIDDEN);
        }

        // Revalidate Customer Account status is ACTIVE
        CustomerAccount customer = customerAccountRepository.findById(customerAccountId)
                .orElseThrow(() -> new ForbiddenException("Customer account not found"));
        if (customer.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Customer account is suspended or inactive", HttpStatus.FORBIDDEN);
        }

        // Revalidate Device status is ACTIVE and matches session device
        if (session.getDeviceId() == null) {
            throw new ForbiddenException("Sync session is not bound to a device");
        }
        UserDevice device = deviceRepository.findById(session.getDeviceId())
                .orElseThrow(() -> new ForbiddenException("Device not found"));
        if (device.getStatus() != DeviceStatus.ACTIVE) {
            throw new ApiException(ErrorCode.DEVICE_REVOKED, "Device is revoked or not active", HttpStatus.FORBIDDEN);
        }

        if (session.getStatus() != SyncStatus.ACTIVE) {
            throw new ApiException(ErrorCode.SYNC_SESSION_INVALID, "Sync session is not active (status: " + session.getStatus() + ")", HttpStatus.BAD_REQUEST);
        }

        Instant now = Instant.now();
        if (now.isAfter(session.getExpiresAt())) {
            // Autonomous commit of expired state via REQUIRES_NEW
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txTemplate.execute(status -> {
                jdbcTemplate.update("UPDATE sync_session SET status = 'EXPIRED' WHERE id = ?", session.getId());
                return null;
            });
            throw new ApiException(ErrorCode.SYNC_SESSION_INVALID, "Sync session has expired", HttpStatus.BAD_REQUEST);
        }

        // Recheck entitlement status and validity window
        Entitlement ent = entitlementRepository.requireById(session.getEntitlementId());

        if (ent.getStatus() != EntitlementStatus.ACTIVE) {
            throw new ForbiddenException("Entitlement has been revoked or is inactive");
        }

        if (now.isBefore(ent.getValidFrom()) || !now.isBefore(ent.getValidUntil())) {
            throw new ForbiddenException("Entitlement validity window has expired");
        }

        if (chunkNo < 0 || chunkNo >= session.getTotalChunks()) {
            throw new BadRequestException("Invalid chunk number: " + chunkNo + " (total: " + session.getTotalChunks() + ")");
        }

        ElectionType electionType = electionTypeRepository.requireById(ent.getElectionTypeId());

        VoterScope scope = voterScopeAuthorizer.buildVoterScope(ent, electionType, customerUserId, callerRole, "v");
        String boothColumn = scope.boothColumn();

        int offset = chunkNo * session.getChunkSize();

        StringBuilder sql = new StringBuilder("SELECT v.*, vt.marked_at AS turnout_marked_at, (vt.marked_at IS NOT NULL) AS is_voted ")
                .append("FROM voter v ")
                .append("LEFT JOIN voter_turnout vt ON vt.dataset_id = v.dataset_id AND vt.customer_account_id = ? AND vt.voter_row_id = v.id ")
                .append("WHERE ").append(scope.whereClause());

        List<Object> params = new ArrayList<>();
        params.add(customerAccountId);
        params.addAll(scope.params());

        sql.append(" ORDER BY v.").append(boothColumn).append(" ASC, v.serial_no ASC LIMIT ? OFFSET ?");
        params.add(session.getChunkSize());
        params.add(offset);

        List<VoterSummaryResponse> records = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
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
            throw new ForbiddenException("Access denied to complete sync session");
        }

        SyncSession session = syncSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Sync session not found"));

        if (!session.getCustomerAccountId().equals(customerAccountId)) {
            throw new ForbiddenException("Session does not belong to this customer account");
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
            // Never serve raw bytes if decryption fails; log warning and return null
            log.warn("Sync voter mobile decryption failed: {}", e.getMessage());
            return null;
        }
    }
}
