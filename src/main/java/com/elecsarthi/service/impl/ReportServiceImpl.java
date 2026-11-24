package com.elecsarthi.service.impl;

import com.elecsarthi.dto.response.*;
import com.elecsarthi.entity.AgentSale;
import com.elecsarthi.entity.AgentSaleLine;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SaleStatus;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.repository.AgentSaleLineRepository;
import com.elecsarthi.repository.AgentSaleRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final AgentSaleRepository agentSaleRepository;
    private final AgentSaleLineRepository agentSaleLineRepository;
    private final CustomerAccountRepository customerAccountRepository;
    private final ElectionTypeRepository electionTypeRepository;

    @Override
    @Transactional(readOnly = true)
    public SalesReportResponse getSalesReport(Long callerUserId, Role callerRole, Long agentId, Instant from, Instant to) {
        if (callerRole == Role.SUBOWNER) {
            throw new ForbiddenException("Subowners are not permitted to view sales reports");
        }

        if (callerRole != Role.OWNER && callerRole != Role.AGENT) {
            throw new ForbiddenException("Access denied to sales reports");
        }

        Long targetAgentId = agentId;
        if (callerRole == Role.AGENT) {
            targetAgentId = callerUserId;
        }

        List<AgentSale> allSales;
        if (targetAgentId != null) {
            allSales = agentSaleRepository.findBySoldByUserIdOrderBySoldAtDesc(targetAgentId);
        } else {
            allSales = agentSaleRepository.findAll();
        }

        // Date range filter
        List<AgentSale> filteredSales = allSales.stream()
                .filter(s -> from == null || !s.getSoldAt().isBefore(from))
                .filter(s -> to == null || !s.getSoldAt().isAfter(to))
                .sorted(Comparator.comparing(AgentSale::getSoldAt).reversed())
                .collect(Collectors.toList());

        Map<Short, ElectionType> electionTypes = electionTypeRepository.findAll().stream()
                .collect(Collectors.toMap(ElectionType::getId, et -> et));

        Map<Long, String> customerNames = new HashMap<>();

        long totalSales = filteredSales.size();
        long voidedSalesCount = 0;
        long totalLines = 0;
        long voidedLinesCount = 0;
        long totalVoters = 0;

        Map<String, long[]> typeStats = new HashMap<>(); // code -> [salesCount, voterCount]
        Map<String, ScopeStat> scopeStats = new HashMap<>(); // hash -> ScopeStat
        List<SaleRecordResponse> records = new ArrayList<>();

        for (AgentSale sale : filteredSales) {
            boolean isVoided = (sale.getStatus() == SaleStatus.VOIDED);
            if (isVoided) {
                voidedSalesCount++;
            }

            ElectionType et = electionTypes.get(sale.getElectionTypeId());
            String typeCode = (et != null ? et.getCode().name() : "UNKNOWN");

            String custName = customerNames.computeIfAbsent(sale.getCustomerAccountId(), cid ->
                    customerAccountRepository.findById(cid)
                            .map(CustomerAccount::getCustomerName)
                            .orElse("Customer #" + cid));

            List<AgentSaleLine> lines = agentSaleLineRepository.findBySaleIdOrderByLineNoAsc(sale.getId());
            totalLines += lines.size();
            if (isVoided) {
                voidedLinesCount += lines.size();
            }

            for (AgentSaleLine line : lines) {
                totalVoters += line.getVoterCountSnapshot();

                long[] tCounts = typeStats.computeIfAbsent(typeCode, k -> new long[2]);
                tCounts[0]++;
                tCounts[1] += line.getVoterCountSnapshot();

                ScopeStat ss = scopeStats.computeIfAbsent(line.getCriteriaHash(), k ->
                        new ScopeStat(line.getScopeLabel(), line.getCriteriaHash()));
                ss.timesSold++;
                ss.voterCount += line.getVoterCountSnapshot();

                records.add(SaleRecordResponse.builder()
                        .saleId(sale.getId())
                        .soldAt(sale.getSoldAt())
                        .agentId(sale.getSoldByUserId())
                        .customerAccountId(sale.getCustomerAccountId())
                        .customerName(custName)
                        .electionTypeCode(typeCode)
                        .scopeLabel(line.getScopeLabel())
                        .voterCount(line.getVoterCountSnapshot())
                        .duration(line.getDurationValue() + " " + line.getDurationUnit().name())
                        .validFrom(line.getValidFrom())
                        .validUntil(line.getValidUntil())
                        .status(sale.getStatus())
                        .build());
            }
        }

        long totalCustomersAcquired = filteredSales.stream()
                .map(AgentSale::getCustomerAccountId)
                .distinct()
                .count();

        List<ElectionTypeQuantityResponse> typeBreakdown = typeStats.entrySet().stream()
                .map(e -> ElectionTypeQuantityResponse.builder()
                        .electionTypeCode(e.getKey())
                        .salesCount(e.getValue()[0])
                        .voterCount(e.getValue()[1])
                        .build())
                .sorted(Comparator.comparing(ElectionTypeQuantityResponse::getSalesCount).reversed())
                .collect(Collectors.toList());

        List<ScopeQuantityResponse> scopeBreakdown = scopeStats.values().stream()
                .map(s -> ScopeQuantityResponse.builder()
                        .scopeLabel(s.scopeLabel)
                        .criteriaHash(s.criteriaHash)
                        .timesSold(s.timesSold)
                        .voterCount(s.voterCount)
                        .build())
                .sorted(Comparator.comparing(ScopeQuantityResponse::getTimesSold).reversed())
                .collect(Collectors.toList());

        return SalesReportResponse.builder()
                .agentId(targetAgentId)
                .totalSales(totalSales)
                .totalLines(totalLines)
                .totalVoters(totalVoters)
                .totalCustomersAcquired(totalCustomersAcquired)
                .voidedSalesCount(voidedSalesCount)
                .voidedLinesCount(voidedLinesCount)
                .electionTypeBreakdown(typeBreakdown)
                .scopeBreakdown(scopeBreakdown)
                .records(records)
                .build();
    }

    private static class ScopeStat {
        String scopeLabel;
        String criteriaHash;
        long timesSold = 0;
        long voterCount = 0;

        ScopeStat(String scopeLabel, String criteriaHash) {
            this.scopeLabel = scopeLabel;
            this.criteriaHash = criteriaHash;
        }
    }
}
