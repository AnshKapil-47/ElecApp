package com.elecsarthi.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoidSaleRequest {

    @NotBlank(message = "Void reason is required")
    private String voidReason;
}
