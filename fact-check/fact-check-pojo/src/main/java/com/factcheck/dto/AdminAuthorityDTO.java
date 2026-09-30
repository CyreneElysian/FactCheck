package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 管理端新增/编辑权威信息入参
 */
@Data
public class AdminAuthorityDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private String content;
    private String category;
    private String source;
    private String sourceUrl;
    private String coverImage;
    private Integer status;
}

