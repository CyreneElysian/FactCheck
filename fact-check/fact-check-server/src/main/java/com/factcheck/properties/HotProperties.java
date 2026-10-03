package com.factcheck.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 热度相关配置，对应 application.yml 中的 fact-check.hot
 */
@Component
@ConfigurationProperties(prefix = "fact-check.hot")
@Data
public class HotProperties {

    /**
     * "查看详情"计热度的去重窗口（分钟）
     *
     * <p>同一用户对同一记录在该窗口内只计一次。若不设窗口，用户反复刷新详情页就能
     * 把某条消息刷上热度榜（前端在等待异步检测结果时本来也会轮询详情接口）。</p>
     */
    private int viewDedupMinutes = 30;
}
