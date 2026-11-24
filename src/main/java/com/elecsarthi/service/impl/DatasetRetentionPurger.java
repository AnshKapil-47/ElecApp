package com.elecsarthi.service.impl;

import com.elecsarthi.entity.Dataset;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatasetRetentionPurger {

    private final DatasetRepository datasetRepository;
    private final EntitlementRepository entitlementRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Purges a single expired dataset in its own isolated transaction.
     * Ensures partition detachment and dropping do not abort or rollback adjacent dataset purges.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean purgeSingleDataset(Long datasetId, Instant now) {
        Dataset ds = datasetRepository.findByIdForUpdate(datasetId).orElse(null);
        if (ds == null || (ds.getStatus() != DatasetStatus.IMPORTED && ds.getStatus() != DatasetStatus.SUPERSEDED)) {
            log.info("Dataset {} is no longer in IMPORTED or SUPERSEDED status or not found. Skipping purge.", datasetId);
            return false;
        }

        if (ds.getDeleteAfter() == null || ds.getDeleteAfter().isAfter(now)) {
            log.info("Dataset {} delete_after is null or in future. Skipping purge.", datasetId);
            return false;
        }

        log.info("Evaluating expired dataset ID={} name='{}' tier={}", ds.getId(), ds.getName(), ds.getStorageTier());

        // Safety Check 1: Refuse to purge if active entitlements exist
        boolean hasActiveEntitlements = entitlementRepository.findByDatasetId(ds.getId()).stream()
                .anyMatch(e -> e.getStatus() == EntitlementStatus.ACTIVE && e.getValidUntil().isAfter(now));
        if (hasActiveEntitlements) {
            log.warn("Cannot purge dataset {}: Active entitlements exist. Skipping purge.", ds.getId());
            return false;
        }

        // Safety Check 2: Refuse to purge if unexpired offline leases exist
        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now);
        String leaseSql = "SELECT count(*) FROM offline_lease l " +
                "JOIN booth_assignment b ON l.booth_assignment_id = b.id " +
                "JOIN entitlement e ON b.entitlement_id = e.id " +
                "WHERE e.dataset_id = ? AND l.expires_at > ? AND l.revoked_at IS NULL";
        Long activeLeaseCount = jdbcTemplate.queryForObject(leaseSql, Long.class, ds.getId(), nowTs);
        if (activeLeaseCount != null && activeLeaseCount > 0) {
            log.warn("Cannot purge dataset {}: {} unexpired offline leases exist. Skipping purge.", ds.getId(), activeLeaseCount);
            return false;
        }

        // Audit purge before dropping
        auditService.logDetails(null, null, ds.getId(), null,
                "DATASET_AUTO_PURGED", AuditOutcome.SUCCESS,
                "dataset", ds.getId().toString(),
                Map.of("name", ds.getName(), "tier", ds.getStorageTier().name(), "deleteAfter", ds.getDeleteAfter().toString()),
                null);

        // Mark status in metadata to DELETING
        ds.setStatus(DatasetStatus.DELETING);
        datasetRepository.saveAndFlush(ds);

        // Drop database partitions
        jdbcTemplate.execute("SELECT detach_and_drop_dataset_partitions(" + ds.getId() + "::bigint);");

        log.info("Successfully dropped partitions and marked DELETING for dataset {}", ds.getId());
        return true;
    }
}
