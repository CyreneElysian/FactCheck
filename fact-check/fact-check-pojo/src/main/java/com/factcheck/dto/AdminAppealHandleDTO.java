package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 管理端处理申诉入参
 */
@Data
public class AdminAppealHandleDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Boolean accept;
    private String handleResult;
    private Integer newVerdict;
    private String newReasoningChain;
}

