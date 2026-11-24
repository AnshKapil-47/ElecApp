package com.elecsarthi.exception.common;

import com.elecsarthi.exception.errorcode.ErrorCode;
import org.springframework.http.HttpStatus;

public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message, HttpStatus.UNAUTHORIZED);
    }
}
