package com.factcheck.vo;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 权威信息视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorityInfoVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private String content;
    private String category;
    private String source;
    private String sourceUrl;
    private String coverImage;
    private java.time.LocalDateTime publishTime;
}

