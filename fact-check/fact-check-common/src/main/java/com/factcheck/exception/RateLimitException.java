package com.factcheck.exception;

/**
 * 访问频率超限异常
 */
public class RateLimitException extends BaseException {
    public RateLimitException(String msg) {
        super(msg);
    }
}
