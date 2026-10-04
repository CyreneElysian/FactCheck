package com.factcheck.util;

import com.factcheck.exception.BaseException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
     * 分页参数校验（**未传时取默认值**，返回可直接使用的页码与每页条数）
     *
     * <p>与 {@link #requirePaging(Integer, Integer)} 的区别：后者把 null 视为非法，
     * 适合 Controller 已经用 {@code @RequestParam(defaultValue = ...)} 兜底的接口；
     * 本方法把 null 落到默认值（页码 1、每页 10），适合直接用 DTO 绑定查询参数的接口
     * （DTO 字段缺省即为 null）——语义仍是"用户没传就用默认"，但**传了非法值就必须拒绝**。</p>
     *
     * <p>要修的缺陷：6 个列表接口原先只做
     * {@code page == null ? 1 : page} 兜底、不做上下界校验，于是
     * {@code page=0} 会让 PageHelper 退化为"不分页"而把整表拉进内存，
     * {@code pageSize=100000} 同样可被用来打爆内存。</p>
     *
     * @return 校验后的分页参数（永不为 null，且已保证 1 ≤ page、1 ≤ pageSize ≤ {@link #MAX_PAGE_SIZE}）
     */
    public static Paging requirePagingOrDefault(Integer page, Integer pageSize) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedPageSize = pageSize == null ? 10 : pageSize;
        requirePaging(resolvedPage, resolvedPageSize);
        return new Paging(resolvedPage, resolvedPageSize);
    }

    /**
     * 校验后的分页参数（页码与每页条数）
     */
    public static final class Paging {
        private final int page;
        private final int pageSize;

        Paging(int page, int pageSize) {
            this.page = page;
            this.pageSize = pageSize;
        }

        public int getPage() {
            return page;
        }

        public int getPageSize() {
            return pageSize;
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

    /**
     * 密码最短长度（无依赖的最小强度要求）
     */
    public static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * 密码最长长度（与 {@code t_user.password VARCHAR(100)} 的 BCrypt 密文长度留足余量）
     */
    public static final int MAX_PASSWORD_LENGTH = 64;

    /**
     * 弱口令黑名单（小写比较）
     *
     * <p>只收最常被撞库工具优先尝试的那一批，不做穷举——真正的防线是"字母+数字"组合要求
     * 与后续的登录限流。</p>
     */
    private static final Set<String> WEAK_PASSWORDS = new HashSet<>(Arrays.asList(
            "12345678", "123456789", "1234567890", "87654321",
            "password", "password1", "passw0rd", "p@ssword", "p@ssw0rd",
            "qwertyui", "qwerty123", "qwertyuiop", "1qaz2wsx", "1q2w3e4r",
            "iloveyou", "admin123", "administrator", "root1234",
            "abc12345", "a123456", "aa123456", "abc123456",
            "11111111", "00000000", "88888888", "66666666",
            "test1234", "changeme", "letmein1", "welcome1", "football",
            "wechat123", "zhangsan", "woaini1314", "5201314520"));

    /**
     * 密码强度校验（**创建与管理密码时使用，不用于登录**）
     *
     * <h3>为什么必须做</h3>
     * 此前注册与改密**没有任何强度校验**：{@code 123456}、{@code password} 都能直接设置。
     * 配合"登录按用户名限流 5 次/10 分钟"仍可被慢速爆破，弱口令是账号失陷的首要原因。
     *
     * <h3>为什么"不用于登录"</h3>
     * 校验只作用于**设置新密码**的路径（注册、改密、管理员建号）。
     * 登录不对已存在的口令做强度检查——否则历史弱口令账号会在改密前直接无法登录，
     * 那等于用一次"安全加固"把用户锁在门外。改为"下次改密时强制变强"。
     *
     * <h3>规则</h3>
     * <ol>
     *   <li>长度 8–64，且**首尾不能有空白**（密码参与哈希，空白是隐性错误来源）</li>
     *   <li>必须同时包含字母与数字（不强制大小写与符号：那会让可用性显著下降，
     *       而"字母+数字+≥8 位"已能挡住绝大多数撞库字典）</li>
     *   <li>不在常见弱口令黑名单里（大小写不敏感）</li>
     *   <li>不能与用户名相同（大小写不敏感）——防止"用户名即密码"</li>
     * </ol>
     *
     * @param value    明文密码
     * @param username 关联用户名，可为 null（注册时用）；传 null 则跳过第 4 条
     * @return 原样返回密码（便于链式调用），不做 trim 之外的加工
     */
    public static String requireStrongPassword(String value, String username) {
        if (value == null || value.isEmpty()) {
            throw new BaseException("密码不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new BaseException("密码首尾不能包含空格");
        }
        if (value.length() < MIN_PASSWORD_LENGTH) {
            throw new BaseException("密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (value.length() > MAX_PASSWORD_LENGTH) {
            throw new BaseException("密码长度不能超过 " + MAX_PASSWORD_LENGTH + " 位");
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                hasLetter = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new BaseException("密码必须同时包含字母和数字");
        }

        String lower = value.toLowerCase();
        if (WEAK_PASSWORDS.contains(lower)) {
            throw new BaseException("密码过于常见，请更换更复杂的密码");
        }
        if (username != null && lower.equals(username.trim().toLowerCase())) {
            throw new BaseException("密码不能与用户名相同");
        }
        return value;
    }
}
