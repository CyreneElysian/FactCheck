package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 管理端转派待诊断消息入参
 */
@Data
public class AdminAssignDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long assigneeId;
}

