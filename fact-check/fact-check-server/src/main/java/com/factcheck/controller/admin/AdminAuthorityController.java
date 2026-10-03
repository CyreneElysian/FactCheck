package com.factcheck.controller.admin;

import com.factcheck.dto.*;
import com.factcheck.result.PageResult;
import com.factcheck.result.Result;
import com.factcheck.service.*;
import com.factcheck.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 权威信息管理控制器（管理端）
 */
@RestController
@RequestMapping("/admin/authority")
@Slf4j
public class AdminAuthorityController {

    @Autowired
    private AdminAuthorityService adminAuthorityService;

    /**
     * 分页查询权威信息
     */
    @GetMapping("/list")
    public Result<PageResult> list(@RequestParam(defaultValue = "1") Integer page,
                                   @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("分页查询权威信息：{},{}", page, pageSize);
        return Result.success(adminAuthorityService.page(page, pageSize));
    }
    /**
     * 新增权威信息
     */
    @PostMapping("")
    public Result save(@RequestBody AdminAuthorityDTO adminAuthorityDTO) {
        log.info("新增权威信息：{}", adminAuthorityDTO);
        adminAuthorityService.save(adminAuthorityDTO);
        return Result.success();
    }
    /**
     * 编辑权威信息
     *
     * <p>P5-54：以路径上的 {@code id} 为准并写回 DTO，避免"路径一个 id、请求体另一个 id"
     * 时更新的其实是请求体里那条（脆弱且容易被前端漏传 id 影响）。</p>
     */
    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @RequestBody AdminAuthorityDTO adminAuthorityDTO) {
        log.info("编辑权威信息：{},{}", id, adminAuthorityDTO);
        adminAuthorityDTO.setId(id);
        adminAuthorityService.update(adminAuthorityDTO);
        return Result.success();
    }
    /**
     * 上/下线权威信息
     */
    @PutMapping("/{id}/status")
    public Result updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        log.info("上/下线权威信息：{},{}", id, status);
        adminAuthorityService.updateStatus(id, status);
        return Result.success();
    }
    /**
     * 删除权威信息
     */
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        log.info("删除权威信息：{}", id);
        adminAuthorityService.delete(id);
        return Result.success();
    }
}
