package com.elecsarthi.exception.common;

import com.elecsarthi.exception.errorcode.ErrorCode;
import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {
    public ConflictException(ErrorCode code, String message) {
        super(code, message, HttpStatus.CONFLICT);
    }

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT);
    }
}
