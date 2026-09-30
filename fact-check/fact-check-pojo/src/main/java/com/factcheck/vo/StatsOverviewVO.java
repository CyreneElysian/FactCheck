package com.factcheck.vo;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作台统计视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsOverviewVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long todayDetectCount;
    private Long todayAiCount;
    private Long todayManualCount;
    private Long pendingCount;
    private Long pendingAppealCount;
    private Long totalUserCount;
}

