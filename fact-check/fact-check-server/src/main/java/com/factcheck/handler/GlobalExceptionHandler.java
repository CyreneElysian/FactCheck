package com.factcheck.handler;

import com.factcheck.constant.MessageConstant;
import com.factcheck.exception.BaseException;
import com.factcheck.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;

/**
 * 全局异常处理器，统一转换为 Result 返回
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * MySQL 的唯一键冲突错误码（ER_DUP_ENTRY）
     */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    /**
     * 标准 SQL 唯一性约束冲突的 SQLState（PostgreSQL / H2 等使用）
     */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /**
     * 业务异常处理
     */
    @ExceptionHandler
    public Result exceptionHandler(BaseException ex) {
        log.error("业务异常：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 唯一键冲突（主路径）
     *
     * <p>MyBatis-Spring 会把驱动的完整性约束异常翻译成 Spring 的
     * {@link DuplicateKeyException}，所以这才是实际会命中的分支。
     * 早期版本只声明了 {@code SQLIntegrityConstraintViolationException}，
     * 结果唯一键冲突常常直接掉进兜底、返回"未知错误"（用户名重复时前端提示不出来）。</p>
     */
    @ExceptionHandler
    public Result exceptionHandler(DuplicateKeyException ex) {
        log.warn("唯一键冲突：{}", ex.getMostSpecificCause().getMessage());
        return Result.error(MessageConstant.ALREADY_EXISTS);
    }

    /**
     * 其他数据完整性约束异常（非空约束、外键约束等，统一回"未知错误"但保留可排查的日志）
     */
    @ExceptionHandler
    public Result exceptionHandler(DataIntegrityViolationException ex) {
        log.error("数据完整性约束异常：{}", ex.getMostSpecificCause().getMessage());
        return Result.error(MessageConstant.DATA_INTEGRITY_VIOLATION);
    }

    /**
     * SQL 完整性约束异常兜底（异常翻译被关闭时才会走到这里）
     *
     * <p>判断依据改为**错误码 / SQLState**，不再匹配英文报文字符串：
     * 早期实现判断 {@code message.contains("Duplicate entry")}，
     * 依赖 MySQL 的报文格式与语言环境，一旦数据库返回本地化文案或换成别的数据库，
     * 就会退化成"未知错误"。</p>
     */
    @ExceptionHandler
    public Result exceptionHandler(SQLIntegrityConstraintViolationException ex) {
        log.error("SQL 完整性约束异常：errorCode={}, sqlState={}, message={}",
                ex.getErrorCode(), ex.getSQLState(), ex.getMessage());
        if (isDuplicateKey(ex.getErrorCode(), ex.getSQLState())) {
            return Result.error(MessageConstant.ALREADY_EXISTS);
        }
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }

    /**
     * 是否为唯一键冲突：按错误码 / SQLState 判断，与报文的语言无关
     */
    static boolean isDuplicateKey(int errorCode, String sqlState) {
        return errorCode == MYSQL_DUPLICATE_ENTRY
                || SQLSTATE_UNIQUE_VIOLATION.equals(sqlState);
    }

    /**
     * 请求体缺失或格式错误（JSON 解析失败、空体）
     *
     * <p>不单独处理的话会落到兜底分支返回"未知错误"，客户端根本看不出是自己传错了
     * （实测 POST 空体就是这种情况）。</p>
     */
    @ExceptionHandler
    public Result exceptionHandler(HttpMessageNotReadableException ex) {
        log.warn("请求体不可读：{}", ex.getMessage());
        return Result.error(MessageConstant.REQUEST_BODY_INVALID);
    }

    /**
     * 缺少必填查询参数（例如分页接口漏传且没有默认值时）
     */
    @ExceptionHandler
    public Result exceptionHandler(MissingServletRequestParameterException ex) {
        log.warn("缺少必填参数：{}", ex.getParameterName());
        return Result.error("缺少必填参数：" + ex.getParameterName());
    }

    /**
     * 系统异常兜底处理
     */
    @ExceptionHandler
    public Result exceptionHandler(Exception ex) {
        log.error("系统异常", ex);
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }
}
