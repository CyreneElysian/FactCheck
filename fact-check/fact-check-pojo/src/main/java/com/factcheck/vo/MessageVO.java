package com.factcheck.vo;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 站内信视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Integer type;
    private String title;
    private String content;
    private Long relatedId;
    private Integer isRead;
    private java.time.LocalDateTime createTime;
}

