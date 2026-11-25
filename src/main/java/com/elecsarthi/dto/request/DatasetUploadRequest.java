package com.elecsarthi.dto.request;

import com.elecsarthi.enums.DatasetTier;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * Metadata for a dataset upload. Datasets are election-type agnostic: the election type is picked by the
 * Agent at sale time. Unknown properties (e.g. a legacy {@code electionTypeCode} sent by older web forms)
 * are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatasetUploadRequest {

    @NotBlank(message = "Dataset name is required.")
    private String name;

    @NotNull(message = "Storage tier is required.")
    private DatasetTier storageTier;

    private Long supersedesDatasetId;

    /**
     * Criteria parameters for filtering this dataset's intended scope
     * e.g. {"state": "MAHARASHTRA", "ac_no": 12}
     */
    private Map<String, Object> criteria;
}
