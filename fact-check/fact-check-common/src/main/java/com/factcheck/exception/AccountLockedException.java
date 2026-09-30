package com.factcheck.exception;

/**
 * 账号禁用/注销异常
 */
public class AccountLockedException extends BaseException {
    public AccountLockedException(String msg) {
        super(msg);
    }
}
