package com.factcheck.util;

import com.factcheck.exception.BaseException;

/**
 * 参数校验工具（轻量、无框架依赖）
 *
 * <h3>为什么不用 Bean Validation（{@code @Valid} + {@code @NotBlank}）</h3>
 * 本项目编译与运行在 Spring Boot 2.7（{@code javax.validation} 命名空间），
 * 而当前的离线 Maven 仓库里只有 {@code hibernate-validator 8.x}（{@code jakarta} 命名空间），
 * 没有与 Boot 2.7 匹配的 6.x 实现。此时若只加注解不加实现，
 * {@code @Valid} 会被**静默忽略**——"看起来校验了、实际没校验"比不校验更危险。
 * 因此这里用显式校验方法把规则落到代码里，全部可单测、无额外依赖。
 *
 * <p>联网环境要换成标准做法时，改造是机械的：引入
 * {@code spring-boot-starter-validation}，把这里的调用换成 DTO 上的注解即可，
 * 校验规则与文案可直接搬过去。</p>
 *
 * <h3>文案约定</h3>
 * 抛出的 {@link BaseException} 文案会由全局异常处理器**原样返回给前端**，
 * 因此必须是用户看得懂的中文提示，且不能泄漏内部实现。
 */
public final class ValidationUtils {

    /**
     * 分页每页最大条数：防止 {@code pageSize=100000} 这类请求把整表拉进内存
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 单条检测内容最大长度：与 Python 侧 {@code DETECT_MAX_TEXT_LENGTH} 对齐，
     * 让超长内容在这里就被明确拒绝，而不是被静默截断
     */
    public static final int MAX_CLAIM_LENGTH = 5000;

    private ValidationUtils() {
    }

    /**
     * 必填文本：去首尾空白后不能为空，且长度受限
     *
     * <p>注意用的是 trim 后的判断，纯空白字符串（"   "）会被拒绝——
     * 这与 {@code StringUtils.hasLength} 的行为不同，后者放行纯空白。</p>
     *
     * @return 去空白后的文本，便于调用方直接落库
     */
    public static String requireText(String value, String field, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw new BaseException(field + "不能为空");
        }
        if (text.length() > maxLength) {
            throw new BaseException(field + "长度不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    /**
     * 选填文本：为空返回 null，非空时去空白并校验长度
     */
    public static String optionalText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > maxLength) {
            throw new BaseException(field + "长度不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    /**
     * 取值必须在给定集合内
     */
    public static void requireOneOf(Integer value, String field, int... allowed) {
        if (value == null) {
            throw new BaseException(field + "不能为空");
        }
        for (int item : allowed) {
            if (item == value) {
                return;
            }
        }
        throw new BaseException(field + "取值不合法");
    }

    /**
     * 必填正数
     */
    public static Long requirePositive(Long value, String field) {
        if (value == null) {
            throw new BaseException(field + "不能为空");
        }
        if (value <= 0) {
            throw new BaseException(field + "必须为正整数");
        }
        return value;
    }

    /**
     * 分页参数校验
     *
     * <p>为什么必须校验：{@code PageHelper.startPage(0, n)} 会被当成"不分页"而返回全表，
     * {@code pageSize} 过大同样会把整表拉进内存。</p>
     */
    public static void requirePaging(Integer page, Integer pageSize) {
        if (page == null || page < 1) {
            throw new BaseException("页码不正确（需不小于 1）");
        }
        if (pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BaseException("每页条数不正确（需在 1 ~ " + MAX_PAGE_SIZE + " 之间）");
        }
    }

    /**
     * 选填的 http(s) 链接：为空放行，非空必须带协议头
     */
    public static String optionalHttpUrl(String value, String field, int maxLength) {
        String text = optionalText(value, field, maxLength);
        if (text == null) {
            return null;
        }
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            throw new BaseException(field + "必须以 http:// 或 https:// 开头");
        }
        return text;
    }
}
