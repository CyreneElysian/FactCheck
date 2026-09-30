package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 站内信分页查询入参
 */
@Data
public class MessagePageQueryDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer isRead;
    private Integer page;
    private Integer pageSize;
}

