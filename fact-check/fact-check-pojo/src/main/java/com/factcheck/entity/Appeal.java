package com.factcheck.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 申诉实体，对应 t_appeal 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Appeal implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long recordId;
    private Long userId;
    private Integer reasonType;
    private String content;
    private Integer status;
    private String handleResult;
    private Long handledBy;
    private LocalDateTime handleTime;
    private LocalDateTime createTime;
}

