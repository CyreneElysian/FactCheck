package com.factcheck.exception;

/**
 * 申诉记录不存在异常
 */
public class AppealNotFoundException extends BaseException {
    public AppealNotFoundException(String msg) {
        super(msg);
    }
}
