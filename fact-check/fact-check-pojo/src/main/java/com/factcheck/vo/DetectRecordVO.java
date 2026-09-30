package com.factcheck.vo;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检测记录详情视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectRecordVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String content;
    private String category;
    private Integer sourceType;
    private String imageUrl;
    private Integer status;
    private Integer verdict;
    private Integer verdictSource;
    private java.math.BigDecimal confidence;
    private String reasoningChain;
    private String aiSummary;
    private Integer version;
    private Integer hitCount;
    private Long assigneeId;
    private java.time.LocalDateTime deadline;
    private Long authorityId;
    private AuthorityInfoVO authorityInfo;
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime completeTime;
    private java.util.List evidenceList;
}

