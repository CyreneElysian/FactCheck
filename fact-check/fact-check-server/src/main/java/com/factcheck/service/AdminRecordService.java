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

    /**
     * 管理员补充证据（P3-35）
     *
     * <p>以 {@code source_type=2}（管理员补充）写入，与 AI 检索结果区分；
     * 会校验标题必填、链接必须是 http(s)，并写操作日志。</p>
     */
    void addEvidence(Long recordId, com.factcheck.dto.EvidenceCreateDTO evidenceCreateDTO);

    /**
     * 删除证据（P3-35）
     *
     * <p>删除前校验该证据确实属于路径上的 recordId，避免"用 A 记录的权限删掉 B 记录的证据"。</p>
     */
    void removeEvidence(Long recordId, Long evidenceId);
}
