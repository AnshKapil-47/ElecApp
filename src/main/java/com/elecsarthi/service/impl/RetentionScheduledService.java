package com.elecsarthi.service.impl;

import com.elecsarthi.entity.Dataset;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.SessionStatus;
import com.elecsarthi.enums.SyncStatus;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.SyncSessionRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionScheduledService {

    private final DatasetRepository datasetRepository;
    private final EntitlementRepository entitlementRepository;
    private final UserSessionRepository sessionRepository;
    private final SyncSessionRepository syncSessionRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    private static final long LOCK_DATASET_PURGE = 1001L;
    private static final long LOCK_SESSION_EXPIRY = 1002L;
    private static final long LOCK_NONCE_PURGE = 1003L;

    /**
     * Daily 02:00 IST: Purge MASTER and LONG_TERM datasets whose delete_after
     * retention has passed.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
    public void runDatasetRetentionJob() {
        executeWithLock(LOCK_DATASET_PURGE, this::purgeExpiredDatasets);
    }

    /**
     * Hourly: Expire stale user sessions, sync sessions, and leases past their
     * expiry windows.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Kolkata")
    public void runSessionExpiryJob() {
        executeWithLock(LOCK_SESSION_EXPIRY, this::expireSessionsAndLeases);
    }

    /**
     * Daily 02:30 IST: Purge device nonces older than their expiration.
     */
    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Kolkata")
    public void runNoncePurgeJob() {
        executeWithLock(LOCK_NONCE_PURGE, this::purgeExpiredNonces);
    }

    @Transactional
    public void purgeExpiredDatasets() {
        Instant now = Instant.now();
        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now);
        List<Long> expiredDatasetIds = jdbcTemplate.query(
                "SELECT id FROM dataset WHERE status = 'IMPORTED' AND delete_after IS NOT NULL AND delete_after <= ?",
                (rs, rowNum) -> rs.getLong("id"),
                nowTs);

        for (Long datasetId : expiredDatasetIds) {
            try {
                purgeDataset(datasetId, now);
            } catch (Exception e) {
                log.error("Failed to purge expired dataset {}", datasetId, e);
            }
        }
    }

    @Transactional
    public void purgeDataset(Long datasetId, Instant now) {
        Dataset ds = datasetRepository.findByIdForUpdate(datasetId).orElse(null);
        if (ds == null || ds.getStatus() != DatasetStatus.IMPORTED) {
            log.info("Dataset {} is no longer in IMPORTED status or not found. Skipping purge.", datasetId);
            return;
        }

        if (ds.getDeleteAfter() == null || ds.getDeleteAfter().isAfter(now)) {
            log.info("Dataset {} delete_after is null or in future. Skipping purge.", datasetId);
            return;
        }

        log.info("Evaluating expired dataset ID={} name='{}' tier={}", ds.getId(), ds.getName(), ds.getStorageTier());

        // Safety Check 1: Refuse to purge if active entitlements exist
        boolean hasActiveEntitlements = entitlementRepository.findByDatasetId(ds.getId()).stream()
                .anyMatch(e -> e.getStatus() == EntitlementStatus.ACTIVE && e.getValidUntil().isAfter(now));
        if (hasActiveEntitlements) {
            log.warn("Cannot purge dataset {}: Active entitlements exist. Skipping purge.", ds.getId());
            return;
        }

        // Safety Check 2: Refuse to purge if unexpired offline leases exist
        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now);
        String leaseSql = "SELECT count(*) FROM offline_lease l " +
                "JOIN booth_assignment b ON l.booth_assignment_id = b.id " +
                "JOIN entitlement e ON b.entitlement_id = e.id " +
                "WHERE e.dataset_id = ? AND l.expires_at > ? AND l.revoked_at IS NULL";
        Long activeLeaseCount = jdbcTemplate.queryForObject(leaseSql, Long.class, ds.getId(), nowTs);
        if (activeLeaseCount != null && activeLeaseCount > 0) {
            log.warn("Cannot purge dataset {}: {} unexpired offline leases exist. Skipping purge.", ds.getId(),
                    activeLeaseCount);
            return;
        }

        // Audit purge before dropping
        auditService.logDetails(null, null, ds.getId(), null,
                "DATASET_AUTO_PURGED", AuditOutcome.SUCCESS,
                "dataset", ds.getId().toString(),
                Map.of("name", ds.getName(), "tier", ds.getStorageTier().name(), "deleteAfter",
                        ds.getDeleteAfter().toString()),
                null);

        // Mark status in metadata to DELETING
        ds.setStatus(DatasetStatus.DELETING);
        datasetRepository.saveAndFlush(ds);

        // Drop database partitions
        jdbcTemplate.execute("SELECT detach_and_drop_dataset_partitions(" + ds.getId() + "::bigint);");

        log.info("Successfully dropped partitions and marked DELETING for dataset {}", ds.getId());
    }

    public void expireSessionsAndLeases() {
        Instant now = Instant.now();
        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now);

        // 1. Terminate expired user sessions
        int expiredSessions = jdbcTemplate.update(
                "UPDATE user_session SET status = 'TERMINATED', terminated_at = ?, " +
                        "termination_reason = CASE WHEN absolute_expires_at <= ? THEN 'ABSOLUTE_EXPIRED' ELSE 'IDLE_EXPIRED' END "
                        +
                        "WHERE status = 'ACTIVE' AND (absolute_expires_at <= ? OR (idle_expires_at IS NOT NULL AND idle_expires_at <= ?))",
                nowTs, nowTs, nowTs, nowTs);
        if (expiredSessions > 0) {
            log.info("Terminated {} expired user sessions", expiredSessions);
        }

        // 2. Mark expired sync sessions
        int expiredSyncs = jdbcTemplate.update(
                "UPDATE sync_session SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND expires_at <= ?",
                nowTs);
        if (expiredSyncs > 0) {
            log.info("Marked {} sync sessions as EXPIRED", expiredSyncs);
        }
    }

    public void purgeExpiredNonces() {
        Instant now = Instant.now();
        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now);
        int deletedNonces = jdbcTemplate.update("DELETE FROM device_nonce WHERE expires_at <= ?", nowTs);
        if (deletedNonces > 0) {
            log.info("Purged {} expired device nonces", deletedNonces);
        }
    }

    private void executeWithLock(long lockKey, Runnable task) {
        Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, lockKey);
        if (Boolean.TRUE.equals(acquired)) {
            try {
                task.run();
            } finally {
                jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, lockKey);
            }
        } else {
            log.debug("Advisory lock {} held by another node, skipping scheduled run", lockKey);
        }
    }
}
