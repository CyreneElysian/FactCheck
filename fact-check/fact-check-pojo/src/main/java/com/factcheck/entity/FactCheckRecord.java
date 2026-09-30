package com.factcheck.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检测记录实体，对应 t_fact_check_record 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactCheckRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String content;
    private String contentHash;
    private String category;
    private Integer sourceType;
    private String imageUrl;
    private Integer status;
    private Integer verdict;
    private Integer verdictSource;
    private BigDecimal confidence;
    private String reasoningChain;
    private String aiSummary;
    private Integer version;
    private Integer hitCount;
    private Long assigneeId;
    private LocalDateTime deadline;
    private Long authorityId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime completeTime;
}

