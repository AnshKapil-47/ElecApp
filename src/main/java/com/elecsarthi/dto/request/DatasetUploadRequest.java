package com.elecsarthi.dto.request;

import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.ElectionTypeCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatasetUploadRequest {

    @NotBlank(message = "Dataset name is required.")
    private String name;

    @NotNull(message = "Election type code is required.")
    private ElectionTypeCode electionTypeCode;

    @NotNull(message = "Storage tier is required.")
    private DatasetTier storageTier;

    private Long supersedesDatasetId;

    /**
     * Criteria parameters for filtering this dataset's intended scope
     * e.g. {"state": "MAHARASHTRA", "ac_no": 12}
     */
    private Map<String, Object> criteria;
}
