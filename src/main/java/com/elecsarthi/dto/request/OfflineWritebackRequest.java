package com.elecsarthi.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfflineWritebackRequest {

    @NotNull(message = "Lease ID is required")
    private UUID leaseId;

    @NotEmpty(message = "Turnout marks list cannot be empty")
    @Valid
    private List<OfflineTurnoutMarkDto> marks;
}
