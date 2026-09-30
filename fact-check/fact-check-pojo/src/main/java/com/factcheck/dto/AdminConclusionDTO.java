package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 管理端提交/修改结论入参
 */
@Data
public class AdminConclusionDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer verdict;
    private String reasoningChain;
    private String reason;
}

