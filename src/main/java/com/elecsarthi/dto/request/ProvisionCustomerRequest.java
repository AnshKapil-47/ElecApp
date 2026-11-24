package com.elecsarthi.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
public class ProvisionCustomerRequest {

    @NotBlank(message = "Customer name is required.")
    private String customerName;

    private String partyOrOrgName;

    @NotBlank(message = "Contact phone is required.")
    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Phone must be in valid E.164 format (e.g. +919876543210).")
    private String contactPhone;

    @Email(message = "Invalid email format.")
    private String contactEmail;

    /**
     * Optional for Owner/Subowner creator; automatically set to calling Agent when created by Agent.
     */
    private Long owningAgentUserId;
}
