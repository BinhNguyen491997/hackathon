package com.bank.settlement;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Mô phỏng đúng hình dạng RestExceptionHandler của repo thật: nhánh validation là <b>override</b> của
 * ResponseEntityExceptionHandler (không có annotation), còn các nhánh khác dùng @ExceptionHandler.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {

        return handleError(CommonErrorCode.INVALID_FIELD);
    }

    /** BaseException mang sẵn mã lỗi của nó, handler không áp mã mới. */
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<Object> handleBaseException(BaseException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getErrorCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAll(Exception ex) {
        return handleError(CommonErrorCode.SYSTEMS_ERROR);
    }

    private ResponseEntity<Object> handleError(CommonErrorCode errorCode) {
        return ResponseEntity.badRequest().body(errorCode.getMessage());
    }
}
