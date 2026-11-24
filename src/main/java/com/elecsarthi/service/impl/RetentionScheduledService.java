package com.elecsarthi.service.impl;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionScheduledService {

    private final DatasetRetentionPurger datasetRetentionPurger;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    private static final long LOCK_DATASET_PURGE = 1001L;
    private static final long LOCK_SESSION_EXPIRY = 1002L;
    private static final long LOCK_NONCE_PURGE = 1003L;

    /**
     * Daily 02:00 IST: Purge MASTER, LONG_TERM, and SUPERSEDED datasets whose delete_after
     * retention has passed.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
    public void runDatasetRetentionJob() {
        executeWithTransactionLock(LOCK_DATASET_PURGE, this::purgeExpiredDatasets);
    }

    /**
     * Hourly: Expire stale user sessions, sync sessions, and leases past their
     * expiry windows.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Kolkata")
    public void runSessionExpiryJob() {
        executeWithTransactionLock(LOCK_SESSION_EXPIRY, this::expireSessionsAndLeases);
    }

    /**
     * Daily 02:30 IST: Purge device nonces older than their expiration.
     */
    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Kolkata")
    public void runNoncePurgeJob() {
        executeWithTransactionLock(LOCK_NONCE_PURGE, this::purgeExpiredNonces);
    }

    /**
     * Identifies eligible expired datasets (status IMPORTED or SUPERSEDED) and purges each in an isolated transaction.
     */
    public void purgeExpiredDatasets() {
        Instant now = clock.instant();
        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now);
        List<Long> expiredDatasetIds = jdbcTemplate.query(
                "SELECT id FROM dataset WHERE status IN ('IMPORTED', 'SUPERSEDED') AND delete_after IS NOT NULL AND delete_after <= ?",
                (rs, rowNum) -> rs.getLong("id"),
                nowTs);

        for (Long datasetId : expiredDatasetIds) {
            try {
                datasetRetentionPurger.purgeSingleDataset(datasetId, now);
            } catch (Exception e) {
                log.error("Failed to purge expired dataset {}", datasetId, e);
            }
        }
    }

    public boolean purgeDataset(Long datasetId, Instant now) {
        return datasetRetentionPurger.purgeSingleDataset(datasetId, now);
    }

    public void expireSessionsAndLeases() {
        Instant now = clock.instant();
        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now);

        // 1. Terminate expired user sessions
        int expiredSessions = jdbcTemplate.update(
                "UPDATE user_session SET status = 'TERMINATED', terminated_at = ?, " +
                        "termination_reason = CASE WHEN absolute_expires_at <= ? THEN 'ABSOLUTE_EXPIRED' ELSE 'IDLE_EXPIRED' END " +
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
        Instant now = clock.instant();
        java.sql.Timestamp nowTs = java.sql.Timestamp.from(now);
        int deletedNonces = jdbcTemplate.update("DELETE FROM device_nonce WHERE expires_at <= ?", nowTs);
        if (deletedNonces > 0) {
            log.info("Purged {} expired device nonces", deletedNonces);
        }
    }

    /**
     * Executes the task using a PostgreSQL transaction-scoped advisory lock (pg_try_advisory_xact_lock).
     * The lock is acquired on the transaction's connection and automatically released upon commit/rollback,
     * completely eliminating connection pool leaks and cross-thread lock state mismatches.
     */
    public void executeWithTransactionLock(long lockKey, Runnable task) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.executeWithoutResult(status -> {
            Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockKey);
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Acquired advisory xact lock {}", lockKey);
                task.run();
            } else {
                log.debug("Advisory xact lock {} held by another node, skipping scheduled run", lockKey);
            }
        });
    }
}
