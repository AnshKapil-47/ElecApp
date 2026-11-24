package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.OfflineTurnoutMarkDto;
import com.elecsarthi.dto.request.OfflineWritebackRequest;
import com.elecsarthi.dto.request.RequestOfflineLeaseRequest;
import com.elecsarthi.dto.response.OfflineLeaseResponse;
import com.elecsarthi.dto.response.OfflineWritebackResponse;
import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.entity.OfflineLease;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.BoothAssignmentStatus;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.BoothAssignmentRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.OfflineLeaseRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.OfflineLeaseService;
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
public class OfflineLeaseServiceImpl implements OfflineLeaseService {

    private final OfflineLeaseRepository offlineLeaseRepository;
    private final BoothAssignmentRepository boothAssignmentRepository;
    private final EntitlementRepository entitlementRepository;
    private final AppProperties appProperties;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public OfflineLeaseResponse requestLease(Long subuserId, Role callerRole, UUID deviceId, RequestOfflineLeaseRequest request) {
        if (callerRole != Role.SUBUSER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only Subuser mobile clients can request offline booth leases", HttpStatus.FORBIDDEN);
        }

        if (deviceId == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Device ID is required for offline lease", HttpStatus.BAD_REQUEST);
        }

        BoothAssignment assignment = boothAssignmentRepository.findById(request.getBoothAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Booth assignment not found"));

        if (!assignment.getSubuserId().equals(subuserId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Booth assignment does not belong to this subuser", HttpStatus.FORBIDDEN);
        }

        if (assignment.getStatus() != BoothAssignmentStatus.ACTIVE) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Booth assignment is not active", HttpStatus.BAD_REQUEST);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(assignment.getValidFrom()) || !now.isBefore(assignment.getValidUntil())) {
            throw new ApiException(ErrorCode.SUBUSER_BOOTH_EXPIRED, "Booth assignment is expired or not yet active", HttpStatus.BAD_REQUEST);
        }

        Entitlement ent = entitlementRepository.findById(assignment.getEntitlementId())
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found"));

        if (ent.getStatus() != EntitlementStatus.ACTIVE || !now.isBefore(ent.getValidUntil())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Entitlement is expired or revoked", HttpStatus.FORBIDDEN);
        }

        // Lease sequence per device
        Optional<OfflineLease> topLease = offlineLeaseRepository.findTopByDeviceIdOrderByLeaseSeqDesc(deviceId);
        long nextSeq = topLease.map(l -> l.getLeaseSeq() + 1).orElse(1L);

        // Expiry = min(assignment.validUntil, entitlement.validUntil, now + 24 hours)
        Instant max24h = now.plus(24, ChronoUnit.HOURS);
        Instant expiresAt = max24h;
        if (assignment.getValidUntil().isBefore(expiresAt)) {
            expiresAt = assignment.getValidUntil();
        }
        if (ent.getValidUntil().isBefore(expiresAt)) {
            expiresAt = ent.getValidUntil();
        }

        // Canonical payload
        Map<String, Object> payloadMap = new TreeMap<>();
        payloadMap.put("subuserId", subuserId);
        payloadMap.put("customerAccountId", assignment.getCustomerAccountId());
        payloadMap.put("boothAssignmentId", assignment.getId());
        payloadMap.put("boothValue", assignment.getBoothValue());
        payloadMap.put("entitlementId", ent.getId());
        payloadMap.put("datasetId", ent.getDatasetId());
        payloadMap.put("deviceId", deviceId.toString());
        payloadMap.put("leaseSeq", nextSeq);
        payloadMap.put("issuedAt", now.toString());
        payloadMap.put("expiresAt", expiresAt.toString());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payloadMap);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize lease payload", e);
        }

        String payloadSha256 = CryptoUtils.sha256Hex(payloadJson);

        // Ed25519 signature
        byte[] privKey = HexFormat.of().parseHex(appProperties.getSecurity().getEd25519PrivateKeyHex());
        byte[] sigBytes = CryptoUtils.signEd25519(payloadSha256.getBytes(StandardCharsets.UTF_8), privKey);
        String serverSignature = Base64.getEncoder().encodeToString(sigBytes);

        OfflineLease lease = OfflineLease.builder()
                .boothAssignmentId(assignment.getId())
                .subuserId(subuserId)
                .deviceId(deviceId)
                .leaseSeq(nextSeq)
                .payloadSha256(payloadSha256)
                .signingKeyVersion((short) appProperties.getSecurity().getEd25519KeyVersion())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();

        lease = offlineLeaseRepository.save(lease);

        auditService.log(subuserId, assignment.getCustomerAccountId(), ent.getDatasetId(), deviceId,
                "OFFLINE_LEASE_ISSUED", AuditOutcome.SUCCESS, "offline_lease", lease.getId().toString(),
                "{\"booth\":" + assignment.getBoothValue() + ",\"seq\":" + nextSeq + "}", null);

        return OfflineLeaseResponse.builder()
                .leaseId(lease.getId())
                .boothAssignmentId(assignment.getId())
                .subuserId(subuserId)
                .deviceId(deviceId)
                .leaseSeq(nextSeq)
                .boothValue(assignment.getBoothValue())
                .entitlementId(ent.getId())
                .datasetId(ent.getDatasetId())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .payloadJson(payloadJson)
                .serverSignature(serverSignature)
                .signingKeyVersion(lease.getSigningKeyVersion())
                .build();
    }

    @Override
    @Transactional
    public OfflineWritebackResponse processWriteback(Long subuserId, Role callerRole, UUID deviceId, OfflineWritebackRequest request) {
        if (callerRole != Role.SUBUSER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only Subusers can perform lease writebacks", HttpStatus.FORBIDDEN);
        }

        OfflineLease lease = offlineLeaseRepository.findById(request.getLeaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Offline lease not found"));

        if (!lease.getSubuserId().equals(subuserId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Lease does not belong to this subuser", HttpStatus.FORBIDDEN);
        }

        if (lease.getRevokedAt() != null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Offline lease has been revoked", HttpStatus.FORBIDDEN);
        }

        BoothAssignment assignment = boothAssignmentRepository.findById(lease.getBoothAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Booth assignment not found"));

        Entitlement ent = entitlementRepository.findById(assignment.getEntitlementId())
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found"));

        int totalSubmitted = request.getMarks().size();
        int totalAccepted = 0;
        int totalRejected = 0;

        String insertSql = "INSERT INTO voter_turnout (dataset_id, customer_account_id, voter_row_id, marked_by_user_id, marked_at, source) " +
                "VALUES (?, ?, ?, ?, ?, 'OFFLINE_LEASE') " +
                "ON CONFLICT (dataset_id, customer_account_id, voter_row_id) DO NOTHING";

        for (OfflineTurnoutMarkDto mark : request.getMarks()) {
            if (mark.getOfflineMarkedAt().isAfter(lease.getExpiresAt())) {
                log.warn("Offline turnout mark rejected: marked at {} is after lease expiration {}", mark.getOfflineMarkedAt(), lease.getExpiresAt());
                totalRejected++;
                continue;
            }

            jdbcTemplate.update(insertSql,
                    ent.getDatasetId(),
                    assignment.getCustomerAccountId(),
                    mark.getVoterRowId(),
                    subuserId,
                    Timestamp.from(mark.getOfflineMarkedAt()));
            totalAccepted++;
        }

        auditService.log(subuserId, assignment.getCustomerAccountId(), ent.getDatasetId(), deviceId,
                "OFFLINE_WRITEBACK_PROCESSED", AuditOutcome.SUCCESS, "offline_lease", lease.getId().toString(),
                "{\"accepted\":" + totalAccepted + ",\"rejected\":" + totalRejected + "}", null);

        return OfflineWritebackResponse.builder()
                .totalSubmitted(totalSubmitted)
                .totalAccepted(totalAccepted)
                .totalRejected(totalRejected)
                .build();
    }
}
