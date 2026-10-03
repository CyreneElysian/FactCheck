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

    /**
     * 当前用户是否已收藏该记录（仅详情接口填充，列表接口为 null）
     *
     * <p>P3-30 / P3-37：前端原先没有这个字段可依据，只能"按钮永远显示收藏"，
     * 既无法回显已收藏态，也无法正确切换为"取消收藏"。</p>
     */
    private Boolean favorited;

    /**
     * 当前用户对该记录的评分（1 有帮助 / 0 无帮助；未评过为 null）
     *
     * <p>P3-37：前端原先评分为 null 初值，不回显历史评分，无法表现"可修改"。</p>
     */
    private Integer myRating;
}

