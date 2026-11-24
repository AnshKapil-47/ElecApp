package com.elecsarthi.dto.request;

import com.elecsarthi.enums.DatasetTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateSaleRequest {

    @NotNull(message = "Customer account ID is required")
    private Long customerAccountId;

    @NotNull(message = "Election type ID is required")
    private Short electionTypeId;

    @NotNull(message = "Storage tier is required")
    private DatasetTier storageTier;

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    @NotEmpty(message = "Sale must contain at least one line")
    @Valid
    private List<SaleLineRequest> lines;
}
