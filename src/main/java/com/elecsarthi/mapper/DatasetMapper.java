package com.elecsarthi.mapper;

import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.dto.response.DatasetSummaryResponse;
import com.elecsarthi.dto.response.ImportIssueResponse;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ImportIssue;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class DatasetMapper {

    public DatasetSummaryResponse toSummaryResponse(Dataset d) {
        if (d == null) return null;
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

    public DatasetDetailResponse toDetailResponse(Dataset d, List<ImportIssue> issues) {
        if (d == null) return null;
        List<ImportIssueResponse> issueResponses = (issues != null)
                ? issues.stream().map(this::toIssueResponse).collect(Collectors.toList())
                : Collections.emptyList();

        return DatasetDetailResponse.builder()
                .id(d.getId())
                .name(d.getName())
                .originalFileName(d.getOriginalFileName())
                .fileSha256(d.getFileSha256())
                .storageTier(d.getStorageTier())
                .status(d.getStatus())
                .supersedesDatasetId(d.getSupersedesDatasetId())
                .supersededAt(d.getSupersededAt())
                .totalRows(d.getTotalRows())
                .importedRows(d.getImportedRows())
                .failedRows(d.getFailedRows())
                .states(d.getStates())
                .deleteAfter(d.getDeleteAfter())
                .importedByUserId(d.getImportedByUserId())
                .createdAt(d.getCreatedAt())
                .completedAt(d.getCompletedAt())
                .issues(issueResponses)
                .build();
    }

    public ImportIssueResponse toIssueResponse(ImportIssue i) {
        if (i == null) return null;
        return ImportIssueResponse.builder()
                .id(i.getId())
                .sheet(i.getSheet())
                .rowNo(i.getRowNo())
                .columnName(i.getColumnName())
                .issueCode(i.getIssueCode())
                .issueDetail(i.getIssueDetail())
                .createdAt(i.getCreatedAt())
                .build();
    }
}
