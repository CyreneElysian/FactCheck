package com.factcheck.controller.user;

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
 * 事实检测控制器（用户端）
 */
@RestController
@RequestMapping("/detect")
@Slf4j
public class DetectController {

    @Autowired
    private DetectService detectService;

    /**
     * 用户提交检测
     */
    @PostMapping("/submit")
    public Result<Long> submit(@RequestBody DetectSubmitDTO detectSubmitDTO) {
        log.info("用户提交检测：{}", detectSubmitDTO);
        return Result.success(detectService.submit(detectSubmitDTO));
    }
    /**
     * 查询检测记录详情
     */
    @GetMapping("/record/{id}")
    public Result<DetectRecordVO> record(@PathVariable Long id) {
        log.info("查询检测记录详情：{}", id);
        return Result.success(detectService.getRecord(id));
    }
    /**
     * 分页查询我的检测记录
     */
    @GetMapping("/records")
    public Result<PageResult> records(DetectRecordPageQueryDTO pageQueryDTO) {
        log.info("分页查询我的检测记录：{}", pageQueryDTO);
        return Result.success(detectService.pageRecords(pageQueryDTO));
    }

    /**
     * 查询检测记录的结论修订历史
     */
    @GetMapping("/record/{id}/revisions")
    public Result<List<ConclusionRevisionVO>> revisions(@PathVariable Long id) {
        log.info("查询结论修订历史：recordId={}", id);
        return Result.success(detectService.revisionHistory(id));
    }
}
