package com.factcheck.vo;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 申诉视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppealVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long recordId;
    private String recordContent;
    private Integer reasonType;
    private String content;
    private Integer status;
    private String handleResult;
    private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime handleTime;
}

