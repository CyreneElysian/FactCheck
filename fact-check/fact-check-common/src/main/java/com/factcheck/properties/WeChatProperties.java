package com.factcheck.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序配置属性，对应 application.yml 中 fact-check.wechat
 */
@Component
@ConfigurationProperties(prefix = "fact-check.wechat")
@Data
public class WeChatProperties {
    private String appid;
    private String secret;
}
