package com.factcheck.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 操作日志实体，对应 t_operation_log 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationLog implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long adminId;
    private String operation;
    private String method;
    private String params;
    private String ip;
    private LocalDateTime createTime;
}

