package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReassignAgentRequest {

    @NotNull(message = "New owning agent user ID is required.")
    private Long owningAgentUserId;
}
