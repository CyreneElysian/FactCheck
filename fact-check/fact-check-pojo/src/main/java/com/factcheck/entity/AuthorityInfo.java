package com.factcheck.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 权威信息实体，对应 t_authority_info 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorityInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private String content;
    private String category;
    private String source;
    private String sourceUrl;
    private String coverImage;
    private Integer status;
    private LocalDateTime publishTime;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

