package com.elecsarthi.controller;

import com.elecsarthi.dto.request.DatasetUploadRequest;
import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.dto.response.DatasetSummaryResponse;
import com.elecsarthi.security.service.ElecSarthiUserPrincipal;
import com.elecsarthi.service.DatasetImportService;
import com.elecsarthi.service.DatasetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/datasets")
@RequiredArgsConstructor
@Tag(name = "Datasets", description = "Dataset upload, ingestion and management (Owner and Subowner only)")
public class DatasetController {

    private final DatasetService datasetService;
    private final DatasetImportService datasetImportService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Upload and stream ingestion of an election voter .xlsx dataset")
    public ResponseEntity<DatasetDetailResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") @Valid DatasetUploadRequest metadata,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {

        DatasetDetailResponse response = datasetImportService.importDataset(file, metadata, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "List imported datasets (paginated)")
    public ResponseEntity<Page<DatasetSummaryResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(datasetService.listDatasets(pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Get dataset details and import issue summary")
    public ResponseEntity<DatasetDetailResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(datasetService.getDataset(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER')")
    @Operation(summary = "Delete dataset and drop partitions (blocked if active entitlements exist)")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal ElecSarthiUserPrincipal principal) {
        datasetService.deleteDataset(id, principal.getUserId());
        return ResponseEntity.noContent().build();
    }
}
