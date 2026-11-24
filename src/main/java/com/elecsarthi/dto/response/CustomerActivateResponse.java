package com.elecsarthi.dto.response;

import com.elecsarthi.enums.Role;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerActivateResponse {
    private String message;
    private String registrationToken;
    private Long userId;
    private Role role;
    private Long customerAccountId;
}
