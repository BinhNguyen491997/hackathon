package com.bank.settlement;

/** Exception nghiệp vụ mang theo một hằng mã lỗi - dạng phổ biến ở core banking. */
public class BaseException extends RuntimeException {

    private final CommonErrorCode errorCode;

    public BaseException(Object[] args, CommonErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public CommonErrorCode getErrorCode() {
        return this.errorCode;
    }
}
