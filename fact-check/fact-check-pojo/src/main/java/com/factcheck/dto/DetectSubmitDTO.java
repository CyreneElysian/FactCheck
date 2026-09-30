package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 提交检测入参
 */
@Data
public class DetectSubmitDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String content;
    private Integer sourceType;
    private String imageUrl;
    private String category;
}

