package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.ContentSafetyResult;
import com.factcheck.entity.ContentSafetyLog;
import com.factcheck.exception.BaseException;
import com.factcheck.mapper.ContentSafetyLogMapper;
import com.factcheck.properties.ContentSafetyProperties;
import com.factcheck.service.ContentSafetyService;
import com.factcheck.service.safety.ContentSafetyChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 内容安全审核服务实现
 *
 * <p>职责划分：本类负责"挑实现 + 写审核日志 + 执行处置策略"，
 * 具体怎么判定由 {@link ContentSafetyChecker} 实现（默认
 * {@link com.factcheck.service.safety.LocalRuleContentSafetyChecker} 本地规则）。</p>
 *
 * <h3>处置策略（分级）</h3>
 * <ul>
 *   <li><b>BLOCK</b>：抛 {@link BaseException}（文案"内容涉嫌违规，请修改后重试"），不落业务数据；</li>
 *   <li><b>REVIEW</b>：<b>放行</b>，只写审核日志并打 WARN。
 *       原因见 checker 的说明：事实核查平台的内容天然包含敏感词，拦截会挡掉核心场景；</li>
 *   <li><b>PASS</b>：正常放行，同样写一条审核日志（保证"提交过什么内容"可审计）。</li>
 * </ul>
 *
 * <h3>两个刻意的取舍</h3>
 * <ul>
 *   <li><b>审核器异常时放行（fail-open）</b>：内容安全不是本产品的核心价值，
 *       而误拦会直接让用户无法提交待核查内容。因此审核不可用时记 ERROR 日志并放行，
 *       而不是把整个提交链路一起拖垮。<b>若将来用于强合规场景，应改为 fail-closed。</b></li>
 *   <li><b>审核日志写失败不影响主流程</b>：日志失败只记 ERROR，
 *       不让"审计写不进去"演变成"用户提交不了"。</li>
 * </ul>
 */
@Service
@Slf4j
public class ContentSafetyServiceImpl implements ContentSafetyService {

    /**
     * 审核原因入库前的最大长度（t_content_safety_log.reason 为 VARCHAR(500)）
     */
    private static final int MAX_REASON_LENGTH = 500;

    private static final String CHECK_TYPE_TEXT = "text";
    private static final String CHECK_TYPE_IMAGE = "image";

    /**
     * 审核日志中的结果取值（与建表注释一致：0 通过，1 拒绝）
     */
    private static final int LOG_RESULT_PASS = 0;
    private static final int LOG_RESULT_REJECT = 1;

    private static final int SOURCE_TYPE_TEXT = 1;
    private static final int SOURCE_TYPE_IMAGE = 2;

    private final ContentSafetyLogMapper contentSafetyLogMapper;
    private final ContentSafetyProperties properties;
    private final List<ContentSafetyChecker> checkers;

    /**
     * 生效的审核器；配置的实现不存在时为 null（此时不做拦截并告警）
     */
    private final ContentSafetyChecker checker;

    public ContentSafetyServiceImpl(ContentSafetyLogMapper contentSafetyLogMapper,
                                    ContentSafetyProperties properties,
                                    ObjectProvider<ContentSafetyChecker> checkerProvider) {
        this.contentSafetyLogMapper = contentSafetyLogMapper;
        this.properties = properties;
        // 注意：编译目标是 Java 11，Stream.toList()（Java 16+）不可用，用 collect
        this.checkers = checkerProvider.stream().collect(Collectors.toList());
        this.checker = resolveChecker(properties, this.checkers);
        warnIfMisconfigured();
    }

    private static ContentSafetyChecker resolveChecker(ContentSafetyProperties properties,
                                                      List<ContentSafetyChecker> checkers) {
        String wanted = properties.getProvider() == null ? "" : properties.getProvider().trim();
        return checkers.stream()
                .filter(item -> item.name().equalsIgnoreCase(wanted))
                .findFirst()
                .orElse(null);
    }

    /**
     * 启动即暴露配置问题：实现名写错或整体关闭都会明确告警，
     * 避免出现"以为在审核、其实什么都没做"
     */
    private void warnIfMisconfigured() {
        if (!properties.isEnabled()) {
            log.warn("内容安全审核已关闭（fact-check.content-safety.enabled=false），提交内容不会被审核");
            return;
        }
        if (checker == null) {
            log.error("内容安全审核未生效：fact-check.content-safety.provider={} 没有对应实现"
                            + "（当前可用实现：{}），本次启动将不做任何内容拦截",
                    properties.getProvider(), checkerNames());
        } else {
            log.info("内容安全审核已启用：provider={}，命中违法交易/招揽类规则将拒绝提交，"
                            + "命中敏感话题仅记录日志（判定逻辑见 {}）",
                    checker.name(), checker.getClass().getSimpleName());
        }
    }

    private String checkerNames() {
        return checkers.isEmpty() ? "无"
                : String.join(", ", checkers.stream().map(ContentSafetyChecker::name).collect(Collectors.toList()));
    }

    @Override
    public ContentSafetyResult checkText(String text) {
        return check(text == null ? "" : text.trim(), CHECK_TYPE_TEXT, SOURCE_TYPE_TEXT);
    }

    @Override
    public ContentSafetyResult checkImage(String imageUrl) {
        String url = imageUrl == null ? "" : imageUrl.trim();
        if (url.isEmpty()) {
            return ContentSafetyResult.pass();
        }
        return check(url, CHECK_TYPE_IMAGE, SOURCE_TYPE_IMAGE);
    }

    /**
     * 统一的审核入口：判定 → 写日志 → 执行处置策略
     */
    private ContentSafetyResult check(String content, String checkType, int sourceType) {
        if (!properties.isEnabled() || checker == null || content.isEmpty()) {
            return ContentSafetyResult.pass();
        }

        ContentSafetyResult result;
        try {
            result = CHECK_TYPE_IMAGE.equals(checkType) ? checker.checkImage(content) : checker.checkText(content);
        } catch (Exception e) {
            // fail-open：审核器异常不应让用户提交不了内容（见类注释中的取舍说明）
            log.error("内容安全审核器执行失败，本次放行：checkType={}, error={}", checkType, e.getMessage(), e);
            saveLog(content, checkType, sourceType, LOG_RESULT_PASS,
                    "审核器异常已放行：" + e.getClass().getSimpleName());
            return ContentSafetyResult.pass();
        }
        if (result == null) {
            log.error("内容安全审核器返回 null（实现不符合约定），本次放行：checkType={}", checkType);
            saveLog(content, checkType, sourceType, LOG_RESULT_PASS, "审核器返回空结果已放行");
            return ContentSafetyResult.pass();
        }

        boolean blocked = result.isBlocked();
        saveLog(content, checkType, sourceType,
                blocked ? LOG_RESULT_REJECT : LOG_RESULT_PASS,
                describe(result));

        if (blocked) {
            log.warn("内容安全审核拒绝提交：checkType={}, category={}, detail={}",
                    checkType, result.getCategory(), result.getDetail());
            throw new BaseException(MessageConstant.CONTENT_ILLEGAL);
        }
        if (result.isReview()) {
            // 放行但留痕：这类内容在事实核查平台里很可能正是待核查的对象
            log.warn("内容安全审核命中敏感话题但放行：checkType={}, category={}, detail={}",
                    checkType, result.getCategory(), result.getDetail());
        }
        return result;
    }

    private static String describe(ContentSafetyResult result) {
        StringBuilder builder = new StringBuilder();
        if (result.getCategory() != null) {
            builder.append(result.getCategory());
        }
        if (result.getDetail() != null && !result.getDetail().isEmpty()) {
            if (builder.length() > 0) {
                builder.append("：");
            }
            builder.append(result.getDetail());
        }
        return builder.length() == 0 ? "通过" : builder.toString();
    }

    /**
     * 写审核日志；失败只记 ERROR，不影响主流程
     */
    private void saveLog(String content, String checkType, int sourceType, int result, String reason) {
        try {
            Long userId = BaseContext.getCurrentId();
            contentSafetyLogMapper.insert(ContentSafetyLog.builder()
                    .userId(userId == null ? 0L : userId)
                    .contentHash(sha256Hex(content))
                    .sourceType(sourceType)
                    .checkType(checkType)
                    .result(result)
                    .reason(truncate(reason, MAX_REASON_LENGTH))
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.error("内容安全审核日志写入失败（不影响本次提交）：checkType={}, error={}",
                    checkType, e.getMessage(), e);
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 内容哈希：用于审核日志关联与事后排查（与检测记录的 contentHash 同算法）
     */
    static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception e) {
            log.error("计算内容哈希失败：{}", e.getMessage());
            return null;
        }
    }
}
