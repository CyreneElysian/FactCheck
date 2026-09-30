package com.factcheck.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检测请求日志实体，对应 t_detect_log 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectLog implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String content;
    private String contentHash;
    private String category;
    private Integer sourceType;
    private Integer dedupHit;
    private Long hitRecordId;
    private Long recordId;
    private String ip;
    private String userAgent;
    private LocalDateTime createTime;
}

