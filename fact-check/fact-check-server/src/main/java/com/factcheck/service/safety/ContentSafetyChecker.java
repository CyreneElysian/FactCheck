package com.factcheck.service.safety;

import com.factcheck.dto.ContentSafetyResult;

/**
 * 内容安全审核器（可插拔）
 *
 * <p>把"怎么审核"与"审核结果怎么处置"分开：</p>
 * <ul>
 *   <li>{@link com.factcheck.service.impl.ContentSafetyServiceImpl} 负责pick实现、写审核日志、执行处置策略；</li>
 *   <li>本接口的实现只负责"给定内容，返回判定结果"，不写库、不抛业务异常。</li>
 * </ul>
 *
 * <p>这样就能在不改业务代码的前提下替换实现：当前提供
 * {@link LocalRuleContentSafetyChecker}（本地确定性规则，离线可测、零成本）；
 * 若要接阿里云内容安全等云端审核，只需再实现本接口并在
 * {@code fact-check.content-safety.provider} 中切换。</p>
 */
public interface ContentSafetyChecker {

    /**
     * 实现名，需与 {@code fact-check.content-safety.provider} 的取值一致
     */
    String name();

    /**
     * 审核文本
     *
     * @param text 待审核文本（已去首尾空白）
     * @return 判定结果，不得为 null
     */
    ContentSafetyResult checkText(String text);

    /**
     * 审核图片
     *
     * @param imageUrl 图片地址
     * @return 判定结果，不得为 null
     */
    ContentSafetyResult checkImage(String imageUrl);
}
