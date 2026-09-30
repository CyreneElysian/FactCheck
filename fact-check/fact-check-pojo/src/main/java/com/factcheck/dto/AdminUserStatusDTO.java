package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 管理端修改用户状态入参
 */
@Data
public class AdminUserStatusDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer status;
}

