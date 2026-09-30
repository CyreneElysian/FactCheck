package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 管理端检测记录分页查询入参
 */
@Data
public class AdminRecordPageQueryDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String category;
    private Integer status;
    private Integer verdict;
    private String keyword;
    private Integer page;
    private Integer pageSize;
}

