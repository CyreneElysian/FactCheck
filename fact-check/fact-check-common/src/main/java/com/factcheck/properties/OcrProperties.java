package com.factcheck.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OCR 配置属性，对应 application.yml 中 fact-check.ocr
 */
@Component
@ConfigurationProperties(prefix = "fact-check.ocr")
@Data
public class OcrProperties {
    /** OCR 接口地址（阿里云市场通用文字识别） */
    private String endpoint;
    /** 阿里云市场 AppCode */
    private String appCode;
}
