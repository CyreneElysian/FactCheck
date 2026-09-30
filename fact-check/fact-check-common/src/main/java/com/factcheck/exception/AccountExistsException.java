package com.factcheck.exception;

/**
 * 用户名已存在异常
 */
public class AccountExistsException extends BaseException {
    public AccountExistsException(String msg) {
        super(msg);
    }
}
