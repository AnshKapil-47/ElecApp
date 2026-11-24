package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.DatasetUploadRequest;
import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.dto.response.ImportIssueResponse;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.ImportIssue;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.ImportIssueRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.CriteriaNormalizer;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetImportServiceImpl implements DatasetImportService {

    private final DatasetRepository datasetRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final ImportIssueRepository importIssueRepository;
    private final CriteriaNormalizer criteriaNormalizer;
    private final AuditService auditService;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    private static final int MAX_ISSUES = 200;
    private static final int BATCH_SIZE = 1000;

    private static final Set<String> CANONICAL_COLUMNS = Set.of(
            "state", "district", "pc_no", "pc_name_en", "pc_name_mt",
            "ac_no", "ac_name_en", "ac_name_mt", "part_no", "booth_no",
            "booth_name_en", "booth_name_mt", "booth_address_en", "booth_address_mt",
            "serial_no", "voter_id", "v_fname_en", "v_lname_en", "v_fname_mt", "v_lname_mt",
            "vr_fname_en", "vr_lname_en", "vr_fname_mt", "vr_lname_mt",
            "house_no", "house_location_en", "house_location_mt",
            "section_no", "section_name_en", "section_name_mt",
            "age", "sex", "rural_urban", "tehsil", "block", "pincode",
            "municipal_name_nagar_nigam_en", "municipal_name_nagar_nigam_mt", "nagar_nigam_ward_no",
            "municipal_name_nagar_palika_en", "municipal_name_nagar_palika_mt", "nagar_palika_ward_no",
            "municipal_name_nagar_parishad_en", "municipal_name_nagar_parishad_mt", "nagar_parishad_ward_no",
            "zilla_parishad_name_en", "zilla_parishad_name_mt", "zilla_parishad_name_ward_no",
            "panchayat_samiti_name_en", "panchayat_samiti_name_mt", "panchayat_samiti_name_ward_no",
            "panchayat_name_en", "panchayat_name_mt", "panchayat_no",
            "mobile_no", "voter_image"
    );

    private static final Set<String> INT_COLUMNS = Set.of(
            "pc_no", "ac_no", "part_no", "booth_no", "serial_no",
            "section_no", "age", "nagar_nigam_ward_no", "nagar_palika_ward_no",
            "nagar_parishad_ward_no", "zilla_parishad_name_ward_no",
            "panchayat_samiti_name_ward_no", "panchayat_no"
    );

    @Override
    public DatasetDetailResponse importDataset(MultipartFile file, DatasetUploadRequest request, Long callerUserId) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "File is required.", HttpStatus.BAD_REQUEST);
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".xlsx")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Only .xlsx Excel files are supported.", HttpStatus.BAD_REQUEST);
        }

        ElectionType electionType = electionTypeRepository.findByCode(request.getElectionTypeCode())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Election type not found: " + request.getElectionTypeCode(), HttpStatus.NOT_FOUND));

        // Save to temp file while computing SHA-256
        File tempFile;
        String fileSha256;
        try {
            tempFile = File.createTempFile("dataset-import-", ".xlsx");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = file.getInputStream();
                 DigestInputStream dis = new DigestInputStream(in, digest)) {
                Files.copy(dis, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            fileSha256 = HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to read upload file: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        try {
            // Check duplicate file hash across non-failed datasets
            Optional<Dataset> existingDataset = datasetRepository.findByFileSha256AndStatusNot(fileSha256, DatasetStatus.FAILED);
            if (existingDataset.isPresent()) {
                throw new ConflictException(ErrorCode.FILE_ALREADY_EXISTS, "This file has already been imported (Dataset ID: " + existingDataset.get().getId() + ").");
            }

            Instant now = clock.instant();
            Instant deleteAfter = (request.getStorageTier() == DatasetTier.MASTER)
                    ? now.plus(60, ChronoUnit.DAYS)
                    : now.plus(365 * 5, ChronoUnit.DAYS);

            Dataset dataset = Dataset.builder()
                    .name(request.getName().trim())
                    .originalFileName(originalFilename)
                    .fileSha256(fileSha256)
                    .storageTier(request.getStorageTier())
                    .status(DatasetStatus.PENDING)
                    .supersedesDatasetId(request.getSupersedesDatasetId())
                    .deleteAfter(deleteAfter)
                    .importedByUserId(callerUserId)
                    .createdAt(now)
                    .build();

            dataset = datasetRepository.save(dataset);
            Long datasetId = dataset.getId();

            // Create partitions
            jdbcTemplate.execute("SELECT create_dataset_partitions(" + datasetId + ");");

            // Stream XLSX parsing and insert into voter_d<id>
            VoterImportContext context = new VoterImportContext(datasetId, electionType);
            streamXlsx(tempFile, context);

            // Overlap probe on source_record_key_hash
            String overlapSql = "SELECT d.id, d.name FROM voter_d" + datasetId + " n " +
                    "JOIN dataset d ON d.storage_tier = ? AND d.status = 'IMPORTED' AND d.id <> ? " +
                    "JOIN voter o ON o.dataset_id = d.id AND o.source_record_key_hash = n.source_record_key_hash " +
                    "LIMIT 1";

            List<Map<String, Object>> overlaps = jdbcTemplate.queryForList(overlapSql, request.getStorageTier().name(), datasetId);
            if (!overlaps.isEmpty()) {
                dataset.setStatus(DatasetStatus.FAILED);
                datasetRepository.save(dataset);
                Long confId = ((Number) overlaps.get(0).get("id")).longValue();
                String confName = (String) overlaps.get(0).get("name");
                throw new ConflictException(ErrorCode.DATASET_OVERLAP, "Dataset voter records overlap with existing dataset ID " + confId + " (" + confName + ").");
            }

            // Create partition indexes
            jdbcTemplate.execute("SELECT create_dataset_indexes(" + datasetId + ");");

            // Save import issues
            if (!context.issues.isEmpty()) {
                importIssueRepository.saveAll(context.issues);
            }

            // Handle superseding if applicable
            if (request.getSupersedesDatasetId() != null) {
                Dataset old = datasetRepository.findById(request.getSupersedesDatasetId())
                        .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST,
                                "Superseded dataset not found: " + request.getSupersedesDatasetId(), HttpStatus.BAD_REQUEST));
                if (old.getStatus() != DatasetStatus.IMPORTED) {
                    throw new ConflictException(ErrorCode.CONFLICT,
                            "Cannot supersede dataset in status: " + old.getStatus());
                }
                old.setStatus(DatasetStatus.SUPERSEDED);
                old.setSupersededAt(now);
                datasetRepository.save(old);
            }

            // Update completed dataset
            dataset.setStatus(DatasetStatus.IMPORTED);
            dataset.setTotalRows(context.totalRows);
            dataset.setImportedRows(context.importedRows);
            dataset.setFailedRows(context.failedRows);
            dataset.setStates(new ArrayList<>(context.states));
            dataset.setCompletedAt(clock.instant());
            dataset = datasetRepository.save(dataset);

            auditService.log(callerUserId, null, datasetId, null, "DATASET_IMPORT", AuditOutcome.SUCCESS,
                    "dataset", datasetId.toString(), "{\"rows\":" + context.importedRows + ",\"tier\":\"" + request.getStorageTier() + "\"}", null);

            return mapToDetailResponse(dataset, context.issues);

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Dataset import failed", e);
            throw new ApiException(ErrorCode.INTERNAL_SERVER_ERROR, "Dataset import failed: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            tempFile.delete();
        }
    }

    private void streamXlsx(File file, VoterImportContext context) throws Exception {
        try (OPCPackage opc = OPCPackage.open(file)) {
            XSSFReader reader = new XSSFReader(opc);
            StylesTable styles = reader.getStylesTable();
            SharedStringsTable sst = (SharedStringsTable) reader.getSharedStringsTable();
            XMLReader parser = XMLHelper.newXMLReader();

            VoterSheetHandler sheetHandler = new VoterSheetHandler(context, jdbcTemplate, appProperties);
            XSSFSheetXMLHandler handler = new XSSFSheetXMLHandler(styles, sst, sheetHandler, false);
            parser.setContentHandler(handler);

            Iterator<InputStream> sheets = reader.getSheetsData();
            if (sheets.hasNext()) {
                try (InputStream sheetStream = sheets.next()) {
                    parser.parse(new InputSource(sheetStream));
                }
            }
            sheetHandler.flushRemaining();
        }
    }

    private DatasetDetailResponse mapToDetailResponse(Dataset d, List<ImportIssue> issues) {
        List<ImportIssueResponse> issueResponses = issues.stream()
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
                .createdAt(d.getCreatedAt())
                .completedAt(d.getCompletedAt())
                .issues(issueResponses)
                .build();
    }

    private static class VoterImportContext {
        final Long datasetId;
        final ElectionType electionType;
        final Set<String> states = new HashSet<>();
        final List<ImportIssue> issues = new ArrayList<>();
        int totalRows = 0;
        int importedRows = 0;
        int failedRows = 0;

        VoterImportContext(Long datasetId, ElectionType electionType) {
            this.datasetId = datasetId;
            this.electionType = electionType;
        }

        void addIssue(String sheet, int rowNo, String col, String code, String detail) {
            if (issues.size() < MAX_ISSUES) {
                issues.add(ImportIssue.builder()
                        .datasetId(datasetId)
                        .sheet(sheet)
                        .rowNo(rowNo)
                        .columnName(col)
                        .issueCode(code)
                        .issueDetail(detail)
                        .createdAt(Instant.now())
                        .build());
            }
        }
    }

    private static class VoterSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final VoterImportContext ctx;
        private final JdbcTemplate jdbcTemplate;
        private final AppProperties appProperties;
        private final byte[] mobileAesKey;

        private final Map<Integer, String> headerMap = new HashMap<>();
        private final Map<String, String> currentRow = new HashMap<>();
        private final List<Object[]> batchArgs = new ArrayList<>();
        private int currentRowNum = -1;

        VoterSheetHandler(VoterImportContext ctx, JdbcTemplate jdbcTemplate, AppProperties appProperties) {
            this.ctx = ctx;
            this.jdbcTemplate = jdbcTemplate;
            this.appProperties = appProperties;
            this.mobileAesKey = HexFormat.of().parseHex(appProperties.getSecurity().getVoterMobileAesKeyHex());
        }

        @Override
        public void startRow(int rowNum) {
            currentRowNum = rowNum;
            currentRow.clear();
        }

        @Override
        public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
            if (formattedValue == null) return;
            String val = formattedValue.trim();
            if (val.isEmpty()) return;

            int colIdx = getColumnIndex(cellReference);

            if (currentRowNum == 0) {
                // Header row
                String canonical = val.toLowerCase().replace(" ", "_");
                if (CANONICAL_COLUMNS.contains(canonical)) {
                    headerMap.put(colIdx, canonical);
                }
            } else {
                String colName = headerMap.get(colIdx);
                if (colName != null) {
                    currentRow.put(colName, val);
                }
            }
        }

        @Override
        public void endRow(int rowNum) {
            if (rowNum == 0) return; // Header finished

            ctx.totalRows++;

            String state = CriteriaNormalizer.normalizeText(currentRow.get("state"));
            String district = CriteriaNormalizer.normalizeText(currentRow.get("district"));
            String acNoStr = currentRow.get("ac_no");
            String partNoStr = currentRow.get("part_no");
            String boothNoStr = currentRow.get("booth_no");
            String serialNoStr = currentRow.get("serial_no");
            String voterId = CriteriaNormalizer.normalizeText(currentRow.get("voter_id"));

            if (state.isEmpty() || serialNoStr == null || voterId.isEmpty()) {
                ctx.failedRows++;
                ctx.addIssue("Sheet1", rowNum, "voter_id", "MISSING_REQUIRED_FIELD", "Missing required identification field at row " + rowNum);
                return;
            }

            ctx.states.add(state);

            // Compute source_record_key_hash: SHA-256 of state|district|ac_no|part_no|booth_no|serial_no|voter_id
            String keyStr = String.format("%s|%s|%s|%s|%s|%s|%s",
                    state, district,
                    acNoStr != null ? acNoStr.trim() : "",
                    partNoStr != null ? partNoStr.trim() : "",
                    boothNoStr != null ? boothNoStr.trim() : "",
                    serialNoStr.trim(), voterId);
            String sourceKeyHash = CryptoUtils.sha256Hex(keyStr);

            // Mobile encryption
            byte[] mobileEnc = null;
            Short mobileKeyVer = null;
            String mobileNo = currentRow.get("mobile_no");
            if (mobileNo != null && !mobileNo.isBlank()) {
                try {
                    mobileEnc = CryptoUtils.aesGcmEncrypt(mobileNo.trim().getBytes(), mobileAesKey);
                    mobileKeyVer = (short) appProperties.getSecurity().getVoterMobileKeyVersion();
                } catch (Exception e) {
                    ctx.addIssue("Sheet1", rowNum, "mobile_no", "ENCRYPTION_FAILED", "Failed to encrypt mobile at row " + rowNum);
                }
            }

            Object[] rowValues = new Object[]{
                    ctx.datasetId,
                    sourceKeyHash,
                    state,
                    district.isEmpty() ? null : district,
                    parseInteger(currentRow.get("pc_no")),
                    currentRow.get("pc_name_en"),
                    currentRow.get("pc_name_mt"),
                    parseInteger(acNoStr),
                    currentRow.get("ac_name_en"),
                    currentRow.get("ac_name_mt"),
                    parseInteger(partNoStr),
                    parseInteger(boothNoStr),
                    currentRow.get("booth_name_en"),
                    currentRow.get("booth_name_mt"),
                    currentRow.get("booth_address_en"),
                    currentRow.get("booth_address_mt"),
                    parseInteger(serialNoStr),
                    voterId,
                    currentRow.get("v_fname_en"),
                    currentRow.get("v_lname_en"),
                    currentRow.get("v_fname_mt"),
                    currentRow.get("v_lname_mt"),
                    currentRow.get("vr_fname_en"),
                    currentRow.get("vr_lname_en"),
                    currentRow.get("vr_fname_mt"),
                    currentRow.get("vr_lname_mt"),
                    currentRow.get("house_no"),
                    currentRow.get("house_location_en"),
                    currentRow.get("house_location_mt"),
                    parseInteger(currentRow.get("section_no")),
                    currentRow.get("section_name_en"),
                    currentRow.get("section_name_mt"),
                    parseInteger(currentRow.get("age")),
                    currentRow.get("sex"),
                    currentRow.get("rural_urban"),
                    currentRow.get("tehsil"),
                    currentRow.get("block"),
                    currentRow.get("pincode"),
                    currentRow.get("municipal_name_nagar_nigam_en"),
                    currentRow.get("municipal_name_nagar_nigam_mt"),
                    parseInteger(currentRow.get("nagar_nigam_ward_no")),
                    currentRow.get("municipal_name_nagar_palika_en"),
                    currentRow.get("municipal_name_nagar_palika_mt"),
                    parseInteger(currentRow.get("nagar_palika_ward_no")),
                    currentRow.get("municipal_name_nagar_parishad_en"),
                    currentRow.get("municipal_name_nagar_parishad_mt"),
                    parseInteger(currentRow.get("nagar_parishad_ward_no")),
                    currentRow.get("zilla_parishad_name_en"),
                    currentRow.get("zilla_parishad_name_mt"),
                    parseInteger(currentRow.get("zilla_parishad_name_ward_no")),
                    currentRow.get("panchayat_samiti_name_en"),
                    currentRow.get("panchayat_samiti_name_mt"),
                    parseInteger(currentRow.get("panchayat_samiti_name_ward_no")),
                    currentRow.get("panchayat_name_en"),
                    currentRow.get("panchayat_name_mt"),
                    parseInteger(currentRow.get("panchayat_no")),
                    mobileEnc,
                    mobileKeyVer,
                    currentRow.get("voter_image")
            };

            batchArgs.add(rowValues);
            ctx.importedRows++;

            if (batchArgs.size() >= BATCH_SIZE) {
                flushBatch();
            }
        }

        private void flushBatch() {
            if (batchArgs.isEmpty()) return;

            String insertSql = "INSERT INTO voter_d" + ctx.datasetId + " (" +
                    "dataset_id, source_record_key_hash, state, district, pc_no, pc_name_en, pc_name_mt, " +
                    "ac_no, ac_name_en, ac_name_mt, part_no, booth_no, booth_name_en, booth_name_mt, " +
                    "booth_address_en, booth_address_mt, serial_no, voter_id, v_fname_en, v_lname_en, " +
                    "v_fname_mt, v_lname_mt, vr_fname_en, vr_lname_en, vr_fname_mt, vr_lname_mt, " +
                    "house_no, house_location_en, house_location_mt, section_no, section_name_en, " +
                    "section_name_mt, age, sex, rural_urban, tehsil, block, pincode, " +
                    "municipal_name_nagar_nigam_en, municipal_name_nagar_nigam_mt, nagar_nigam_ward_no, " +
                    "municipal_name_nagar_palika_en, municipal_name_nagar_palika_mt, nagar_palika_ward_no, " +
                    "municipal_name_nagar_parishad_en, municipal_name_nagar_parishad_mt, nagar_parishad_ward_no, " +
                    "zilla_parishad_name_en, zilla_parishad_name_mt, zilla_parishad_name_ward_no, " +
                    "panchayat_samiti_name_en, panchayat_samiti_name_mt, panchayat_samiti_name_ward_no, " +
                    "panchayat_name_en, panchayat_name_mt, panchayat_no, mobile_no_enc, mobile_key_version, voter_image_ref" +
                    ") VALUES (" +
                    "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                    "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                    "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?" +
                    ") ON CONFLICT (dataset_id, source_record_key_hash) DO NOTHING";

            jdbcTemplate.batchUpdate(insertSql, batchArgs);
            batchArgs.clear();
        }

        void flushRemaining() {
            flushBatch();
        }

        private Integer parseInteger(String val) {
            if (val == null || val.isBlank()) return null;
            try {
                return (int) Double.parseDouble(val.trim());
            } catch (Exception e) {
                return null;
            }
        }

        private int getColumnIndex(String cellReference) {
            String colStr = cellReference.replaceAll("\\d", "");
            int col = 0;
            for (int i = 0; i < colStr.length(); i++) {
                col = col * 26 + (colStr.charAt(i) - 'A' + 1);
            }
            return col - 1;
        }
    }
}
