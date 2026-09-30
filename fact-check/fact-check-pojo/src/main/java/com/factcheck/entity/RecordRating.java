package com.factcheck.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结果评分实体，对应 t_record_rating 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordRating implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private Long recordId;
    private Integer rating;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

