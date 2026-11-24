package com.elecsarthi.exception.common;

import com.elecsarthi.exception.errorcode.ErrorCode;
import org.springframework.http.HttpStatus;

public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message, HttpStatus.FORBIDDEN);
    }
}
