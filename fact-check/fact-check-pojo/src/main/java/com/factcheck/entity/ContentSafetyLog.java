package com.factcheck.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容安全审核日志实体，对应 t_content_safety_log 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentSafetyLog implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String contentHash;
    private Integer sourceType;
    private String checkType;
    private Integer result;
    private String reason;
    private LocalDateTime createTime;
}

