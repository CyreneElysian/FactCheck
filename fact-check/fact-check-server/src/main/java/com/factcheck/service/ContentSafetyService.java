package com.factcheck.service;

/**
 * 内容安全审核服务接口，默认使用阿里云内容安全
 */
public interface ContentSafetyService {

    /**
     * 文字内容安全审核
     * @param text 待审核文字
     */
    void checkText(String text);

    /**
     * 图片内容安全审核
     * @param imageUrl 图片 URL
     */
    void checkImage(String imageUrl);
}
