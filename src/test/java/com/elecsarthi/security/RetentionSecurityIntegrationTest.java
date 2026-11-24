package com.elecsarthi.security;

import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.entity.UserSession;
import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SessionStatus;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.service.impl.RetentionScheduledService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Scheduled dataset retention never purges data that is still in use. */
public class RetentionSecurityIntegrationTest extends SecurityIntegrationTestSupport {

    @Autowired
    private EntitlementRepository entitlementRepository;

    @Autowired
    private ElectionTypeRepository electionTypeRepository;

    @Autowired
    private RetentionScheduledService retentionScheduledService;

    @Autowired
    private CustomerAccountRepository customerAccountRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Finding 6: Scheduled Retention - Purges MASTER dataset partitions past delete_after and terminates expired sessions")
    void testScheduledRetentionExecution() {
        Instant now = Instant.now();

        AppUser creatorRet = userRepository.save(AppUser.builder()
                .username("creator_user_retention")
                .fullName("Creator User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        // 1. Create expired MASTER dataset past delete_after
        Dataset masterDs = datasetRepository.save(Dataset.builder()
                .name("Expired Master Roll")
                .originalFileName("master_roll.xlsx")
                .fileSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(now.minus(1, ChronoUnit.DAYS))
                .importedByUserId(creatorRet.getId())
                .createdAt(now.minus(61, ChronoUnit.DAYS))
                .build());

        jdbcTemplate.execute("SELECT create_dataset_partitions(" + masterDs.getId() + ");");

        // Verify partition table exists before purge
        Boolean partitionExistsBefore = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'voter_d" + masterDs.getId() + "')",
                Boolean.class
        );
        assertTrue(Boolean.TRUE.equals(partitionExistsBefore));

        // 2. Create expired user session
        AppUser user = userRepository.save(AppUser.builder()
                .username("retention_user")
                .fullName("Retention User")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        UserSession expiredSession = sessionRepository.save(UserSession.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .userRole(user.getRole())
                .clientType(ClientType.WEB)
                .status(SessionStatus.ACTIVE)
                .absoluteExpiresAt(now.minus(1, ChronoUnit.DAYS))
                .build());

        // 3. Trigger retention jobs
        retentionScheduledService.purgeExpiredDatasets();
        retentionScheduledService.expireSessionsAndLeases();

        // 4. Verify partition was dropped and dataset marked DELETING
        Boolean partitionExistsAfter = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'voter_d" + masterDs.getId() + "')",
                Boolean.class
        );
        assertFalse(Boolean.TRUE.equals(partitionExistsAfter), "Partition table voter_d<id> must be detached and dropped");

        Dataset purgedDs = datasetRepository.findById(masterDs.getId()).orElseThrow();
        assertEquals(DatasetStatus.DELETING, purgedDs.getStatus());

        // 5. Verify user session transitioned to TERMINATED
        UserSession updatedSession = sessionRepository.findById(expiredSession.getId()).orElseThrow();
        assertEquals(SessionStatus.TERMINATED, updatedSession.getStatus());
        assertEquals("ABSOLUTE_EXPIRED", updatedSession.getTerminationReason());
    }

    @Test
    @DisplayName("Verification: Retention job safety checks preserve datasets with active entitlements")
    void testRetentionJobPreservesDatasetWithActiveEntitlement() {
        Instant now = Instant.now();

        AppUser owner = userRepository.save(AppUser.builder()
                .username("ret_owner")
                .fullName("Ret Owner")
                .passwordHash(passwordEncoder.encode("Password123!"))
                .role(Role.OWNER)
                .status(UserStatus.ACTIVE)
                .build());

        CustomerAccount custAcc = customerAccountRepository.save(CustomerAccount.builder()
                .customerName("Ret Test Cust")
                .contactPhone("+919999900002")
                .status(UserStatus.ACTIVE)
                .createdByUserId(owner.getId())
                .build());

        // Create an expired dataset with delete_after in the past
        Dataset expDs = datasetRepository.save(Dataset.builder()
                .name("Expired But Active Entitlement DS")
                .originalFileName("exp.csv")
                .fileSha256("11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff")
                .storageTier(DatasetTier.LONG_TERM)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(now.minus(1, ChronoUnit.DAYS))
                .totalRows(10)
                .importedRows(10)
                .failedRows(0)
                .importedByUserId(owner.getId())
                .createdAt(now.minus(60, ChronoUnit.DAYS))
                .build());

        jdbcTemplate.execute("SELECT create_dataset_partitions(" + expDs.getId() + ");");

        ElectionType et = electionTypeRepository.findAll().get(0);

        // Add an ACTIVE entitlement whose validUntil is in the future
        entitlementRepository.save(Entitlement.builder()
                .customerAccountId(custAcc.getId())
                .electionTypeId(et.getId())
                .datasetId(expDs.getId())
                .storageTier(DatasetTier.LONG_TERM)
                .criteria("{\"ac_no\": 50}")
                .criteriaHash("hash-exp-1")
                .scopeLabel("AC 50")
                .source(EntitlementSource.DIRECT)
                .status(EntitlementStatus.ACTIVE)
                .validFrom(now.minus(10, ChronoUnit.DAYS))
                .validUntil(now.plus(10, ChronoUnit.DAYS))
                .mobileAccess(false)
                .createdByUserId(owner.getId())
                .createdAt(now.minus(10, ChronoUnit.DAYS))
                .build());

        // Run retention job
        retentionScheduledService.purgeExpiredDatasets();

        // Dataset MUST STILL EXIST and remain in IMPORTED status
        Dataset preservedDs = datasetRepository.findById(expDs.getId()).orElseThrow();
        assertEquals(DatasetStatus.IMPORTED, preservedDs.getStatus());
    }

    @Test
    @DisplayName("Finding N2, N3, N4 & Point 1: Scheduled retention entry method, xact lock contention, failure recovery, and REQUIRES_NEW isolation")
    void testScheduledRetentionTransactionLockAndSupersededPurge() throws Exception {
        AppUser owner = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.OWNER)
                .findFirst()
                .orElseGet(() -> userRepository.save(AppUser.builder()
                        .username("ret_owner_" + UUID.randomUUID())
                        .fullName("Retention Owner")
                        .passwordHash(passwordEncoder.encode("Secret@123"))
                        .role(Role.OWNER)
                        .status(UserStatus.ACTIVE)
                        .build()));

        // 1. Advisory Lock Contention: Hold transaction-scoped lock 1001L on another thread/connection
        Dataset contentionDs = datasetRepository.save(Dataset.builder()
                .name("Contention Dataset")
                .originalFileName("contention.xlsx")
                .fileSha256("sha_cont_" + UUID.randomUUID())
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(Instant.now().minus(1, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .createdAt(Instant.now().minus(65, ChronoUnit.DAYS))
                .build());
        jdbcTemplate.execute("SELECT create_dataset_partitions(" + contentionDs.getId() + ")");

        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);
        ExecutorService lockHolderExecutor = Executors.newSingleThreadExecutor();
        Future<?> holderFuture = lockHolderExecutor.submit(() -> {
            org.springframework.transaction.support.TransactionTemplate holderTx =
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            holderTx.executeWithoutResult(status -> {
                Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(1001)", Boolean.class);
                assertTrue(Boolean.TRUE.equals(acquired), "Background holder must acquire lock 1001");
                lockAcquiredLatch.countDown();
                try {
                    assertTrue(releaseLockLatch.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });

        assertTrue(lockAcquiredLatch.await(5, TimeUnit.SECONDS));
        // Execute actual @Scheduled entry method while lock 1001 is held -> must skip cleanly without purging
        retentionScheduledService.runDatasetRetentionJob();
        Dataset stillImported = datasetRepository.findById(contentionDs.getId()).orElseThrow();
        assertEquals(DatasetStatus.IMPORTED, stillImported.getStatus(),
                "Scheduled job must skip execution when advisory lock 1001 is held");

        // Release holder lock and re-run actual @Scheduled entry method -> must acquire lock & purge
        releaseLockLatch.countDown();
        holderFuture.get(10, TimeUnit.SECONDS);
        lockHolderExecutor.shutdown();

        retentionScheduledService.runDatasetRetentionJob();
        Dataset purgedContention = datasetRepository.findById(contentionDs.getId()).orElseThrow();
        assertEquals(DatasetStatus.DELETING, purgedContention.getStatus(),
                "After lock release, runDatasetRetentionJob must acquire lock 1001 and purge dataset");

        // 2. Failure releases advisory xact lock: throwing inside executeWithTransactionLock must rollback and release 1001L
        assertThrows(RuntimeException.class, () ->
                retentionScheduledService.executeWithTransactionLock(1001L, () -> {
                    throw new RuntimeException("Simulated outer scheduled failure");
                }));

        Dataset postFailureDs = datasetRepository.save(Dataset.builder()
                .name("Post Failure Superseded Dataset")
                .originalFileName("post_fail_super.xlsx")
                .fileSha256("sha_pf_" + UUID.randomUUID())
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.SUPERSEDED)
                .deleteAfter(Instant.now().minus(2, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .createdAt(Instant.now().minus(30, ChronoUnit.DAYS))
                .build());
        jdbcTemplate.execute("SELECT create_dataset_partitions(" + postFailureDs.getId() + ")");

        // Subsequent runDatasetRetentionJob must acquire lock 1001L cleanly
        retentionScheduledService.runDatasetRetentionJob();
        Dataset refreshedPostFail = datasetRepository.findById(postFailureDs.getId()).orElseThrow();
        assertEquals(DatasetStatus.DELETING, refreshedPostFail.getStatus(),
                "Subsequent runDatasetRetentionJob must acquire lock after previous failure rolled back");

        // 3. Spring-managed REQUIRES_NEW isolation: failure on one dataset rolls back ONLY that dataset
        // (including its DATASET_AUTO_PURGED audit log), while sibling expired dataset commits its purge.
        Dataset failingDs = datasetRepository.save(Dataset.builder()
                .name("Failing Purge Dataset")
                .originalFileName("failing_purge.xlsx")
                .fileSha256("sha_fail_" + UUID.randomUUID())
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.IMPORTED)
                .deleteAfter(Instant.now().minus(3, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .createdAt(Instant.now().minus(70, ChronoUnit.DAYS))
                .build());
        Dataset succeedingDs = datasetRepository.save(Dataset.builder()
                .name("Succeeding Purge Dataset")
                .originalFileName("succeeding_purge.xlsx")
                .fileSha256("sha_ok_" + UUID.randomUUID())
                .storageTier(DatasetTier.MASTER)
                .status(DatasetStatus.SUPERSEDED)
                .deleteAfter(Instant.now().minus(3, ChronoUnit.DAYS))
                .importedByUserId(owner.getId())
                .createdAt(Instant.now().minus(70, ChronoUnit.DAYS))
                .build());
        jdbcTemplate.execute("SELECT create_dataset_partitions(" + failingDs.getId() + ")");
        jdbcTemplate.execute("SELECT create_dataset_partitions(" + succeedingDs.getId() + ")");

        // Temporary DB trigger blocking UPDATE solely for failingDs.id
        jdbcTemplate.execute(
                "CREATE OR REPLACE FUNCTION test_fail_dataset_purge_fn() RETURNS trigger AS $$ " +
                "BEGIN IF NEW.id = " + failingDs.getId() + " THEN RAISE EXCEPTION 'Simulated partition/dataset failure'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql;");
        jdbcTemplate.execute(
                "CREATE TRIGGER trg_test_fail_dataset_purge BEFORE UPDATE ON dataset FOR EACH ROW EXECUTE FUNCTION test_fail_dataset_purge_fn();");

        try {
            retentionScheduledService.runDatasetRetentionJob();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_fail_dataset_purge ON dataset;");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_fail_dataset_purge_fn();");
        }

        Dataset failedReloaded = datasetRepository.findById(failingDs.getId()).orElseThrow();
        Dataset okReloaded = datasetRepository.findById(succeedingDs.getId()).orElseThrow();

        assertEquals(DatasetStatus.IMPORTED, failedReloaded.getStatus(),
                "Failed dataset purge must roll back to IMPORTED");
        assertEquals(DatasetStatus.DELETING, okReloaded.getStatus(),
                "Sibling dataset purge must commit DELETING independently via REQUIRES_NEW");

        Long failedAuditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action_type = 'DATASET_AUTO_PURGED' AND target_id = ?",
                Long.class, failingDs.getId().toString());
        Long okAuditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action_type = 'DATASET_AUTO_PURGED' AND target_id = ?",
                Long.class, succeedingDs.getId().toString());

        assertEquals(0L, failedAuditCount, "Audit record for failed REQUIRES_NEW purge must roll back");
        assertEquals(1L, okAuditCount, "Audit record for successful REQUIRES_NEW purge must commit");
    }
}
