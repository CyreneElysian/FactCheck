package com.factcheck.vo;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 证据视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private String url;
    private String snippet;
    private String source;
    private java.math.BigDecimal sourceCredibility;
    private java.math.BigDecimal relevanceScore;
    private Integer sourceType;
}

