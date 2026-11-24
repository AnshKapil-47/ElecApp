package com.elecsarthi.exception.errorcode;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String code,
    String message,
    Instant timestamp
) {
    public ErrorResponse(String code, String message) {
        this(code, message, Instant.now());
    }
}
