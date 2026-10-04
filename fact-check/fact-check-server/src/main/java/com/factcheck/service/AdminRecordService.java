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

    /**
     * 强制重新检测（管理端）
     *
     * <h3>为什么必须有这个入口</h3>
     * 检测结果会被精确/语义去重**缓存**：同一内容再次提交时直接返回旧记录，不会再调 Agent。
     * 这在正常情况是特性（省额度、结果稳定），但有两种情况会变成问题：
     * <ol>
     *   <li><b>上游缺陷修复后，旧结论与旧证据永远无法刷新</b>。实测遇到：证据里的
     *       URL/标题因编码与长度缺陷损坏，修好代码后重提同一句只会返回那份脏数据
     *       （{@code hit_count} 累加、证据一条都不更新）；</li>
     *   <li>结论本身存疑，需要重跑一次 AI 判断。</li>
     * </ol>
     * 人工改结论（{@code updateConclusion}）**不能**替代它：那只改结论，不会重跑检索、
     * 也不会刷新证据。
     *
     * <p>实现上复用与"失败重试"完全相同的路径（重置为检测中 → 异步重跑），
     * 因此不会产生第二套状态流转。</p>
     *
     * @throws com.factcheck.exception.BaseException 记录不存在时
     */
    void redetect(Long recordId);
}
