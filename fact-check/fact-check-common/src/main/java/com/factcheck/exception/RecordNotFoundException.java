package com.factcheck.exception;

/**
 * 检测记录不存在异常
 */
public class RecordNotFoundException extends BaseException {
    public RecordNotFoundException(String msg) {
        super(msg);
    }
}
