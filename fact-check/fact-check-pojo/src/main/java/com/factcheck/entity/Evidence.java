package com.factcheck.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 证据实体，对应 t_evidence 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Evidence implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long recordId;
    private String title;
    private String url;
    private String snippet;
    private String source;
    private BigDecimal sourceCredibility;
    private BigDecimal relevanceScore;
    private Integer sourceType;
    private LocalDateTime createTime;
}

