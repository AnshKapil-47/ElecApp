package com.elecsarthi.service.impl;

import java.time.Clock;
import com.elecsarthi.dto.request.DirectEntitlementRequest;
import com.elecsarthi.dto.response.EntitlementResponse;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.*;
import com.elecsarthi.repository.OfflineLeaseRepository;
import com.elecsarthi.service.AreaResolver;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.CriteriaNormalizer;
import com.elecsarthi.service.DatasetService;
import com.elecsarthi.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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
    private final OfflineLeaseRepository offlineLeaseRepository;
    private final AreaResolver areaResolver;
    private final DatasetService datasetService;
    private final Clock clock;
    private final CriteriaNormalizer criteriaNormalizer;
    private final AuditService auditService;

    @Override
    @Transactional
    public EntitlementResponse createDirectEntitlement(Long callerUserId, Role callerRole, DirectEntitlementRequest request) {
        if (callerRole != Role.OWNER && callerRole != Role.SUBOWNER) {
            throw new ForbiddenException("Only OWNER or SUBOWNER can create direct entitlements");
        }

        if (request.isMobileAccess() && callerRole != Role.OWNER) {
            throw new ForbiddenException("Only OWNER can enable mobile number access");
        }

        CustomerAccount customer = customerAccountRepository.requireById(request.getCustomerAccountId());

        if (customer.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Customer account is suspended or not active", HttpStatus.BAD_REQUEST);
        }

        ElectionType electionType = electionTypeRepository.requireById(request.getElectionTypeId());

        if (!electionType.isActive()) {
            throw new BadRequestException("Election type is not active");
        }

        CriteriaNormalizer.NormalizedCriteria normalized = criteriaNormalizer.normalize(electionType, request.getCriteria());

        Dataset dataset = areaResolver.resolveDataset(request.getStorageTier(), electionType, normalized);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AreaResolver.ValidityWindow window = areaResolver.validityWindow(customer.getId(), electionType,
                request.getStorageTier(), normalized, dataset, request.getDurationValue(), request.getDurationUnit(), now);
        Instant validFrom = window.validFrom();
        Instant validUntil = window.validUntil();

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
            datasetService.recomputeLongTermRetention(dataset.getId(), now);
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
        Entitlement entitlement = entitlementRepository.requireById(entitlementId);

        entitlement.setMobileAccess(enabled);
        entitlement.setMobileAccessChangedAt(Instant.now());
        entitlement.setMobileAccessChangedBy(ownerUserId);
        entitlement = entitlementRepository.save(entitlement);

        // Bump customer mobileAccessPolicyVersion
        customerAccountRepository.findById(entitlement.getCustomerAccountId()).ifPresent(ca -> {
            ca.bumpMobileAccessPolicyVersion();
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
        CustomerAccount customer = customerAccountRepository.requireById(customerAccountId);

        if (callerRole == Role.SUBUSER) {
            throw new ForbiddenException("Subusers cannot view customer entitlements");
        }

        if (callerRole == Role.AGENT) {
            if (!customer.isOwnedByAgent(callerUserId)) {
                throw new ForbiddenException("You are not the owning agent for this customer");
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
        Entitlement entitlement = entitlementRepository.requireById(entitlementId);

        if (callerRole == Role.SUBUSER) {
            throw new ForbiddenException("Subusers cannot view entitlements");
        }

        if (callerRole == Role.AGENT) {
            CustomerAccount customer = customerAccountRepository.requireById(entitlement.getCustomerAccountId());
            if (!customer.isOwnedByAgent(callerUserId)) {
                throw new ForbiddenException("Access denied");
            }
        }

        ElectionType electionType = electionTypeRepository.findById(entitlement.getElectionTypeId()).orElse(null);
        return mapToResponse(entitlement, electionType);
    }

    @Override
    @Transactional
    public EntitlementResponse revokeEntitlement(Long callerUserId, Role callerRole, Long entitlementId, String reason) {
        if (callerRole != Role.OWNER && callerRole != Role.SUBOWNER) {
            throw new ForbiddenException("Only OWNER or SUBOWNER can revoke entitlements");
        }

        Entitlement entitlement = entitlementRepository.requireById(entitlementId);

        if (entitlement.getStatus() == EntitlementStatus.REVOKED) {
            throw new BadRequestException("Entitlement is already revoked");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        entitlement.setStatus(EntitlementStatus.REVOKED);
        entitlement.setRevokedAt(now);
        entitlement.setRevokeReason(reason != null && !reason.isBlank() ? reason : "Revoked by " + callerRole.name());
        entitlement.setRevokedByUserId(callerUserId);
        entitlement = entitlementRepository.saveAndFlush(entitlement);

        // Cascade-revoke offline leases for booth assignments under this entitlement
        offlineLeaseRepository.revokeActiveByEntitlementId(entitlementId, now, callerUserId);

        // Recompute delete_after for LONG_TERM dataset
        datasetService.recomputeLongTermRetention(entitlement.getDatasetId(), now);

        // Increment customer's mobile access policy version
        customerAccountRepository.findById(entitlement.getCustomerAccountId()).ifPresent(ca -> {
            ca.bumpMobileAccessPolicyVersion();
            customerAccountRepository.save(ca);
        });

        auditService.log(callerUserId, entitlement.getCustomerAccountId(), entitlement.getDatasetId(), null,
                "DIRECT_ENTITLEMENT_REVOKED", AuditOutcome.SUCCESS,
                "entitlement", entitlement.getId().toString(),
                "{\"reason\":\"" + entitlement.getRevokeReason() + "\"}", null);

        ElectionType electionType = electionTypeRepository.findById(entitlement.getElectionTypeId()).orElse(null);
        return mapToResponse(entitlement, electionType);
    }

    private EntitlementResponse mapToResponse(Entitlement e, ElectionType electionType) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
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
