package com.elecsarthi.exception.common;

import com.elecsarthi.exception.errorcode.ErrorCode;
import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ApiException {
    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message, HttpStatus.NOT_FOUND);
    }

    public ResourceNotFoundException() {
        this("The requested resource was not found.");
    }
}
