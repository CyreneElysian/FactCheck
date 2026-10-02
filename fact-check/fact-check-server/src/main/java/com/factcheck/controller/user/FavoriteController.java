package com.factcheck.controller.user;

import com.factcheck.dto.*;
import com.factcheck.result.PageResult;
import com.factcheck.result.Result;
import com.factcheck.service.*;
import com.factcheck.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 收藏控制器（用户端）
 */
@RestController
@RequestMapping("/favorite")
@Slf4j
public class FavoriteController {

    @Autowired
    private FavoriteService favoriteService;

    /**
     * 收藏检测结果
     */
    @PostMapping("/{recordId}")
    public Result add(@PathVariable Long recordId) {
        log.info("收藏检测结果：{}", recordId);
        favoriteService.add(recordId);
        return Result.success();
    }
    /**
     * 取消收藏
     */
    @DeleteMapping("/{recordId}")
    public Result remove(@PathVariable Long recordId) {
        log.info("取消收藏：{}", recordId);
        favoriteService.remove(recordId);
        return Result.success();
    }
    /**
     * 分页查询我的收藏
     */
    @GetMapping("/my")
    public Result<PageResult> my(@RequestParam(defaultValue = "1") Integer page,
                                 @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("分页查询我的收藏：{},{}", page, pageSize);
        return Result.success(favoriteService.pageMy(page, pageSize));
    }
}
