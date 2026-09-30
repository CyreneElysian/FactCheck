package com.factcheck.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性，对应 application.yml 中 fact-check.jwt
 */
@Component
@ConfigurationProperties(prefix = "fact-check.jwt")
@Data
public class JwtProperties {
    private String userSecretKey;
    private long userTtl;
    private String userTokenName;
    private String adminSecretKey;
    private long adminTtl;
    private String adminTokenName;
}
