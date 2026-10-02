package com.factcheck.service;

import com.factcheck.dto.ContentSafetyResult;

/**
 * 内容安全审核服务：在"提交检测"等入口处对内容做审核
 *
 * <p>由 {@code fact-check.content-safety.provider} 决定具体使用哪个
 * {@link com.factcheck.service.safety.ContentSafetyChecker} 实现（默认 local：本地确定性规则）。
 * 判定为 BLOCK 时本服务抛出业务异常（文案见
 * {@link com.factcheck.constant.MessageConstant#CONTENT_ILLEGAL}），
 * 并把每一次审核写入 {@code t_content_safety_log}。</p>
 */
public interface ContentSafetyService {

    /**
     * 文字内容安全审核
     *
     * @param text 待审核文本
     * @return 审核结果（PASS / REVIEW / BLOCK）
     * @throws com.factcheck.exception.BaseException 判定为 BLOCK 时抛出
     */
    ContentSafetyResult checkText(String text);

    /**
     * 图片内容安全审核
     *
     * @param imageUrl 图片 URL
     * @return 审核结果（PASS / REVIEW / BLOCK）
     * @throws com.factcheck.exception.BaseException 判定为 BLOCK 时抛出
     */
    ContentSafetyResult checkImage(String imageUrl);
}
