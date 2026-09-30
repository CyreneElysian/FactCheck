package com.factcheck.service;

import java.util.List;
import com.factcheck.vo.PendingClaimVO;
import com.factcheck.vo.DetectRecordVO;

/**
 * 管理员待诊断消息服务接口
 */
public interface AdminPendingService {

    /**
     * 查询待诊断热门消息 Top10
     */
    List<PendingClaimVO> top10();

    /**
     * 查询待诊断消息详情（含 AI 证据）
     */
    DetectRecordVO detail(Long recordId);

    /**
     * 转派给其他管理员
     */
    void assign(Long recordId, Long assigneeId);
}
