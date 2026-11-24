package com.elecsarthi.service.impl;

import com.elecsarthi.dto.request.CreateSaleRequest;
import com.elecsarthi.dto.request.SaleLineRequest;
import com.elecsarthi.dto.request.VoidSaleRequest;
import com.elecsarthi.dto.response.SaleLineResponse;
import com.elecsarthi.dto.response.SaleResponse;
import com.elecsarthi.entity.*;
import com.elecsarthi.enums.*;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.*;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.CriteriaNormalizer;
import com.elecsarthi.service.SaleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SaleServiceImpl implements SaleService {

    private final AgentSaleRepository agentSaleRepository;
    private final AgentSaleLineRepository agentSaleLineRepository;
    private final EntitlementRepository entitlementRepository;
    private final CustomerAccountRepository customerAccountRepository;
    private final AppUserRepository appUserRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final DatasetRepository datasetRepository;
    private final CriteriaNormalizer criteriaNormalizer;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    private static final ZoneId KOLKATA_ZONE = ZoneId.of("Asia/Kolkata");

    private static class ProcessedLine {
        CriteriaNormalizer.NormalizedCriteria normalizedCriteria;
        Dataset dataset;
        int voterCount;
        Instant validFrom;
        Instant validUntil;
        SaleLineRequest lineRequest;
    }

    @Override
    @Transactional
    public SaleResponse createSale(Long agentUserId, CreateSaleRequest request) {
        AppUser agentUser = appUserRepository.findById(agentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent user not found"));

        if (agentUser.getRole() != Role.AGENT) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only agents can perform sales", HttpStatus.FORBIDDEN);
        }

        CustomerAccount customer = customerAccountRepository.findById(request.getCustomerAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer account not found"));

        if (customer.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Customer account is suspended or not active", HttpStatus.BAD_REQUEST);
        }

        if (customer.getOwningAgentUserId() == null || !customer.getOwningAgentUserId().equals(agentUserId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You are not the owning agent for this customer", HttpStatus.FORBIDDEN);
        }

        ElectionType electionType = electionTypeRepository.findById(request.getElectionTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found"));

        if (!electionType.isActive()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Election type is not active", HttpStatus.BAD_REQUEST);
        }

        // Canonical string for idempotency check
        StringBuilder canonicalPayload = new StringBuilder();
        canonicalPayload.append(customer.getId()).append(":")
                .append(electionType.getId()).append(":")
                .append(request.getStorageTier().name()).append(":[");

        for (int i = 0; i < request.getLines().size(); i++) {
            SaleLineRequest line = request.getLines().get(i);
            if (i > 0) canonicalPayload.append(";");
            canonicalPayload.append(line.getDurationValue()).append(":")
                    .append(line.getDurationUnit().name()).append(":")
                    .append(new TreeMap<>(line.getCriteria()));
        }
        canonicalPayload.append("]");
        String requestSha256 = CryptoUtils.sha256Hex(canonicalPayload.toString());

        // Check idempotency
        Optional<AgentSale> existingSaleOpt = agentSaleRepository.findBySoldByUserIdAndIdempotencyKey(agentUserId, request.getIdempotencyKey());
        if (existingSaleOpt.isPresent()) {
            AgentSale existing = existingSaleOpt.get();
            if (existing.getRequestSha256().equals(requestSha256)) {
                log.info("Idempotent replay for sale id={}", existing.getId());
                return mapToSaleResponse(existing, electionType.getCode().name());
            } else {
                throw new ConflictException("Idempotency key reused with different payload");
            }
        }

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Set<String> seenHashes = new HashSet<>();
        List<ProcessedLine> processedLines = new ArrayList<>();

        for (SaleLineRequest lineReq : request.getLines()) {
            CriteriaNormalizer.NormalizedCriteria normalized = criteriaNormalizer.normalize(electionType, lineReq.getCriteria());
            if (!seenHashes.add(normalized.getCriteriaHash())) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Duplicate criteria among sale lines: " + normalized.getScopeLabel(), HttpStatus.BAD_REQUEST);
            }

            // Area resolution
            Dataset dataset = resolveDataset(request.getStorageTier(), normalized.getCriteriaMap());

            // Ambiguity guard
            checkAmbiguity(electionType, dataset.getId(), normalized.getCriteriaMap());

            // Voter count snapshot
            int voterCount = calculateVoterCount(dataset.getId(), normalized.getCriteriaMap());

            // Back-to-back renewal calculation
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

            Instant validUntil = calculateValidUntil(validFrom, lineReq.getDurationValue(), lineReq.getDurationUnit());

            // MASTER tier check
            if (request.getStorageTier() == DatasetTier.MASTER) {
                if (dataset.getDeleteAfter() != null && validUntil.isAfter(dataset.getDeleteAfter())) {
                    throw new ApiException(ErrorCode.BAD_REQUEST,
                            "Validity until " + validUntil + " exceeds MASTER dataset expiration " + dataset.getDeleteAfter(),
                            HttpStatus.BAD_REQUEST);
                }
            }

            ProcessedLine pl = new ProcessedLine();
            pl.normalizedCriteria = normalized;
            pl.dataset = dataset;
            pl.voterCount = voterCount;
            pl.validFrom = validFrom;
            pl.validUntil = validUntil;
            pl.lineRequest = lineReq;
            processedLines.add(pl);
        }

        // Persist AgentSale
        AgentSale sale = AgentSale.builder()
                .soldByUserId(agentUserId)
                .customerAccountId(customer.getId())
                .electionTypeId(electionType.getId())
                .storageTier(request.getStorageTier())
                .idempotencyKey(request.getIdempotencyKey())
                .requestSha256(requestSha256)
                .soldAt(now)
                .status(SaleStatus.COMPLETED)
                .build();
        sale = agentSaleRepository.save(sale);

        List<SaleLineResponse> lineResponses = new ArrayList<>();

        for (int i = 0; i < processedLines.size(); i++) {
            ProcessedLine pl = processedLines.get(i);
            short lineNo = (short) (i + 1);

            AgentSaleLine saleLine = AgentSaleLine.builder()
                    .saleId(sale.getId())
                    .lineNo(lineNo)
                    .datasetId(pl.dataset.getId())
                    .datasetName(pl.dataset.getName())
                    .storageTier(request.getStorageTier())
                    .criteria(pl.normalizedCriteria.getCanonicalJson())
                    .criteriaHash(pl.normalizedCriteria.getCriteriaHash())
                    .scopeLabel(pl.normalizedCriteria.getScopeLabel())
                    .voterCountSnapshot(pl.voterCount)
                    .durationValue(pl.lineRequest.getDurationValue())
                    .durationUnit(pl.lineRequest.getDurationUnit())
                    .validFrom(pl.validFrom)
                    .validUntil(pl.validUntil)
                    .build();
            saleLine = agentSaleLineRepository.save(saleLine);

            Entitlement entitlement = Entitlement.builder()
                    .customerAccountId(customer.getId())
                    .electionTypeId(electionType.getId())
                    .datasetId(pl.dataset.getId())
                    .storageTier(request.getStorageTier())
                    .criteria(pl.normalizedCriteria.getCanonicalJson())
                    .criteriaHash(pl.normalizedCriteria.getCriteriaHash())
                    .scopeLabel(pl.normalizedCriteria.getScopeLabel())
                    .source(EntitlementSource.SALE)
                    .saleLineId(saleLine.getId())
                    .status(EntitlementStatus.ACTIVE)
                    .validFrom(pl.validFrom)
                    .validUntil(pl.validUntil)
                    .mobileAccess(false)
                    .createdByUserId(agentUserId)
                    .createdAt(now)
                    .build();
            entitlement = entitlementRepository.save(entitlement);

            lineResponses.add(SaleLineResponse.builder()
                    .id(saleLine.getId())
                    .lineNo(lineNo)
                    .storageTier(saleLine.getStorageTier())
                    .scopeLabel(saleLine.getScopeLabel())
                    .criteriaHash(saleLine.getCriteriaHash())
                    .voterCountSnapshot(saleLine.getVoterCountSnapshot())
                    .durationValue(saleLine.getDurationValue())
                    .durationUnit(saleLine.getDurationUnit())
                    .validFrom(saleLine.getValidFrom())
                    .validUntil(saleLine.getValidUntil())
                    .entitlementId(entitlement.getId())
                    .build());
        }

        auditService.log(agentUserId, customer.getId(), null, null,
                "AGENT_SALE_CREATED", AuditOutcome.SUCCESS,
                "agent_sale", sale.getId().toString(),
                "{\"lines\":" + lineResponses.size() + "}", null);

        return SaleResponse.builder()
                .id(sale.getId())
                .soldByUserId(sale.getSoldByUserId())
                .customerAccountId(sale.getCustomerAccountId())
                .electionTypeId(sale.getElectionTypeId())
                .electionTypeCode(electionType.getCode().name())
                .storageTier(sale.getStorageTier())
                .idempotencyKey(sale.getIdempotencyKey())
                .soldAt(sale.getSoldAt())
                .status(sale.getStatus())
                .lines(lineResponses)
                .build();
    }

    @Override
    @Transactional
    public SaleResponse voidSale(Long ownerUserId, Long saleId, VoidSaleRequest request) {
        AppUser owner = appUserRepository.findById(ownerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (owner.getRole() != Role.OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only OWNER can void a sale", HttpStatus.FORBIDDEN);
        }

        AgentSale sale = agentSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found"));

        if (sale.getStatus() == SaleStatus.VOIDED) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Sale is already voided", HttpStatus.BAD_REQUEST);
        }

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        sale.setStatus(SaleStatus.VOIDED);
        sale.setVoidedAt(now);
        sale.setVoidReason(request.getVoidReason());
        sale.setVoidedByUserId(ownerUserId);
        agentSaleRepository.save(sale);

        // Revoke matching entitlements
        List<AgentSaleLine> lines = agentSaleLineRepository.findBySaleIdOrderByLineNoAsc(saleId);
        for (AgentSaleLine line : lines) {
            entitlementRepository.findBySaleLineId(line.getId()).ifPresent(ent -> {
                ent.setStatus(EntitlementStatus.REVOKED);
                ent.setRevokedAt(now);
                ent.setRevokeReason("Sale voided: " + request.getVoidReason());
                ent.setRevokedByUserId(ownerUserId);
                entitlementRepository.save(ent);
            });
        }

        // Increment customer's mobile access policy version
        customerAccountRepository.findById(sale.getCustomerAccountId()).ifPresent(ca -> {
            ca.setMobileAccessPolicyVersion(ca.getMobileAccessPolicyVersion() + 1);
            customerAccountRepository.save(ca);
        });

        auditService.log(ownerUserId, sale.getCustomerAccountId(), null, null,
                "SALE_VOIDED", AuditOutcome.SUCCESS,
                "agent_sale", sale.getId().toString(),
                "{\"reason\":\"" + request.getVoidReason() + "\"}", null);

        String code = electionTypeRepository.findById(sale.getElectionTypeId())
                .map(et -> et.getCode().name())
                .orElse("UNKNOWN");

        return mapToSaleResponse(sale, code);
    }

    @Override
    @Transactional(readOnly = true)
    public SaleResponse getSale(Long callerUserId, Role callerRole, Long saleId) {
        AgentSale sale = agentSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found"));

        if (callerRole == Role.SUBOWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Subowners are not permitted to view sales records", HttpStatus.FORBIDDEN);
        }

        if (callerRole == Role.AGENT && !sale.getSoldByUserId().equals(callerUserId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Agents can only view their own sales", HttpStatus.FORBIDDEN);
        }

        String code = electionTypeRepository.findById(sale.getElectionTypeId())
                .map(et -> et.getCode().name())
                .orElse("UNKNOWN");

        return mapToSaleResponse(sale, code);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SaleResponse> getSalesByAgent(Long agentUserId) {
        return agentSaleRepository.findBySoldByUserIdOrderBySoldAtDesc(agentUserId).stream()
                .map(s -> {
                    String code = electionTypeRepository.findById(s.getElectionTypeId())
                            .map(et -> et.getCode().name())
                            .orElse("UNKNOWN");
                    return mapToSaleResponse(s, code);
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SaleResponse> getSalesByCustomer(Long callerUserId, Role callerRole, Long customerAccountId) {
        if (callerRole == Role.SUBOWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Subowners cannot view sales records", HttpStatus.FORBIDDEN);
        }

        if (callerRole == Role.AGENT) {
            CustomerAccount customer = customerAccountRepository.findById(customerAccountId)
                    .orElseThrow(() -> new ResourceNotFoundException("Customer account not found"));
            if (customer.getOwningAgentUserId() == null || !customer.getOwningAgentUserId().equals(callerUserId)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "You are not the owning agent for this customer", HttpStatus.FORBIDDEN);
            }
        }

        return agentSaleRepository.findByCustomerAccountIdOrderBySoldAtDesc(customerAccountId).stream()
                .map(s -> {
                    String code = electionTypeRepository.findById(s.getElectionTypeId())
                            .map(et -> et.getCode().name())
                            .orElse("UNKNOWN");
                    return mapToSaleResponse(s, code);
                })
                .collect(Collectors.toList());
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
        return datasetRepository.findById(datasetId)
                .orElseThrow(() -> new ResourceNotFoundException("Resolved dataset not found: " + datasetId));
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

    private int calculateVoterCount(Long datasetId, Map<String, Object> criteriaMap) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM voter WHERE dataset_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(datasetId);

        for (Map.Entry<String, Object> entry : criteriaMap.entrySet()) {
            sql.append(" AND ").append(entry.getKey()).append(" = ?");
            params.add(entry.getValue());
        }

        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count != null ? count : 0;
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

    private SaleResponse mapToSaleResponse(AgentSale sale, String electionTypeCode) {
        List<AgentSaleLine> lines = agentSaleLineRepository.findBySaleIdOrderByLineNoAsc(sale.getId());
        List<SaleLineResponse> lineResponses = lines.stream()
                .map(l -> {
                    Long entId = entitlementRepository.findBySaleLineId(l.getId())
                            .map(Entitlement::getId)
                            .orElse(null);
                    return SaleLineResponse.builder()
                            .id(l.getId())
                            .lineNo(l.getLineNo())
                            .storageTier(l.getStorageTier())
                            .scopeLabel(l.getScopeLabel())
                            .criteriaHash(l.getCriteriaHash())
                            .voterCountSnapshot(l.getVoterCountSnapshot())
                            .durationValue(l.getDurationValue())
                            .durationUnit(l.getDurationUnit())
                            .validFrom(l.getValidFrom())
                            .validUntil(l.getValidUntil())
                            .entitlementId(entId)
                            .build();
                })
                .collect(Collectors.toList());

        return SaleResponse.builder()
                .id(sale.getId())
                .soldByUserId(sale.getSoldByUserId())
                .customerAccountId(sale.getCustomerAccountId())
                .electionTypeId(sale.getElectionTypeId())
                .electionTypeCode(electionTypeCode)
                .storageTier(sale.getStorageTier())
                .idempotencyKey(sale.getIdempotencyKey())
                .soldAt(sale.getSoldAt())
                .status(sale.getStatus())
                .voidedAt(sale.getVoidedAt())
                .voidReason(sale.getVoidReason())
                .lines(lineResponses)
                .build();
    }
}
