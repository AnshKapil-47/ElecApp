package com.elecsarthi.service.impl;

import java.time.Clock;
import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.OfflineTurnoutMarkDto;
import com.elecsarthi.dto.request.OfflineWritebackRequest;
import com.elecsarthi.dto.request.RequestOfflineLeaseRequest;
import com.elecsarthi.dto.response.OfflineLeaseResponse;
import com.elecsarthi.dto.response.OfflineWritebackResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.entity.OfflineLease;
import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.BoothAssignmentStatus;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.BoothAssignmentRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.OfflineLeaseRepository;
import com.elecsarthi.repository.UserDeviceRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.security.signing.OfflineLeaseSigner;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.OfflineLeaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineLeaseServiceImpl implements OfflineLeaseService {

    /**
     * Version of the signed lease payload. Version 2 replaced the dataset id by the lease id. Leases live at most
     * 24 hours, so version 1 leases issued before the switch expire on their own and nothing on the server
     * accepts or verifies an old format: writeback is keyed on the lease id and resolves everything from the database.
     */
    static final int LEASE_PAYLOAD_VERSION = 2;

    private final OfflineLeaseRepository offlineLeaseRepository;
    private final BoothAssignmentRepository boothAssignmentRepository;
    private final EntitlementRepository entitlementRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final UserDeviceRepository deviceRepository;
    private final CustomerAccountRepository customerAccountRepository;
    private final AppUserRepository userRepository;
    private final OfflineLeaseSigner offlineLeaseSigner;
    private final AppProperties appProperties;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock;

    @Override
    @Transactional
    public OfflineLeaseResponse requestLease(Long subuserId, Role callerRole, UUID deviceId, RequestOfflineLeaseRequest request) {
        if (callerRole != Role.SUBUSER) {
            throw new ForbiddenException("Only Subuser mobile clients can request offline booth leases");
        }

        if (deviceId == null) {
            throw new BadRequestException("Device ID is required for offline lease");
        }

        BoothAssignment assignment = boothAssignmentRepository.findById(request.getBoothAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Booth assignment not found"));

        if (!assignment.getSubuserId().equals(subuserId)) {
            throw new ResourceNotFoundException("Booth assignment not found");
        }

        if (assignment.getStatus() != BoothAssignmentStatus.ACTIVE) {
            throw new BadRequestException("Booth assignment is not active");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(assignment.getValidFrom()) || !now.isBefore(assignment.getValidUntil())) {
            throw new ApiException(ErrorCode.SUBUSER_BOOTH_EXPIRED, "Booth assignment is expired or not yet active", HttpStatus.BAD_REQUEST);
        }

        Entitlement ent = entitlementRepository.requireById(assignment.getEntitlementId());

        if (ent.getStatus() != EntitlementStatus.ACTIVE || !now.isBefore(ent.getValidUntil())) {
            throw new ForbiddenException("Entitlement is expired or revoked");
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

        // Canonical payload. It names the lease, never the dataset: the dataset is resolved on the server from
        // the lease record (lease -> booth assignment -> entitlement), and a signature gives no confidentiality.
        UUID leaseId = UUID.randomUUID();
        Map<String, Object> payloadMap = new TreeMap<>();
        payloadMap.put("payloadVersion", LEASE_PAYLOAD_VERSION);
        payloadMap.put("leaseId", leaseId.toString());
        payloadMap.put("subuserId", subuserId);
        payloadMap.put("customerAccountId", assignment.getCustomerAccountId());
        payloadMap.put("boothAssignmentId", assignment.getId());
        payloadMap.put("boothValue", assignment.getBoothValue());
        payloadMap.put("entitlementId", ent.getId());
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

        // Ed25519 signature via OfflineLeaseSigner
        String serverSignature = offlineLeaseSigner.signLeasePayload(payloadSha256);

        OfflineLease lease = OfflineLease.builder()
                .id(leaseId)
                .boothAssignmentId(assignment.getId())
                .subuserId(subuserId)
                .deviceId(deviceId)
                .leaseSeq(nextSeq)
                .payloadSha256(payloadSha256)
                .signingKeyVersion(offlineLeaseSigner.getActiveKeyVersion())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();

        lease = offlineLeaseRepository.save(lease);

        auditService.logDetails(subuserId, assignment.getCustomerAccountId(), ent.getDatasetId(), deviceId,
                "OFFLINE_LEASE_ISSUED", AuditOutcome.SUCCESS, "offline_lease", lease.getId().toString(),
                AuditService.details("booth", assignment.getBoothValue(), "seq", nextSeq), null);

        return OfflineLeaseResponse.builder()
                .leaseId(lease.getId())
                .boothAssignmentId(assignment.getId())
                .subuserId(subuserId)
                .deviceId(deviceId)
                .leaseSeq(nextSeq)
                .boothValue(assignment.getBoothValue())
                .entitlementId(ent.getId())
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
            throw new ForbiddenException("Only Subusers can perform lease writebacks");
        }

        OfflineLease lease = offlineLeaseRepository.findById(request.getLeaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Offline lease not found"));

        if (!lease.getSubuserId().equals(subuserId)) {
            throw new ResourceNotFoundException("Offline lease not found");
        }

        if (lease.getRevokedAt() != null) {
            throw new ForbiddenException("Offline lease has been revoked");
        }

        Instant now = clock.instant();

        BoothAssignment assignment = boothAssignmentRepository.findById(lease.getBoothAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Booth assignment not found"));

        if (assignment.getStatus() != BoothAssignmentStatus.ACTIVE || !now.isBefore(assignment.getValidUntil())) {
            throw new ForbiddenException("Booth assignment is revoked or expired");
        }

        Entitlement ent = entitlementRepository.requireById(assignment.getEntitlementId());

        if (ent.getStatus() != EntitlementStatus.ACTIVE || !now.isBefore(ent.getValidUntil())) {
            throw new ForbiddenException("Entitlement is revoked or expired");
        }

        if (deviceId != null) {
            UserDevice device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new ForbiddenException("Device not found"));
            if (device.getStatus() != DeviceStatus.ACTIVE) {
                throw new ApiException(ErrorCode.DEVICE_REVOKED, "Device is revoked or inactive", HttpStatus.FORBIDDEN);
            }
        }

        // Revalidate customer account status
        CustomerAccount customer = customerAccountRepository.requireById(assignment.getCustomerAccountId());
        if (customer.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Customer account is suspended or inactive");
        }

        // Revalidate subuser status
        AppUser subuser = userRepository.findById(subuserId)
                .orElseThrow(() -> new ResourceNotFoundException("Subuser not found"));
        if (subuser.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Subuser is suspended or inactive");
        }

        ElectionType electionType = electionTypeRepository.requireById(ent.getElectionTypeId());
        String boothColumn = electionType.getBoothColumn();

        int totalSubmitted = request.getMarks().size();
        int totalAccepted = 0;
        int totalRejected = 0;

        String insertSql = "INSERT INTO voter_turnout (dataset_id, customer_account_id, voter_row_id, marked_by_user_id, marked_at, source) " +
                "SELECT ?, ?, v.id, ?, ?, 'OFFLINE_LEASE' " +
                "FROM voter v WHERE v.id = ? AND v.dataset_id = ? AND v." + boothColumn + " = ? " +
                "ON CONFLICT (dataset_id, customer_account_id, voter_row_id) DO NOTHING";

        for (OfflineTurnoutMarkDto mark : request.getMarks()) {
            Instant markedAt = mark.getOfflineMarkedAt();
            if (markedAt == null
                    || markedAt.isBefore(lease.getIssuedAt())
                    || !markedAt.isBefore(lease.getExpiresAt())) {
                log.warn("Offline turnout mark rejected: marked at {} is outside strict lease interval [{}, {})",
                        markedAt, lease.getIssuedAt(), lease.getExpiresAt());
                totalRejected++;
                continue;
            }

            int rows = jdbcTemplate.update(insertSql,
                    ent.getDatasetId(),
                    assignment.getCustomerAccountId(),
                    subuserId,
                    Timestamp.from(mark.getOfflineMarkedAt()),
                    mark.getVoterRowId(),
                    ent.getDatasetId(),
                    assignment.getBoothValue());
            if (rows > 0) {
                totalAccepted++;
            } else {
                totalRejected++;
            }
        }

        auditService.logDetails(subuserId, assignment.getCustomerAccountId(), ent.getDatasetId(), deviceId,
                "OFFLINE_WRITEBACK_PROCESSED", AuditOutcome.SUCCESS, "offline_lease", lease.getId().toString(),
                AuditService.details("accepted", totalAccepted, "rejected", totalRejected), null);

        return OfflineWritebackResponse.builder()
                .totalSubmitted(totalSubmitted)
                .totalAccepted(totalAccepted)
                .totalRejected(totalRejected)
                .build();
    }
}
