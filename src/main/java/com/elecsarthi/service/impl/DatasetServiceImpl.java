package com.elecsarthi.service.impl;

import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.dto.response.DatasetSummaryResponse;
import com.elecsarthi.dto.response.ImportIssueResponse;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ImportIssue;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.repository.ImportIssueRepository;
import com.elecsarthi.repository.OfflineLeaseRepository;
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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetServiceImpl implements DatasetService {

        private final DatasetRepository datasetRepository;
        private final ImportIssueRepository importIssueRepository;
        private final EntitlementRepository entitlementRepository;
        private final OfflineLeaseRepository offlineLeaseRepository;
        private final AppUserRepository userRepository;
        private final AuditService auditService;
        private final JdbcTemplate jdbcTemplate;
        private final Clock clock;

        @Override
        @Transactional(readOnly = true)
        public Page<DatasetSummaryResponse> listDatasets(Pageable pageable) {
                return datasetRepository.findAll(pageable)
                                .map(this::mapToSummary);
        }

        @Override
        @Transactional(readOnly = true)
        public DatasetDetailResponse getDataset(Long id) {
                Dataset dataset = datasetRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Dataset not found: " + id));

                List<ImportIssue> issues = importIssueRepository.findByDatasetIdOrderByRowNoAsc(id);
                String importerUsername = userRepository.findById(dataset.getImportedByUserId())
                                .map(u -> u.getUsername())
                                .orElse("unknown");

                return mapToDetail(dataset, issues, importerUsername);
        }

        @Override
        @Transactional
        public void deleteDataset(Long id, Long callerUserId) {
                Dataset dataset = datasetRepository.findByIdForUpdate(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Dataset not found: " + id));

                if (dataset.getStatus() == DatasetStatus.DELETING) {
                        throw new ConflictException(ErrorCode.CONFLICT, "Dataset is already being deleted.");
                }

                Instant now = clock.instant();

                // 1. Check active entitlements on this dataset
                boolean hasActiveEntitlements = entitlementRepository.findByDatasetId(id).stream()
                                .anyMatch(e -> e.getStatus() == EntitlementStatus.ACTIVE
                                                && e.getValidUntil().isAfter(now));

                if (hasActiveEntitlements) {
                        throw new ConflictException(ErrorCode.CANNOT_DELETE_DATASET,
                                        "Cannot delete dataset. Active entitlements exist for this dataset.");
                }

                // 2. Check unexpired offline leases on this dataset
                String leaseSql = "SELECT count(*) FROM offline_lease l " +
                                "JOIN booth_assignment b ON l.booth_assignment_id = b.id " +
                                "JOIN entitlement e ON b.entitlement_id = e.id " +
                                "WHERE e.dataset_id = ? AND l.expires_at > ? AND l.revoked_at IS NULL";
                Long activeLeaseCount = jdbcTemplate.queryForObject(leaseSql, Long.class, id,
                                java.sql.Timestamp.from(now));
                if (activeLeaseCount != null && activeLeaseCount > 0) {
                        throw new ConflictException(ErrorCode.CANNOT_DELETE_DATASET,
                                        "Cannot delete dataset. Unexpired offline leases exist for this dataset.");
                }

                // 3. Mark status DELETING and flush to prevent any other concurrent process
                // from operating on it
                dataset.setStatus(DatasetStatus.DELETING);
                datasetRepository.saveAndFlush(dataset);

                // 4. Detach and drop partitions using the database SECURITY DEFINER function
                try {
                        jdbcTemplate.execute("SELECT detach_and_drop_dataset_partitions(" + id + "::bigint);");
                } catch (Exception e) {
                        log.error("Failed to detach and drop partitions for dataset {}: {}", id, e.getMessage(), e);
                        throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR,
                                        "Failed to drop partitions for dataset: " + e.getMessage(),
                                        HttpStatus.INTERNAL_SERVER_ERROR);
                }

                // 5. Delete import issues and dataset record
                jdbcTemplate.update("DELETE FROM import_issue WHERE dataset_id = ?", id);
                datasetRepository.delete(dataset);
                datasetRepository.flush();

                // 6. Audit log
                auditService.log(callerUserId, null, id, null, "DATASET_DELETE", AuditOutcome.SUCCESS,
                                "dataset", id.toString(), "{\"name\":\"" + dataset.getName() + "\"}", null);
        }

        private DatasetSummaryResponse mapToSummary(Dataset d) {
                return DatasetSummaryResponse.builder()
                                .id(d.getId())
                                .name(d.getName())
                                .originalFileName(d.getOriginalFileName())
                                .storageTier(d.getStorageTier())
                                .status(d.getStatus())
                                .totalRows(d.getTotalRows())
                                .importedRows(d.getImportedRows())
                                .failedRows(d.getFailedRows())
                                .deleteAfter(d.getDeleteAfter())
                                .createdAt(d.getCreatedAt())
                                .completedAt(d.getCompletedAt())
                                .build();
        }

        private DatasetDetailResponse mapToDetail(Dataset d, List<ImportIssue> issues, String importerUsername) {
                List<ImportIssueResponse> issueDtos = issues.stream()
                                .map(i -> ImportIssueResponse.builder()
                                                .id(i.getId())
                                                .sheet(i.getSheet())
                                                .rowNo(i.getRowNo())
                                                .columnName(i.getColumnName())
                                                .issueCode(i.getIssueCode())
                                                .issueDetail(i.getIssueDetail())
                                                .createdAt(i.getCreatedAt())
                                                .build())
                                .collect(Collectors.toList());

                return DatasetDetailResponse.builder()
                                .id(d.getId())
                                .name(d.getName())
                                .originalFileName(d.getOriginalFileName())
                                .fileSha256(d.getFileSha256())
                                .storageTier(d.getStorageTier())
                                .status(d.getStatus())
                                .states(d.getStates())
                                .supersedesDatasetId(d.getSupersedesDatasetId())
                                .supersededAt(d.getSupersededAt())
                                .deleteAfter(d.getDeleteAfter())
                                .totalRows(d.getTotalRows())
                                .importedRows(d.getImportedRows())
                                .failedRows(d.getFailedRows())
                                .importedByUserId(d.getImportedByUserId())
                                .importedByUsername(importerUsername)
                                .createdAt(d.getCreatedAt())
                                .completedAt(d.getCompletedAt())
                                .issues(issueDtos)
                                .build();
        }
}
