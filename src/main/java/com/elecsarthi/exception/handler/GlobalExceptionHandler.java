package com.elecsarthi.exception.handler;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.exception.errorcode.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final Clock clock;

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        log.warn("API exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getStatus())
                .body(error(ex.getErrorCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", details);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.VALIDATION_FAILED.name(), "Validation failed: " + details));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        log.warn("Database constraint violation: {}", msg);

        if (msg.contains("ex_ent_no_overlap")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error(ErrorCode.CONFLICT.name(), "Overlapping active entitlement already exists for this area and period."));
        }
        if (msg.contains("ex_ba_slot")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error(ErrorCode.SUBUSER_BOOTH_LIMIT_EXCEEDED.name(), "Subuser already has maximum active booth assignments in this time slot."));
        }
        if (msg.contains("ex_ba_same_booth")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error(ErrorCode.CONFLICT.name(), "Subuser is already assigned to this booth in this period."));
        }
        if (msg.contains("ux_sale_agent_idempotency") || msg.contains("ux_sale_line_criteria")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error(ErrorCode.CONFLICT.name(), "Duplicate sale submission or area already included in sale."));
        }
        if (msg.contains("ux_one_customer_admin")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error(ErrorCode.CONFLICT.name(), "Customer account already has a primary login."));
        }
        if (msg.contains("ux_customer_phone")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error(ErrorCode.CONFLICT.name(), "Customer contact phone number is already registered."));
        }

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(ErrorCode.CONFLICT.name(), "Database constraint violation occurred."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(error(ErrorCode.FORBIDDEN.name(), "Access is denied."));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error(ErrorCode.UNAUTHORIZED.name(), "Authentication failed."));
    }

    /**
     * Client mistakes that Spring MVC rejects before a controller runs (unreadable body, missing multipart part,
     * wrong path-variable type, wrong method or media type, unknown path) are 4xx answers, never 500. The messages
     * are fixed strings so nothing about the parser or the server internals is echoed.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class, TypeMismatchException.class, MultipartException.class})
    public ResponseEntity<ErrorResponse> handleUnreadableRequest(Exception ex) {
        if (ex instanceof MaxUploadSizeExceededException) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(error(ErrorCode.BAD_REQUEST.name(), "The uploaded file is too large."));
        }
        log.warn("Rejected malformed request: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.BAD_REQUEST.name(), "The request is malformed or incomplete."));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.VALIDATION_FAILED.name(), "Validation failed."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(error(ErrorCode.BAD_REQUEST.name(), "Method not allowed."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(error(ErrorCode.BAD_REQUEST.name(), "Unsupported media type."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(ErrorCode.RESOURCE_NOT_FOUND.name(), "Resource not found."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled server error", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(ErrorCode.INTERNAL_SERVER_ERROR.name(), "An unexpected server error occurred."));
    }

    private ErrorResponse error(String code, String message) {
        return new ErrorResponse(code, message, clock.instant());
    }
}
