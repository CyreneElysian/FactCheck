package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 检测结果评分入参
 */
@Data
public class RatingSubmitDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long recordId;
    private Integer rating;
}

