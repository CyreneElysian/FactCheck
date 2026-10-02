package com.factcheck.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容安全审核配置，对应 application.yml 中的 fact-check.content-safety
 */
@Component
@ConfigurationProperties(prefix = "fact-check.content-safety")
@Data
public class ContentSafetyProperties {

    /**
     * 是否启用内容安全审核（关闭后不写审核日志，也不做任何拦截）
     */
    private boolean enabled = true;

    /**
     * 审核实现：local（本地确定性规则）/ 其他值需要存在同名的 {@code ContentSafetyChecker} 实现
     */
    private String provider = "local";

    /**
     * 图片地址允许的域名白名单；为空表示不限制域名（仍会校验协议与主机名）
     */
    private List<String> imageHostWhitelist = new ArrayList<>();
}
