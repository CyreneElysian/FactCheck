package com.factcheck.exception;

/**
 * 业务异常基类，业务代码遇异常主动抛出
 */
public class BaseException extends RuntimeException {
    public BaseException() {
    }

    public BaseException(String msg) {
        super(msg);
    }
}
