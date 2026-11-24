package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerActivateRequest {

    @NotBlank(message = "Contact phone in E.164 format is required")
    private String contactPhone;

    @NotBlank(message = "10-character Crockford Base32 activation code is required")
    @Size(min = 10, max = 10, message = "Activation code must be exactly 10 characters")
    private String activationCode;

    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}
