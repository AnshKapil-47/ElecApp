package com.elecsarthi.service.impl;

import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.dto.response.DatasetSummaryResponse;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.mapper.DatasetMapper;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.ImportIssueRepository;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.DatasetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetServiceImpl implements DatasetService {

    /** A LONG_TERM dataset outlives its latest entitlement by this many days. */
    private static final int LONG_TERM_GRACE_DAYS = 30;

    private final DatasetRepository datasetRepository;
    private final ImportIssueRepository importIssueRepository;
    private final EntitlementRepository entitlementRepository;
    private final AppUserRepository userRepository;
    private final AuditService auditService;
    private final DatasetMapper datasetMapper;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Page<DatasetSummaryResponse> listDatasets(Pageable pageable) {
        return datasetRepository.findAll(pageable).map(datasetMapper::toSummaryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public DatasetDetailResponse getDataset(Long id) {
        Dataset dataset = datasetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset not found: " + id));
        String importerUsername = userRepository.findById(dataset.getImportedByUserId())
                .map(user -> user.getUsername())
                .orElse("unknown");
        return datasetMapper.toDetailResponse(dataset, importIssueRepository.findByDatasetIdOrderByRowNoAsc(id), importerUsername);
    }

    /**
     * A dataset can only be deleted when no ACTIVE entitlement and no unexpired offline lease depends on it.
     * It is locked and flagged DELETING first so no concurrent sale, lease or import can use it meanwhile.
     */
    @Override
    @Transactional
    public void deleteDataset(Long id, Long callerUserId) {
        Dataset dataset = datasetRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset not found: " + id));
        if (dataset.getStatus() == DatasetStatus.DELETING) {
            throw new ConflictException(ErrorCode.CONFLICT, "Dataset is already being deleted.");
        }

        Instant now = clock.instant();
        boolean hasActiveEntitlements = entitlementRepository.findByDatasetId(id).stream()
                .anyMatch(e -> e.getStatus() == EntitlementStatus.ACTIVE && e.getValidUntil().isAfter(now));
        if (hasActiveEntitlements) {
            throw new ConflictException(ErrorCode.CANNOT_DELETE_DATASET,
                    "Cannot delete dataset. Active entitlements exist for this dataset.");
        }

        Long activeLeaseCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM offline_lease l "
                        + "JOIN booth_assignment b ON l.booth_assignment_id = b.id "
                        + "JOIN entitlement e ON b.entitlement_id = e.id "
                        + "WHERE e.dataset_id = ? AND l.expires_at > ? AND l.revoked_at IS NULL",
                Long.class, id, Timestamp.from(now));
        if (activeLeaseCount != null && activeLeaseCount > 0) {
            throw new ConflictException(ErrorCode.CANNOT_DELETE_DATASET,
                    "Cannot delete dataset. Unexpired offline leases exist for this dataset.");
        }

        dataset.setStatus(DatasetStatus.DELETING);
        datasetRepository.saveAndFlush(dataset);

        try {
            jdbcTemplate.execute("SELECT detach_and_drop_dataset_partitions(" + id + "::bigint);");
        } catch (Exception e) {
            log.error("Failed to detach and drop partitions for dataset {}", id, e);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to drop partitions for dataset.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        jdbcTemplate.update("DELETE FROM import_issue WHERE dataset_id = ?", id);
        datasetRepository.delete(dataset);
        datasetRepository.flush();

        auditService.logDetails(callerUserId, null, id, null, "DATASET_DELETE", AuditOutcome.SUCCESS,
                "dataset", id.toString(), Map.of("name", dataset.getName()), null);
    }

    @Override
    public void recomputeLongTermRetention(Long datasetId, Instant now) {
        datasetRepository.findById(datasetId)
                .filter(dataset -> dataset.getStorageTier() == DatasetTier.LONG_TERM)
                .ifPresent(dataset -> {
                    Instant keepUntil = entitlementRepository.findLatestActiveValidUntil(datasetId).orElse(now)
                            .plus(LONG_TERM_GRACE_DAYS, ChronoUnit.DAYS);
                    dataset.setDeleteAfter(keepUntil);
                    datasetRepository.save(dataset);
                    log.info("Recomputed LONG_TERM dataset {} delete_after to {}", datasetId, keepUntil);
                });
    }
}
