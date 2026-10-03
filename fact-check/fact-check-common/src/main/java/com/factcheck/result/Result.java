package com.factcheck.result;

import java.io.Serializable;
import lombok.Data;

/**
 * 统一返回结果，前端按固定格式解析
 * @param <T> 数据类型
 */
@Data
public class Result<T> implements Serializable {

    /**
     * 成功返回的默认文案（技术方案 §6.1 约定为 "success"；原先恒为 null）
     */
    public static final String SUCCESS_MSG = "success";

    private Integer code;
    private String msg;
    private T data;

    public static <T> Result<T> success() {
        Result<T> result = new Result<T>();
        result.code = 1;
        result.msg = SUCCESS_MSG;
        return result;
    }

    public static <T> Result<T> success(T object) {
        Result<T> result = new Result<T>();
        result.data = object;
        result.code = 1;
        result.msg = SUCCESS_MSG;
        return result;
    }

    public static <T> Result<T> error(String msg) {
        Result<T> result = new Result<T>();
        result.msg = msg;
        result.code = 0;
        return result;
    }
}
