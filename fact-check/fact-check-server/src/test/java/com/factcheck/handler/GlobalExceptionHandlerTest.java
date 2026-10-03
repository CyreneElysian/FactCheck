package com.factcheck.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.factcheck.constant.MessageConstant;
import com.factcheck.exception.BaseException;
import com.factcheck.result.Result;
import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * {@link GlobalExceptionHandler} 单元测试。
 *
 * <p>锁定统一返回体契约：业务异常 BaseException("xx") 必须原样回传文案 xx、
 * code 为失败（0）；未知运行时异常必须返回 MessageConstant.UNKNOWN_ERROR，
 * 且不能把内部堆栈/异常类名泄漏给前端。</p>
 *
 * <p>完全离线：直接 new 出 handler 调用方法，不启动 Spring（@RestControllerAdvice 只是注解）。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * 业务异常：code=0（失败），msg 等于异常消息本身。
     */
    @Test
    @DisplayName("BaseException(\"xx\") → Result(code=0, msg=\"xx\")")
    void shouldConvertBaseExceptionToFailedResult() {
        Result<?> result = handler.exceptionHandler(new BaseException("xx"));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).isEqualTo("xx");
        assertThat(result.getData()).isNull();
    }

    /**
     * 业务异常文案是前端直接展示的提示，必须逐字透传（不允许被包装）。
     */
    @Test
    @DisplayName("BaseException 的文案逐字透传（含中英文混合）")
    void shouldKeepBaseExceptionMessageVerbatim() {
        String msg = MessageConstant.UPLOAD_TYPE_NOT_SUPPORTED;

        Result<?> result = handler.exceptionHandler(new BaseException(msg));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).isEqualTo(msg);
    }

    /**
     * 普通系统异常必须被吞掉细节，统一返回“未知错误”，避免泄漏内部实现。
     */
    @Test
    @DisplayName("普通 Exception → Result(code=0, msg=未知错误)，不泄漏异常信息")
    void shouldConvertUnexpectedExceptionToUnknownError() {
        Result<?> result = handler.exceptionHandler(new RuntimeException("数据库连接串泄露风险"));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).isEqualTo(MessageConstant.UNKNOWN_ERROR);
        assertThat(result.getMsg()).doesNotContain("数据库");
    }

    /**
     * 唯一键冲突的主路径：MyBatis-Spring 会把它翻译成 Spring 的 DuplicateKeyException。
     *
     * <p>早期版本只声明了 SQLIntegrityConstraintViolationException，因此用户名重复时
     * 往往直接掉进兜底、返回"未知错误"，前端提示不出来。</p>
     */
    @Test
    @DisplayName("Spring DuplicateKeyException（MyBatis 实际抛出的类型）→ 已存在")
    void shouldConvertSpringDuplicateKeyExceptionToAlreadyExists() {
        Result<?> result = handler.exceptionHandler(new DuplicateKeyException(
                "### Error updating database. Cause: java.sql.SQLIntegrityConstraintViolationException: "
                        + "Duplicate entry 'alice' for key 'username'"));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).isEqualTo(MessageConstant.ALREADY_EXISTS);
    }

    /**
     * 非唯一键的数据完整性异常（非空、外键等）给一个可理解的文案，
     * 而不是混在"未知错误"里让人无从排查。
     */
    @Test
    @DisplayName("DataIntegrityViolationException（非唯一键）→ 数据完整性校验失败")
    void shouldConvertOtherDataIntegrityViolation() {
        Result<?> result = handler.exceptionHandler(
                new DataIntegrityViolationException("Column 'username' cannot be null"));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).isEqualTo(MessageConstant.DATA_INTEGRITY_VIOLATION);
    }

    /**
     * 唯一键冲突（Duplicate entry）应转换为"已存在"，而不是"未知错误"。
     *
     * <p>判断依据是错误码 / SQLState，不再依赖英文报文。</p>
     */
    @Test
    @DisplayName("SQLIntegrityConstraintViolationException(errorCode=1062) → 已存在")
    void shouldConvertDuplicateEntryToAlreadyExists() {
        Result<?> result = handler.exceptionHandler(new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'alice' for key 'username'", "23000", 1062));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).isEqualTo(MessageConstant.ALREADY_EXISTS);
    }

    /**
     * 关键回归：数据库返回**本地化文案**时（报文里没有 "Duplicate entry" 这个词），
     * 也必须按错误码识别为唯一键冲突。
     *
     * <p>这正是旧实现（`message.contains("Duplicate entry")`）会失效的场景。</p>
     */
    @Test
    @DisplayName("本地化报文的唯一键冲突（errorCode=1062，报文不含英文）→ 仍识别为已存在")
    void shouldDetectDuplicateKeyFromErrorCodeRegardlessOfLocale() {
        Result<?> result = handler.exceptionHandler(new SQLIntegrityConstraintViolationException(
                "重复条目 'alice' 于键 'username'", "23000", 1062));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).isEqualTo(MessageConstant.ALREADY_EXISTS);
        assertThat(GlobalExceptionHandler.isDuplicateKey(1062, "23000")).isTrue();
    }

    /**
     * 请求体为空或 JSON 写错时，必须给出可操作的提示，而不是笼统的"未知错误"。
     * （实测：POST 空体时客户端只看到"未知错误"，完全不知道是自己传错了。）
     */
    @Test
    @DisplayName("请求体不可读 → 明确的“请求体格式不正确或为空”")
    void shouldConvertUnreadableBody() {
        Result<?> result = handler.exceptionHandler(new HttpMessageNotReadableException("Required request body is missing"));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).isEqualTo(MessageConstant.REQUEST_BODY_INVALID);
    }

    /**
     * 缺少必填查询参数时提示具体是哪个参数（分页接口的 page/pageSize 已有默认值，这里覆盖其余情况）。
     */
    @Test
    @DisplayName("缺少必填参数 → 提示参数名")
    void shouldConvertMissingRequestParameter() {
        Result<?> result = handler.exceptionHandler(
                new MissingServletRequestParameterException("recordId", "Long"));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).contains("recordId");
    }

    /**
     * 其他数据库（PostgreSQL / H2）用 SQLState 23505 表示唯一性冲突，同样要识别。
     */
    @Test
    @DisplayName("SQLState=23505（标准唯一性冲突）→ 已存在")
    void shouldDetectDuplicateKeyFromSqlState() {
        Result<?> result = handler.exceptionHandler(new SQLIntegrityConstraintViolationException(
                "duplicate key value violates unique constraint \"uk_username\"", "23505", 0));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).isEqualTo(MessageConstant.ALREADY_EXISTS);
    }

    /**
     * 非唯一键冲突的 SQL 约束异常回退为"未知错误"。
     */
    @Test
    @DisplayName("其他 SQL 约束异常 → 未知错误")
    void shouldConvertOtherSqlConstraintExceptionToUnknownError() {
        Result<?> result = handler.exceptionHandler(
                new SQLIntegrityConstraintViolationException("Column 'username' cannot be null"));

        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMsg()).isEqualTo(MessageConstant.UNKNOWN_ERROR);
        assertThat(GlobalExceptionHandler.isDuplicateKey(0, null)).isFalse();
    }
}
