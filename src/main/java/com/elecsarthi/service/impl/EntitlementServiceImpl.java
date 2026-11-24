package com.elecsarthi.service.impl;

import com.elecsarthi.dto.request.DirectEntitlementRequest;
import com.elecsarthi.dto.response.EntitlementResponse;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.*;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.CriteriaNormalizer;
import com.elecsarthi.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementServiceImpl implements EntitlementService {

    private final EntitlementRepository entitlementRepository;
    private final CustomerAccountRepository customerAccountRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final DatasetRepository datasetRepository;
    private final CriteriaNormalizer criteriaNormalizer;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    private static final ZoneId KOLKATA_ZONE = ZoneId.of("Asia/Kolkata");

    @Override
    @Transactional
    public EntitlementResponse createDirectEntitlement(Long callerUserId, Role callerRole, DirectEntitlementRequest request) {
        if (callerRole != Role.OWNER && callerRole != Role.SUBOWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only OWNER or SUBOWNER can create direct entitlements", HttpStatus.FORBIDDEN);
        }

        if (request.isMobileAccess() && callerRole != Role.OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only OWNER can enable mobile number access", HttpStatus.FORBIDDEN);
        }

        CustomerAccount customer = customerAccountRepository.findById(request.getCustomerAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer account not found"));

        if (customer.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Customer account is suspended or not active", HttpStatus.BAD_REQUEST);
        }

        ElectionType electionType = electionTypeRepository.findById(request.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        if (!electionType.isActive()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Election type is not active", HttpStatus.BAD_REQUEST);
        }

        CriteriaNormalizer.NormalizedCriteria normalized = criteriaNormalizer.normalize(electionType, request.getCriteria());

        Dataset dataset = resolveDataset(request.getStorageTier(), normalized.getCriteriaMap());
        checkAmbiguity(electionType, dataset.getId(), normalized.getCriteriaMap());

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        List<Entitlement> existingEntitlements = entitlementRepository.findLatestActiveForArea(
                customer.getId(),
                electionType.getId(),
                request.getStorageTier(),
                normalized.getCriteriaHash());

        Instant validFrom = now;
        if (!existingEntitlements.isEmpty()) {
            Entitlement latest = existingEntitlements.get(0);
            if (latest.getValidUntil().isAfter(now)) {
                validFrom = latest.getValidUntil();
            }
        }

        Instant validUntil = calculateValidUntil(validFrom, request.getDurationValue(), request.getDurationUnit());

        if (request.getStorageTier() == DatasetTier.MASTER) {
            if (dataset.getDeleteAfter() != null && validUntil.isAfter(dataset.getDeleteAfter())) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "Validity until " + validUntil + " exceeds MASTER dataset expiration " + dataset.getDeleteAfter(),
                        HttpStatus.BAD_REQUEST);
            }
        }

        Entitlement entitlement = Entitlement.builder()
                .customerAccountId(customer.getId())
                .electionTypeId(electionType.getId())
                .datasetId(dataset.getId())
                .storageTier(request.getStorageTier())
                .criteria(normalized.getCanonicalJson())
                .criteriaHash(normalized.getCriteriaHash())
                .scopeLabel(normalized.getScopeLabel())
                .source(EntitlementSource.DIRECT)
                .saleLineId(null)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(validFrom)
                .validUntil(validUntil)
                .mobileAccess(request.isMobileAccess())
                .mobileAccessChangedAt(request.isMobileAccess() ? now : null)
                .mobileAccessChangedBy(request.isMobileAccess() ? callerUserId : null)
                .createdByUserId(callerUserId)
                .createdAt(now)
                .build();

        entitlement = entitlementRepository.save(entitlement);

        // Update LONG_TERM dataset delete_after to max(validUntil) + 30 days
        if (request.getStorageTier() == DatasetTier.LONG_TERM) {
            recomputeLongTermRetention(dataset.getId(), now);
        }

        auditService.log(callerUserId, customer.getId(), entitlement.getDatasetId(), null,
                "DIRECT_ENTITLEMENT_CREATED", AuditOutcome.SUCCESS,
                "entitlement", entitlement.getId().toString(),
                "{\"scopeLabel\":\"" + entitlement.getScopeLabel() + "\"}", null);

        return mapToResponse(entitlement, electionType);
    }

    @Override
    @Transactional
    public EntitlementResponse updateMobileAccess(Long ownerUserId, Long entitlementId, boolean enabled) {
        Entitlement entitlement = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found"));

        entitlement.setMobileAccess(enabled);
        entitlement.setMobileAccessChangedAt(Instant.now());
        entitlement.setMobileAccessChangedBy(ownerUserId);
        entitlement = entitlementRepository.save(entitlement);

        // Bump customer mobileAccessPolicyVersion
        customerAccountRepository.findById(entitlement.getCustomerAccountId()).ifPresent(ca -> {
            ca.setMobileAccessPolicyVersion(ca.getMobileAccessPolicyVersion() + 1);
            customerAccountRepository.save(ca);
        });

        auditService.log(ownerUserId, entitlement.getCustomerAccountId(), entitlement.getDatasetId(), null,
                "ENTITLEMENT_MOBILE_ACCESS_UPDATED", AuditOutcome.SUCCESS,
                "entitlement", entitlement.getId().toString(),
                "{\"mobileAccess\":" + enabled + "}", null);

        ElectionType electionType = electionTypeRepository.findById(entitlement.getElectionTypeId()).orElse(null);
        return mapToResponse(entitlement, electionType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EntitlementResponse> getEntitlementsByCustomer(Long callerUserId, Role callerRole, Long customerAccountId) {
        CustomerAccount customer = customerAccountRepository.findById(customerAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer account not found"));

        if (callerRole == Role.SUBUSER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Subusers cannot view customer entitlements", HttpStatus.FORBIDDEN);
        }

        if (callerRole == Role.AGENT) {
            if (customer.getOwningAgentUserId() == null || !customer.getOwningAgentUserId().equals(callerUserId)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "You are not the owning agent for this customer", HttpStatus.FORBIDDEN);
            }
        }

        Map<Short, ElectionType> typeMap = electionTypeRepository.findAll().stream()
                .collect(Collectors.toMap(ElectionType::getId, et -> et));

        return entitlementRepository.findByCustomerAccountId(customerAccountId).stream()
                .map(e -> mapToResponse(e, typeMap.get(e.getElectionTypeId())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EntitlementResponse> getMyEntitlements(Long customerAccountId) {
        Map<Short, ElectionType> typeMap = electionTypeRepository.findAll().stream()
                .collect(Collectors.toMap(ElectionType::getId, et -> et));

        return entitlementRepository.findByCustomerAccountId(customerAccountId).stream()
                .map(e -> mapToResponse(e, typeMap.get(e.getElectionTypeId())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public EntitlementResponse getEntitlementById(Long callerUserId, Role callerRole, Long entitlementId) {
        Entitlement entitlement = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found"));

        if (callerRole == Role.SUBUSER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Subusers cannot view entitlements", HttpStatus.FORBIDDEN);
        }

        if (callerRole == Role.AGENT) {
            CustomerAccount customer = customerAccountRepository.findById(entitlement.getCustomerAccountId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer account not found"));
            if (customer.getOwningAgentUserId() == null || !customer.getOwningAgentUserId().equals(callerUserId)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Access denied", HttpStatus.FORBIDDEN);
            }
        }

        ElectionType electionType = electionTypeRepository.findById(entitlement.getElectionTypeId()).orElse(null);
        return mapToResponse(entitlement, electionType);
    }

    @Override
    @Transactional
    public EntitlementResponse revokeEntitlement(Long callerUserId, Role callerRole, Long entitlementId, String reason) {
        if (callerRole != Role.OWNER && callerRole != Role.SUBOWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only OWNER or SUBOWNER can revoke entitlements", HttpStatus.FORBIDDEN);
        }

        Entitlement entitlement = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Entitlement not found"));

        if (entitlement.getStatus() == EntitlementStatus.REVOKED) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Entitlement is already revoked", HttpStatus.BAD_REQUEST);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        entitlement.setStatus(EntitlementStatus.REVOKED);
        entitlement.setRevokedAt(now);
        entitlement.setRevokeReason(reason != null && !reason.isBlank() ? reason : "Revoked by " + callerRole.name());
        entitlement.setRevokedByUserId(callerUserId);
        entitlement = entitlementRepository.saveAndFlush(entitlement);

        // Cascade-revoke offline leases for booth assignments under this entitlement
        jdbcTemplate.update("UPDATE offline_lease SET revoked_at = ?, revoked_by_user_id = ? " +
                "WHERE booth_assignment_id IN (SELECT id FROM booth_assignment WHERE entitlement_id = ?) AND revoked_at IS NULL",
                Timestamp.from(now), callerUserId, entitlementId);

        // Recompute delete_after for LONG_TERM dataset
        recomputeLongTermRetention(entitlement.getDatasetId(), now);

        // Increment customer's mobile access policy version
        customerAccountRepository.findById(entitlement.getCustomerAccountId()).ifPresent(ca -> {
            ca.setMobileAccessPolicyVersion(ca.getMobileAccessPolicyVersion() + 1);
            customerAccountRepository.save(ca);
        });

        auditService.log(callerUserId, entitlement.getCustomerAccountId(), entitlement.getDatasetId(), null,
                "DIRECT_ENTITLEMENT_REVOKED", AuditOutcome.SUCCESS,
                "entitlement", entitlement.getId().toString(),
                "{\"reason\":\"" + entitlement.getRevokeReason() + "\"}", null);

        ElectionType electionType = electionTypeRepository.findById(entitlement.getElectionTypeId()).orElse(null);
        return mapToResponse(entitlement, electionType);
    }

    public void recomputeLongTermRetention(Long datasetId, Instant now) {
        datasetRepository.findById(datasetId).ifPresent(ds -> {
            if (ds.getStorageTier() == DatasetTier.LONG_TERM) {
                String maxSql = "SELECT MAX(valid_until) FROM entitlement WHERE dataset_id = ? AND status = 'ACTIVE'";
                Timestamp maxTs = jdbcTemplate.queryForObject(maxSql, Timestamp.class, datasetId);
                Instant recomputed = (maxTs != null)
                        ? maxTs.toInstant().plus(30, ChronoUnit.DAYS)
                        : now.plus(30, ChronoUnit.DAYS);
                ds.setDeleteAfter(recomputed);
                datasetRepository.save(ds);
                log.info("Recomputed LONG_TERM dataset {} delete_after to {}", datasetId, recomputed);
            }
        });
    }

    private Dataset resolveDataset(DatasetTier tier, Map<String, Object> criteriaMap) {
        StringBuilder sql = new StringBuilder("SELECT DISTINCT v.dataset_id FROM voter v " +
                "JOIN dataset d ON d.id = v.dataset_id " +
                "WHERE d.storage_tier = ? AND d.status = 'IMPORTED'");

        List<Object> params = new ArrayList<>();
        params.add(tier.name());

        for (Map.Entry<String, Object> entry : criteriaMap.entrySet()) {
            sql.append(" AND v.").append(entry.getKey()).append(" = ?");
            params.add(entry.getValue());
        }

        List<Long> matchingDatasetIds = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> rs.getLong(1), params.toArray());

        if (matchingDatasetIds.isEmpty()) {
            throw new ApiException(ErrorCode.AREA_UNAVAILABLE, "Area not available for storage tier " + tier, HttpStatus.BAD_REQUEST);
        }

        if (matchingDatasetIds.size() > 1) {
            throw new ApiException(ErrorCode.AREA_MULTIPLE_DATASETS, "Multiple datasets found for this area in tier " + tier, HttpStatus.BAD_REQUEST);
        }

        Long datasetId = matchingDatasetIds.get(0);
        Dataset dataset = datasetRepository.findByIdForUpdate(datasetId)
                .orElseThrow(() -> new ResourceNotFoundException("Resolved dataset not found: " + datasetId));
        if (dataset.getStatus() != DatasetStatus.IMPORTED) {
            throw new ApiException(ErrorCode.AREA_UNAVAILABLE, "Dataset is not in IMPORTED state: " + dataset.getStatus(), HttpStatus.BAD_REQUEST);
        }
        return dataset;
    }

    private void checkAmbiguity(ElectionType electionType, Long datasetId, Map<String, Object> criteriaMap) {
        if (electionType.getGuardColumn() == null || electionType.getGuardColumn().isBlank()) {
            return;
        }

        String guardCol = electionType.getGuardColumn().trim().toLowerCase();
        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT ")
                .append(guardCol)
                .append(") FROM voter WHERE dataset_id = ?");

        List<Object> params = new ArrayList<>();
        params.add(datasetId);

        for (Map.Entry<String, Object> entry : criteriaMap.entrySet()) {
            sql.append(" AND ").append(entry.getKey()).append(" = ?");
            params.add(entry.getValue());
        }

        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        if (count != null && count > 1) {
            throw new ApiException(ErrorCode.AMBIGUOUS_AREA,
                    "Area contains multiple " + guardCol + " values; ambiguous area",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private Instant calculateValidUntil(Instant validFrom, int durationValue, DurationUnit durationUnit) {
        ZonedDateTime start = validFrom.atZone(KOLKATA_ZONE);
        ZonedDateTime end;
        switch (durationUnit) {
            case DAY -> end = start.plusDays(durationValue);
            case WEEK -> end = start.plusWeeks(durationValue);
            case MONTH -> end = start.plusMonths(durationValue);
            default -> throw new ApiException(ErrorCode.BAD_REQUEST, "Unsupported duration unit: " + durationUnit, HttpStatus.BAD_REQUEST);
        }
        return end.toInstant();
    }

    private EntitlementResponse mapToResponse(Entitlement e, ElectionType electionType) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String remaining;
        if (e.getStatus() == EntitlementStatus.REVOKED) {
            remaining = "Revoked";
        } else if (now.isBefore(e.getValidFrom())) {
            remaining = "Starts on " + e.getValidFrom();
        } else if (now.isAfter(e.getValidUntil())) {
            remaining = "Expired";
        } else {
            long days = Duration.between(now, e.getValidUntil()).toDays();
            if (days > 0) {
                remaining = days + " days remaining";
            } else {
                long hours = Duration.between(now, e.getValidUntil()).toHours();
                remaining = hours + " hours remaining";
            }
        }

        return EntitlementResponse.builder()
                .id(e.getId())
                .customerAccountId(e.getCustomerAccountId())
                .electionTypeId(e.getElectionTypeId())
                .electionTypeCode(electionType != null ? electionType.getCode().name() : "UNKNOWN")
                .electionTypeName(electionType != null ? electionType.getDisplayName() : "Unknown")
                .storageTier(e.getStorageTier())
                .criteria(e.getCriteria())
                .criteriaHash(e.getCriteriaHash())
                .scopeLabel(e.getScopeLabel())
                .source(e.getSource())
                .saleLineId(e.getSaleLineId())
                .status(e.getStatus())
                .validFrom(e.getValidFrom())
                .validUntil(e.getValidUntil())
                .remainingFormatted(remaining)
                .mobileAccess(e.isMobileAccess())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
