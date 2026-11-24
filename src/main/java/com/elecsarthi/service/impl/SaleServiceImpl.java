package com.elecsarthi.service.impl;

import com.elecsarthi.dto.request.CreateSaleRequest;
import com.elecsarthi.dto.request.SaleLineRequest;
import com.elecsarthi.dto.request.VoidSaleRequest;
import com.elecsarthi.dto.response.SaleLineResponse;
import com.elecsarthi.dto.response.SaleResponse;
import com.elecsarthi.entity.AgentSale;
import com.elecsarthi.entity.AgentSaleLine;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SaleStatus;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.mapper.SaleMapper;
import com.elecsarthi.repository.AgentSaleLineRepository;
import com.elecsarthi.repository.AgentSaleRepository;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.OfflineLeaseRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AreaResolver;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.CriteriaNormalizer.NormalizedCriteria;
import com.elecsarthi.service.CriteriaNormalizer;
import com.elecsarthi.service.DatasetService;
import com.elecsarthi.service.SaleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent sales (quantity only: area, duration, voter-count snapshot; no prices) and their Owner-only void.
 * A sale, its lines and one entitlement per line are written in one transaction. Sale lines are never
 * updated; voiding changes only the sale's void columns and revokes its entitlements.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaleServiceImpl implements SaleService {

    private final AgentSaleRepository agentSaleRepository;
    private final AgentSaleLineRepository agentSaleLineRepository;
    private final EntitlementRepository entitlementRepository;
    private final OfflineLeaseRepository offlineLeaseRepository;
    private final CustomerAccountRepository customerAccountRepository;
    private final AppUserRepository appUserRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final DatasetService datasetService;
    private final CriteriaNormalizer criteriaNormalizer;
    private final AuditService auditService;
    private final SaleMapper saleMapper;
    private final AreaResolver areaResolver;
    private final Clock clock;

    /** A validated sale line: its area resolved to one dataset and its validity window computed. */
    private record PlannedLine(SaleLineRequest request, NormalizedCriteria criteria, Dataset dataset,
                               int voterCount, Instant validFrom, Instant validUntil) {
    }

    @Override
    @Transactional
    public SaleResponse createSale(Long agentUserId, CreateSaleRequest request) {
        AppUser agent = appUserRepository.findById(agentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent user not found"));
        if (agent.getRole() != Role.AGENT) {
            throw new ForbiddenException("Only agents can perform sales");
        }

        CustomerAccount customer = customerAccountRepository.requireById(request.getCustomerAccountId());
        if (customer.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Customer account is suspended or not active", HttpStatus.BAD_REQUEST);
        }
        if (!customer.isOwnedByAgent(agentUserId)) {
            throw new ForbiddenException("You are not the owning agent for this customer");
        }

        ElectionType electionType = electionTypeRepository.requireById(request.getElectionTypeId());
        if (!electionType.isActive()) {
            throw new BadRequestException("Election type is not active");
        }

        List<NormalizedCriteria> criteria = new ArrayList<>();
        for (SaleLineRequest lineRequest : request.getLines()) {
            criteria.add(criteriaNormalizer.normalize(electionType, lineRequest.getCriteria()));
        }

        String requestSha256 = requestFingerprint(customer, electionType, request, criteria);
        Optional<AgentSale> replay = agentSaleRepository.findBySoldByUserIdAndIdempotencyKey(agentUserId, request.getIdempotencyKey());
        if (replay.isPresent()) {
            if (!replay.get().getRequestSha256().equals(requestSha256)) {
                throw new ConflictException("Idempotency key reused with different payload");
            }
            log.info("Idempotent replay for sale id={}", replay.get().getId());
            return toResponse(replay.get());
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<PlannedLine> plan = planLines(customer, electionType, request, criteria, now);

        AgentSale sale = agentSaleRepository.save(AgentSale.builder()
                .soldByUserId(agentUserId)
                .customerAccountId(customer.getId())
                .electionTypeId(electionType.getId())
                .storageTier(request.getStorageTier())
                .idempotencyKey(request.getIdempotencyKey())
                .requestSha256(requestSha256)
                .soldAt(now)
                .status(SaleStatus.COMPLETED)
                .build());

        List<SaleLineResponse> lineResponses = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            lineResponses.add(persistLine(sale, (short) (i + 1), plan.get(i), customer, agentUserId, now));
        }

        for (PlannedLine line : plan) {
            datasetService.recomputeLongTermRetention(line.dataset().getId(), now);
        }

        auditService.logDetails(agentUserId, customer.getId(), null, null, "AGENT_SALE_CREATED", AuditOutcome.SUCCESS,
                "agent_sale", sale.getId().toString(), Map.of("lines", lineResponses.size()), null);

        return saleMapper.toResponse(sale, electionType.getCode().name(), lineResponses);
    }

    /**
     * Identifies the request content for idempotency: the same key with the same canonical lines is a replay,
     * the same key with different lines is a conflict. The format must stay stable, since stored hashes are
     * compared against it.
     */
    private String requestFingerprint(CustomerAccount customer, ElectionType electionType, CreateSaleRequest request,
                                      List<NormalizedCriteria> criteria) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            SaleLineRequest line = request.getLines().get(i);
            lines.add(line.getDurationValue() + ":" + line.getDurationUnit().name() + ":" + criteria.get(i).getCanonicalJson());
        }
        return CryptoUtils.sha256Hex(customer.getId() + ":" + electionType.getId() + ":"
                + request.getStorageTier().name() + ":[" + String.join(";", lines) + "]");
    }

    private List<PlannedLine> planLines(CustomerAccount customer, ElectionType electionType, CreateSaleRequest request,
                                        List<NormalizedCriteria> criteria, Instant now) {
        Set<String> seenAreas = new HashSet<>();
        List<PlannedLine> plan = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            SaleLineRequest lineRequest = request.getLines().get(i);
            NormalizedCriteria area = criteria.get(i);
            if (!seenAreas.add(area.getCriteriaHash())) {
                throw new BadRequestException("Duplicate criteria among sale lines: " + area.getScopeLabel());
            }

            Dataset dataset = areaResolver.resolveDataset(request.getStorageTier(), electionType, area);
            int voterCount = areaResolver.countVoters(dataset.getId(), area);
            AreaResolver.ValidityWindow window = areaResolver.validityWindow(customer.getId(), electionType,
                    request.getStorageTier(), area, dataset, lineRequest.getDurationValue(), lineRequest.getDurationUnit(), now);
            plan.add(new PlannedLine(lineRequest, area, dataset, voterCount, window.validFrom(), window.validUntil()));
        }
        return plan;
    }

    private SaleLineResponse persistLine(AgentSale sale, short lineNo, PlannedLine line, CustomerAccount customer,
                                         Long agentUserId, Instant now) {
        NormalizedCriteria area = line.criteria();
        AgentSaleLine saleLine = agentSaleLineRepository.save(AgentSaleLine.builder()
                .saleId(sale.getId())
                .lineNo(lineNo)
                .datasetId(line.dataset().getId())
                .datasetName(line.dataset().getName())
                .storageTier(sale.getStorageTier())
                .criteria(area.getCanonicalJson())
                .criteriaHash(area.getCriteriaHash())
                .scopeLabel(area.getScopeLabel())
                .voterCountSnapshot(line.voterCount())
                .durationValue(line.request().getDurationValue())
                .durationUnit(line.request().getDurationUnit())
                .validFrom(line.validFrom())
                .validUntil(line.validUntil())
                .build());

        Entitlement entitlement = entitlementRepository.save(Entitlement.builder()
                .customerAccountId(customer.getId())
                .electionTypeId(sale.getElectionTypeId())
                .datasetId(line.dataset().getId())
                .storageTier(sale.getStorageTier())
                .criteria(area.getCanonicalJson())
                .criteriaHash(area.getCriteriaHash())
                .scopeLabel(area.getScopeLabel())
                .source(EntitlementSource.SALE)
                .saleLineId(saleLine.getId())
                .status(EntitlementStatus.ACTIVE)
                .validFrom(line.validFrom())
                .validUntil(line.validUntil())
                .mobileAccess(false)
                .createdByUserId(agentUserId)
                .createdAt(now)
                .build());

        return saleMapper.toLineResponse(saleLine, saleLine.getStorageTier(), saleLine.getScopeLabel(), entitlement.getId());
    }

    @Override
    @Transactional
    public SaleResponse voidSale(Long ownerUserId, Long saleId, VoidSaleRequest request) {
        AppUser owner = appUserRepository.findById(ownerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (owner.getRole() != Role.OWNER) {
            throw new ForbiddenException("Only OWNER can void a sale");
        }
        AgentSale sale = agentSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found"));
        if (sale.getStatus() == SaleStatus.VOIDED) {
            throw new BadRequestException("Sale is already voided");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        sale.setStatus(SaleStatus.VOIDED);
        sale.setVoidedAt(now);
        sale.setVoidReason(request.getVoidReason());
        sale.setVoidedByUserId(ownerUserId);
        agentSaleRepository.save(sale);

        List<Long> lineIds = agentSaleLineRepository.findBySaleIdOrderByLineNoAsc(saleId).stream()
                .map(AgentSaleLine::getId).toList();
        Set<Long> affectedDatasetIds = new HashSet<>();
        for (Entitlement entitlement : entitlementRepository.findBySaleLineIdIn(lineIds)) {
            entitlement.setStatus(EntitlementStatus.REVOKED);
            entitlement.setRevokedAt(now);
            entitlement.setRevokeReason("Sale voided: " + request.getVoidReason());
            entitlement.setRevokedByUserId(ownerUserId);
            entitlementRepository.saveAndFlush(entitlement);
            offlineLeaseRepository.revokeActiveByEntitlementId(entitlement.getId(), now, ownerUserId);
            affectedDatasetIds.add(entitlement.getDatasetId());
        }
        affectedDatasetIds.forEach(datasetId -> datasetService.recomputeLongTermRetention(datasetId, now));

        // Bumping the version makes the Customer's apps re-check their mobile-number access.
        customerAccountRepository.findById(sale.getCustomerAccountId()).ifPresent(customer -> {
            customer.bumpMobileAccessPolicyVersion();
            customerAccountRepository.save(customer);
        });

        auditService.logDetails(ownerUserId, sale.getCustomerAccountId(), null, null, "SALE_VOIDED", AuditOutcome.SUCCESS,
                "agent_sale", sale.getId().toString(),
                Map.of("reason", request.getVoidReason() != null ? request.getVoidReason() : ""), null);

        return toResponse(sale);
    }

    @Override
    @Transactional(readOnly = true)
    public SaleResponse getSale(Long callerUserId, Role callerRole, Long saleId) {
        AgentSale sale = agentSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale not found"));
        if (callerRole == Role.SUBOWNER) {
            throw new ForbiddenException("Subowners are not permitted to view sales records");
        }
        if (callerRole == Role.AGENT && !sale.getSoldByUserId().equals(callerUserId)) {
            throw new ForbiddenException("Agents can only view their own sales");
        }
        return toResponse(sale);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SaleResponse> getSalesByAgent(Long agentUserId) {
        return toResponses(agentSaleRepository.findBySoldByUserIdOrderBySoldAtDesc(agentUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SaleResponse> getSalesByCustomer(Long callerUserId, Role callerRole, Long customerAccountId) {
        if (callerRole == Role.SUBOWNER) {
            throw new ForbiddenException("Subowners cannot view sales records");
        }
        if (callerRole == Role.AGENT && !customerAccountRepository.requireById(customerAccountId).isOwnedByAgent(callerUserId)) {
            throw new ForbiddenException("You are not the owning agent for this customer");
        }
        return toResponses(agentSaleRepository.findByCustomerAccountIdOrderBySoldAtDesc(customerAccountId));
    }

    private SaleResponse toResponse(AgentSale sale) {
        return toResponses(List.of(sale)).get(0);
    }

    /** Builds responses for many sales with a fixed number of queries, however many sales and lines there are. */
    private List<SaleResponse> toResponses(List<AgentSale> sales) {
        if (sales.isEmpty()) {
            return List.of();
        }
        List<Long> saleIds = sales.stream().map(AgentSale::getId).toList();
        Map<Long, List<AgentSaleLine>> linesBySale = agentSaleLineRepository
                .findBySaleIdInOrderBySaleIdAscLineNoAsc(saleIds).stream()
                .collect(Collectors.groupingBy(AgentSaleLine::getSaleId));
        Collection<Long> lineIds = linesBySale.values().stream().flatMap(List::stream).map(AgentSaleLine::getId).toList();
        Map<Long, Long> entitlementIdByLine = entitlementRepository.findBySaleLineIdIn(lineIds).stream()
                .collect(Collectors.toMap(Entitlement::getSaleLineId, Entitlement::getId));
        Map<Short, String> electionTypeCodes = electionTypeRepository.findAll().stream()
                .collect(Collectors.toMap(ElectionType::getId, type -> type.getCode().name()));

        return sales.stream()
                .map(sale -> saleMapper.toResponse(sale,
                        electionTypeCodes.getOrDefault(sale.getElectionTypeId(), "UNKNOWN"),
                        linesBySale.getOrDefault(sale.getId(), List.of()).stream()
                                .map(line -> saleMapper.toLineResponse(line, line.getStorageTier(), line.getScopeLabel(),
                                        entitlementIdByLine.get(line.getId())))
                                .toList()))
                .toList();
    }
}
