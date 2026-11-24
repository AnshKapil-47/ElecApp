package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartSyncSessionRequest {

    @NotNull(message = "Entitlement ID is required")
    private Long entitlementId;

    @Builder.Default
    private short protocolVersion = 1;
}
