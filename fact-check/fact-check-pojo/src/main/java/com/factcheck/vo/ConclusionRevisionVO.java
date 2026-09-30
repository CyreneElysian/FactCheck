package com.factcheck.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结论修订历史视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConclusionRevisionVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long recordId;
    private Integer oldVerdict;
    private Integer newVerdict;
    private String oldReasoningChain;
    private String newReasoningChain;
    private String reason;
    private Integer source;
    private Long appealId;
    private Long adminId;
    private LocalDateTime createTime;
}
