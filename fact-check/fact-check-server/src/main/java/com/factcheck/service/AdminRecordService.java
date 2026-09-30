package com.factcheck.service;

import com.factcheck.dto.AdminConclusionDTO;
import com.factcheck.dto.AdminRecordPageQueryDTO;
import com.factcheck.result.PageResult;
import com.factcheck.vo.DetectRecordVO;

/**
 * 管理员结论管理服务接口
 */
public interface AdminRecordService {

    /**
     * 分页查询全部检测记录
     */
    PageResult page(AdminRecordPageQueryDTO pageQueryDTO);

    /**
     * 查询记录详情
     */
    DetectRecordVO detail(Long recordId);

    /**
     * 人工诊断：提交结论与推理依据
     */
    void submitConclusion(Long recordId, AdminConclusionDTO adminConclusionDTO);

    /**
     * 修改历史结论并附原因
     */
    void updateConclusion(Long recordId, AdminConclusionDTO adminConclusionDTO);

    /**
     * 查询指定记录的结论修订历史
     */
    java.util.List<com.factcheck.vo.ConclusionRevisionVO> revisionHistory(Long recordId);
}
