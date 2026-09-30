package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 权威信息分页查询入参
 */
@Data
public class AuthorityPageQueryDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String category;
    private String keyword;
    private Integer page;
    private Integer pageSize;
}

