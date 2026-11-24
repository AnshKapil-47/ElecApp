package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfflineTurnoutMarkDto {

    @NotNull(message = "Voter row ID is required")
    private Long voterRowId;

    @NotNull(message = "Offline timestamp is required")
    private Instant offlineMarkedAt;
}
