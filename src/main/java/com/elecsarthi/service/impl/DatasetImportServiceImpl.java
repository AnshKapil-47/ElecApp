package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.DatasetUploadRequest;
import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.ElectionTypeField;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.FieldUsage;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.mapper.DatasetMapper;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeFieldRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.ImportIssueRepository;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.DatasetImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStringsTable;
import org.apache.poi.xssf.model.StylesTable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Imports an Excel voter roll into a new dataset: partitions are created, the workbook is streamed into
 * them, and the dataset becomes IMPORTED only after the overlap probe passes. Any failure marks the dataset
 * FAILED and drops its partitions, so the same file can be uploaded again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetImportServiceImpl implements DatasetImportService {

    private static final int MASTER_RETENTION_DAYS = 60;
    private static final int LONG_TERM_INITIAL_RETENTION_DAYS = 30;
    private static final long STALE_PENDING_HOURS = 1;

    private final DatasetRepository datasetRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final ElectionTypeFieldRepository fieldRepository;
    private final ImportIssueRepository importIssueRepository;
    private final AuditService auditService;
    private final DatasetMapper datasetMapper;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    public DatasetDetailResponse importDataset(MultipartFile file, DatasetUploadRequest request, Long callerUserId) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File is required.");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".xlsx")) {
            throw new BadRequestException("Only .xlsx Excel files are supported.");
        }
        electionTypeRepository.findByCode(request.getElectionTypeCode())
                .orElseThrow(() -> new ResourceNotFoundException("Election type not found: " + request.getElectionTypeCode()));
        Dataset superseded = loadSupersedable(request.getSupersedesDatasetId());

        Path tempFile = null;
        Dataset dataset = null;
        try {
            tempFile = Files.createTempFile("dataset-import-", ".xlsx");
            String fileSha256 = copyAndHash(file, tempFile);
            rejectDuplicateFile(fileSha256);

            Instant now = clock.instant();
            dataset = datasetRepository.save(Dataset.builder()
                    .name(request.getName().trim())
                    .originalFileName(originalFilename)
                    .fileSha256(fileSha256)
                    .storageTier(request.getStorageTier())
                    .status(DatasetStatus.PENDING)
                    .supersedesDatasetId(request.getSupersedesDatasetId())
                    .deleteAfter(now.plus(initialRetentionDays(request.getStorageTier()), ChronoUnit.DAYS))
                    .importedByUserId(callerUserId)
                    .createdAt(now)
                    .build());
            Long datasetId = dataset.getId();

            jdbcTemplate.execute("SELECT create_dataset_partitions(" + datasetId + ");");

            VoterSheetHandler rows = new VoterSheetHandler(datasetId, textColumnsToNormalize(), jdbcTemplate, appProperties, now);
            streamXlsx(tempFile, rows);

            rejectOverlap(datasetId, request.getStorageTier(), request.getSupersedesDatasetId());
            jdbcTemplate.execute("SELECT create_dataset_indexes(" + datasetId + ");");

            importIssueRepository.saveAll(rows.issues());
            if (superseded != null) {
                superseded.setStatus(DatasetStatus.SUPERSEDED);
                superseded.setSupersededAt(now);
                datasetRepository.save(superseded);
            }
            dataset.setStatus(DatasetStatus.IMPORTED);
            dataset.setTotalRows(rows.totalRows());
            dataset.setImportedRows(rows.importedRows());
            dataset.setFailedRows(rows.failedRows());
            dataset.setStates(rows.states());
            dataset.setCompletedAt(clock.instant());
            dataset = datasetRepository.save(dataset);

            auditService.log(callerUserId, null, datasetId, null, "DATASET_IMPORT", AuditOutcome.SUCCESS,
                    "dataset", datasetId.toString(),
                    "{\"rows\":" + rows.importedRows() + ",\"tier\":\"" + request.getStorageTier() + "\"}", null);

            return datasetMapper.toDetailResponse(dataset, rows.issues());
        } catch (Exception e) {
            discardFailedDataset(dataset);
            if (e instanceof ApiException apiException) {
                log.warn("Dataset import rejected: {}", apiException.getMessage());
                throw apiException;
            }
            log.error("Dataset import failed for datasetId={}", dataset == null ? null : dataset.getId(), e);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "Dataset import failed.", HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            deleteQuietly(tempFile);
        }
    }

    /** A superseded dataset must exist and still be IMPORTED; checked before the file is streamed. */
    private Dataset loadSupersedable(Long supersedesDatasetId) {
        if (supersedesDatasetId == null) {
            return null;
        }
        Dataset old = datasetRepository.findById(supersedesDatasetId)
                .orElseThrow(() -> new BadRequestException("Superseded dataset not found: " + supersedesDatasetId));
        if (old.getStatus() != DatasetStatus.IMPORTED) {
            throw new ConflictException(ErrorCode.CONFLICT, "Cannot supersede dataset in status: " + old.getStatus());
        }
        return old;
    }

    private String copyAndHash(MultipartFile file, Path target) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The same file may be imported only once. A PENDING dataset older than an hour is an abandoned import:
     * its partitions are dropped and it is marked FAILED so the file can be retried.
     */
    private void rejectDuplicateFile(String fileSha256) {
        Optional<Dataset> existing = datasetRepository.findByFileSha256AndStatusNot(fileSha256, DatasetStatus.FAILED);
        if (existing.isEmpty()) {
            return;
        }
        Dataset stale = existing.get();
        boolean abandoned = stale.getStatus() == DatasetStatus.PENDING
                && stale.getCreatedAt().isBefore(clock.instant().minus(STALE_PENDING_HOURS, ChronoUnit.HOURS));
        if (!abandoned) {
            throw new ConflictException(ErrorCode.FILE_ALREADY_EXISTS,
                    "This file has already been imported (Dataset ID: " + stale.getId() + ").");
        }
        log.warn("Abandoned PENDING dataset {} found for the same file; dropping its partitions", stale.getId());
        discardFailedDataset(stale);
    }

    private static int initialRetentionDays(DatasetTier tier) {
        return tier == DatasetTier.MASTER ? MASTER_RETENTION_DAYS : LONG_TERM_INITIAL_RETENTION_DAYS;
    }

    /**
     * A dataset can be queried through any election type, so every text column that any type filters or
     * guards on is normalized at import time (the same way criteria are normalized when a sale is made).
     */
    private Set<String> textColumnsToNormalize() {
        Set<String> columns = new HashSet<>(Set.of("state", "district", "voter_id"));
        for (ElectionType electionType : electionTypeRepository.findAll()) {
            String guard = electionType.getGuardColumn();
            if (guard != null && !guard.isBlank()) {
                columns.add(guard.toLowerCase().trim());
            }
        }
        for (ElectionTypeField field : fieldRepository.findAll()) {
            if (field.getUsage() == FieldUsage.FILTER && field.getSourceColumn() != null) {
                String column = field.getSourceColumn().toLowerCase().trim();
                if (!VoterSheetHandler.INT_COLUMNS.contains(column)) {
                    columns.add(column);
                }
            }
        }
        return columns;
    }

    private void streamXlsx(Path file, VoterSheetHandler rows) throws Exception {
        try (OPCPackage opc = OPCPackage.open(file.toFile())) {
            XSSFReader reader = new XSSFReader(opc);
            StylesTable styles = reader.getStylesTable();
            SharedStringsTable sharedStrings = (SharedStringsTable) reader.getSharedStringsTable();
            XMLReader parser = XMLHelper.newXMLReader();
            parser.setContentHandler(new XSSFSheetXMLHandler(styles, sharedStrings, rows, false));

            Iterator<InputStream> sheets = reader.getSheetsData();
            for (int sheetNo = 1; sheets.hasNext(); sheetNo++) {
                try (InputStream sheet = sheets.next()) {
                    rows.startNewSheet("Sheet" + sheetNo);
                    parser.parse(new InputSource(sheet));
                }
            }
            rows.finish();
        }
    }

    /**
     * A voter record (identified by source_record_key_hash) may exist in only one IMPORTED dataset of a tier.
     * The dataset being superseded is excluded, because a correction legitimately repeats its records.
     */
    private void rejectOverlap(Long datasetId, DatasetTier tier, Long supersedesDatasetId) {
        String sql = "SELECT d.id, d.name FROM voter_d" + datasetId + " n "
                + "JOIN dataset d ON d.storage_tier = ? AND d.status = 'IMPORTED' AND d.id <> ? "
                + (supersedesDatasetId != null ? "AND d.id <> ? " : "")
                + "JOIN voter o ON o.dataset_id = d.id AND o.source_record_key_hash = n.source_record_key_hash "
                + "LIMIT 1";
        Object[] params = supersedesDatasetId != null
                ? new Object[]{tier.name(), datasetId, supersedesDatasetId}
                : new Object[]{tier.name(), datasetId};
        List<Map<String, Object>> overlaps = jdbcTemplate.queryForList(sql, params);
        if (!overlaps.isEmpty()) {
            Map<String, Object> conflict = overlaps.get(0);
            throw new ConflictException(ErrorCode.DATASET_OVERLAP,
                    "Dataset voter records overlap with existing dataset ID " + conflict.get("id") + " (" + conflict.get("name") + ").");
        }
    }

    /** Marks the dataset FAILED and drops its partitions immediately so the file is not locked out. */
    private void discardFailedDataset(Dataset dataset) {
        if (dataset == null || dataset.getId() == null) {
            return;
        }
        try {
            dataset.setStatus(DatasetStatus.FAILED);
            datasetRepository.save(dataset);
            jdbcTemplate.execute("SELECT detach_and_drop_dataset_partitions(" + dataset.getId() + ");");
        } catch (Exception cleanupFailure) {
            log.error("Failed to clean up partitions for dataset {}", dataset.getId(), cleanupFailure);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temporary import file {}", file);
        }
    }
}
