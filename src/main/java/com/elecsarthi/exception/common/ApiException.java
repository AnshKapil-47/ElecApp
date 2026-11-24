package com.elecsarthi.exception.common;

import com.elecsarthi.exception.errorcode.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    private final HttpStatus status;

    public ApiException(ErrorCode errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, HttpStatus.BAD_REQUEST);
    }
}
