package com.factcheck.vo;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待诊断消息视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingClaimVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long recordId;
    private String content;
    private String category;
    private Integer requestCount;
    private java.time.LocalDateTime earliestTime;
    private java.time.LocalDateTime latestTime;
    private java.math.BigDecimal confidence;
    private Integer evidenceCount;
    private Boolean timeout;
}

