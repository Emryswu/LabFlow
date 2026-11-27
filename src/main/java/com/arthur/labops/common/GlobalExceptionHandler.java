package com.arthur.labops.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.persistence.OptimisticLockException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusiness(BusinessException exception) {
        return errorResponse(exception.getStatus(), exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return errorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败", fields);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception) {
        return errorResponse(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前角色无权执行该操作");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException exception) {
        log.warn("Data integrity violation", exception);
        return errorResponse(HttpStatus.CONFLICT, "DATA_CONFLICT", "数据与现有记录冲突");
    }

    /**
     * PESSIMISTIC_WRITE timeouts and {@code @Version} lost updates both become 409.
     * Reservation writers: decide, cancel, complete, expiry, work-order cancellation.
     * Work-order writers: claim, transition. Spring wraps Hibernate stale-state as
     * {@link ObjectOptimisticLockingFailureException}; a raw JPA
     * {@link OptimisticLockException} is mapped the same way if it reaches MVC.
     */
    @ExceptionHandler({
            PessimisticLockingFailureException.class,
            OptimisticLockingFailureException.class,
            ObjectOptimisticLockingFailureException.class,
            OptimisticLockException.class
    })
    ResponseEntity<ApiError> handleLockFailure(RuntimeException exception) {
        log.warn("Lock failure", exception);
        return errorResponse(HttpStatus.CONFLICT, "RESOURCE_BUSY", "资源正被占用，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("Unhandled server exception", exception);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试");
    }

    private ResponseEntity<ApiError> errorResponse(HttpStatus status, String code, String message) {
        return errorResponse(status, code, message, Map.of());
    }

    private ResponseEntity<ApiError> errorResponse(HttpStatus status, String code, String message,
                                                   Map<String, String> fields) {
        ApiError body = new ApiError(Instant.now(), status.value(), code, message, fields);
        return ResponseEntity.status(status).body(body);
    }
}
