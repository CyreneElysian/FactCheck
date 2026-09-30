package com.factcheck.exception;

/**
 * 内容安全审核不通过异常
 */
public class ContentIllegalException extends BaseException {
    public ContentIllegalException(String msg) {
        super(msg);
    }
}
