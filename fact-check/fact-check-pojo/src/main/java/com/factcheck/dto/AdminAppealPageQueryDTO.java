package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 管理端申诉分页查询入参
 */
@Data
public class AdminAppealPageQueryDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer status;
    private Integer page;
    private Integer pageSize;
}

