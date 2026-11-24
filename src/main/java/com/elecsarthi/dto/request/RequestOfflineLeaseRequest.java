package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RequestOfflineLeaseRequest {

    @NotNull(message = "Booth assignment ID is required")
    private Long boothAssignmentId;
}
