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
 * 热门消息控制器（用户端）
 */
@RestController
@RequestMapping("/hot")
@Slf4j
public class HotController {

    @Autowired
    private HotService hotService;

    /**
     * 查询热门消息Top10
     */
    @GetMapping("/top10")
    public Result<List<HotClaimVO>> top10() {
        log.info("查询热门消息Top10");
        return Result.success(hotService.top10());
    }

    /**
     * 公开查看热度榜中的记录详情（P3-34：热度榜可点击进详情）
     *
     * <p>只有当前在 Top10 内的记录可查看，且返回的是脱敏公开视图——
     * 细节见 {@link HotService#publicDetail(Long)} 的说明。</p>
     */
    @GetMapping("/{recordId}")
    public Result<DetectRecordVO> detail(@PathVariable Long recordId) {
        log.info("公开查看热度榜记录详情：recordId={}", recordId);
        return Result.success(hotService.publicDetail(recordId));
    }
}
