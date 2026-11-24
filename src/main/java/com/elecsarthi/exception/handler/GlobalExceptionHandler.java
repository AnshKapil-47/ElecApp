package com.elecsarthi.exception.handler;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.exception.errorcode.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
