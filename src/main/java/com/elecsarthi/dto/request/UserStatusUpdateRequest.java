package com.elecsarthi.dto.request;

import com.elecsarthi.enums.UserStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserStatusUpdateRequest {

    @NotNull(message = "Status is required.")
    private UserStatus status;
}
