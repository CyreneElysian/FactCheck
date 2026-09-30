package com.factcheck.vo;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 热门消息视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotClaimVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long recordId;
    private String content;
    private String category;
    private Integer verdict;
    private Double hotScore;
    private Integer hitCount;
}

