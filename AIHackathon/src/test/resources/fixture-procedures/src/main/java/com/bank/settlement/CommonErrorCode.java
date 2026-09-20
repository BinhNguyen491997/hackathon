package com.bank.settlement;

import org.springframework.http.HttpStatus;

/**
 * Bảng mã lỗi kiểu enum - hình dạng gặp ở mọi hệ thống core: {@code TÊN(mã, "thông điệp", HttpStatus)}.
 * Có cả mã âm và mã không khai báo HttpStatus để test phần phân loại theo hình dạng.
 */
public enum CommonErrorCode {

    SYSTEMS_ERROR(-1, "System error", HttpStatus.BAD_REQUEST),

    SUCCESSFUL(0, "Successful", HttpStatus.OK),

    INVALID_FIELD(400, "%s", HttpStatus.BAD_REQUEST),

    CALL_EVENT_PROCESS_ONE(
            1025,
            "Has error call package way4 '%s' with mes '%s'",
            HttpStatus.INTERNAL_SERVER_ERROR),

    EXECUTE_THIRTY_SERVICE_BY_SYS_ERROR(
            400, "%s An error occurred while executing the 3rd party api!",
            HttpStatus.INTERNAL_SERVER_ERROR),

    CARD_NOT_FOUND(1404, "Card not found"),

    ENROLL_ALREADY_DONE(1409, "Card already enrolled to C2P", HttpStatus.CONFLICT);

    private final int code;

    private final String message;

    private final HttpStatus status;

    CommonErrorCode(int code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    CommonErrorCode(int code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST);
    }

    public int getCode() {
        return this.code;
    }

    public String getMessage() {
        return this.message;
    }
}
