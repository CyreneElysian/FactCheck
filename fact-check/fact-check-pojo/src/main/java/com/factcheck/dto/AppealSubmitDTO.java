package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 发起申诉入参
 */
@Data
public class AppealSubmitDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long recordId;
    private Integer reasonType;
    private String content;
}

