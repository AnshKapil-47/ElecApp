package com.elecsarthi.exception.common;

import com.elecsarthi.exception.errorcode.ErrorCode;
import org.springframework.http.HttpStatus;

public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }
}
