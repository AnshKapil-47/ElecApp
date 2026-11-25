package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartSyncSessionRequest {

    @NotNull(message = "Entitlement ID is required")
    private Long entitlementId;

    /** Optional; protocol version 1 is assumed when omitted (a primitive short made it mandatory in JSON). */
    private Short protocolVersion;
}
