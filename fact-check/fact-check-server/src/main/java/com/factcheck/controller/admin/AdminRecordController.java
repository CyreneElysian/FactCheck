package com.factcheck.controller.admin;

import com.factcheck.dto.*;
import com.factcheck.result.PageResult;
import com.factcheck.result.Result;
import com.factcheck.service.*;
import com.factcheck.vo.*;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 结论管理控制器（管理端）
 */
@RestController
@RequestMapping("/admin/records")
@Slf4j
public class AdminRecordController {

    @Autowired
    private AdminRecordService adminRecordService;

    /**
     * 分页查询全部检测记录
     */
    @GetMapping("")
    public Result<PageResult> page(AdminRecordPageQueryDTO pageQueryDTO) {
        log.info("分页查询全部检测记录：{}", pageQueryDTO);
        return Result.success(adminRecordService.page(pageQueryDTO));
    }
    /**
     * 查询记录详情
     */
    @GetMapping("/{id}")
    public Result<DetectRecordVO> detail(@PathVariable Long id) {
        log.info("查询记录详情：{}", id);
        return Result.success(adminRecordService.detail(id));
    }

    /**
     * 查询指定记录的结论修订历史
     */
    @GetMapping("/{id}/revisions")
    public Result<List<ConclusionRevisionVO>> revisions(@PathVariable Long id) {
        log.info("管理员查询结论修订历史：recordId={}", id);
        return Result.success(adminRecordService.revisionHistory(id));
    }
    /**
     * 人工诊断提交结论
     */
    @PostMapping("/{id}/conclusion")
    public Result submitConclusion(@PathVariable Long id, @RequestBody AdminConclusionDTO adminConclusionDTO) {
        log.info("人工诊断提交结论：{},{}", id, adminConclusionDTO);
        adminRecordService.submitConclusion(id, adminConclusionDTO);
        return Result.success();
    }
    /**
     * 修改历史结论
     */
    @PutMapping("/{id}/conclusion")
    public Result updateConclusion(@PathVariable Long id, @RequestBody AdminConclusionDTO adminConclusionDTO) {
        log.info("修改历史结论：{},{}", id, adminConclusionDTO);
        adminRecordService.updateConclusion(id, adminConclusionDTO);
        return Result.success();
    }

    /**
     * 管理员补充证据（P3-35：AI 没找到证据或证据不足时人工补一条来源）
     */
    @PostMapping("/{id}/evidence")
    public Result addEvidence(@PathVariable Long id, @RequestBody EvidenceCreateDTO evidenceCreateDTO) {
        log.info("管理员补充证据：recordId={}, title={}", id, evidenceCreateDTO.getTitle());
        adminRecordService.addEvidence(id, evidenceCreateDTO);
        return Result.success();
    }

    /**
     * 删除证据（P3-35：删除不相关/错误来源）
     */
    @DeleteMapping("/{id}/evidence/{evidenceId}")
    public Result removeEvidence(@PathVariable Long id, @PathVariable Long evidenceId) {
        log.info("管理员删除证据：recordId={}, evidenceId={}", id, evidenceId);
        adminRecordService.removeEvidence(id, evidenceId);
        return Result.success();
    }

    /**
     * 强制重新检测（P5-70）
     *
     * <p>检测结果会被去重缓存：同一内容再次提交直接返回旧记录，不再调 Agent。
     * 这在正常情况下是特性，但会让"上游缺陷修复后的旧结论/旧证据"永远无法刷新。
     * 本接口提供显式重跑入口，与"失败自动重试"共用同一套状态流转。</p>
     */
    @PostMapping("/{id}/redetect")
    public Result redetect(@PathVariable Long id) {
        log.info("管理员强制重新检测：recordId={}", id);
        adminRecordService.redetect(id);
        return Result.success();
    }
}
