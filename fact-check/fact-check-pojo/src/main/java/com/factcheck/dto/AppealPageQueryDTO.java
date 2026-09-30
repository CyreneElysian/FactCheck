package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 申诉分页查询入参
 */
@Data
public class AppealPageQueryDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer status;
    private Integer page;
    private Integer pageSize;
}

