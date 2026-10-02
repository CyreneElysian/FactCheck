package com.factcheck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容安全审核结果
 *
 * <p>三级处置（见《技术方案》第 10 章安全设计）：</p>
 * <ul>
 *   <li><b>PASS</b>：未命中任何规则；</li>
 *   <li><b>REVIEW</b>：命中"敏感话题词"但**放行**，只写审核日志。
 *       这类内容在事实核查平台里极可能正是**待核查的对象**——例如
 *       "某地又出现新型病毒""网传某国发生恐怖袭击"——直接拦截会挡掉产品最核心的场景；</li>
 *   <li><b>BLOCK</b>：命中"违法交易/招揽"类规则（色情招嫖、赌博推广、诈骗引流、违禁品交易），
 *       直接拒绝提交并写审核日志。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentSafetyResult {

    /**
     * 处置级别
     */
    public enum Level {
        /** 通过 */
        PASS,
        /** 放行但留痕（命中敏感话题） */
        REVIEW,
        /** 拒绝 */
        BLOCK
    }

    /**
     * 处置级别
     */
    private Level level;

    /**
     * 命中类别（如 色情招嫖 / 赌博推广 / 诈骗引流 / 违禁品交易 / 敏感话题），未命中为 null
     */
    private String category;

    /**
     * 命中的具体内容（用于审核日志与排查；**不直接展示给用户**，避免变相教人绕过规则）
     */
    private String detail;

    public boolean isBlocked() {
        return level == Level.BLOCK;
    }

    public boolean isReview() {
        return level == Level.REVIEW;
    }

    /**
     * 是否未命中任何规则（level 为 null 时按通过处理，避免调用方 NPE）
     */
    public boolean isPass() {
        return level == null || level == Level.PASS;
    }

    public static ContentSafetyResult pass() {
        return ContentSafetyResult.builder().level(Level.PASS).build();
    }

    public static ContentSafetyResult review(String category, String detail) {
        return ContentSafetyResult.builder().level(Level.REVIEW).category(category).detail(detail).build();
    }

    public static ContentSafetyResult block(String category, String detail) {
        return ContentSafetyResult.builder().level(Level.BLOCK).category(category).detail(detail).build();
    }
}
