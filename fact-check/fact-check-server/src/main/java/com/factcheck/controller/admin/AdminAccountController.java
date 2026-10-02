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
 * 账号管理控制器（管理端）
 */
@RestController
@RequestMapping("/admin/account")
@Slf4j
public class AdminAccountController {

    @Autowired
    private AdminAccountService adminAccountService;

    /**
     * 创建管理员账号
     */
    @PostMapping("")
    public Result create(@RequestBody AdminAccountCreateDTO adminAccountCreateDTO) {
        log.info("创建管理员账号：{}", adminAccountCreateDTO);
        adminAccountService.create(adminAccountCreateDTO);
        return Result.success();
    }
    /**
     * 分页查询用户列表
     */
    @GetMapping("/users")
    public Result<PageResult> users(@RequestParam(defaultValue = "1") Integer page,
                                    @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("分页查询用户列表：{}", page, pageSize);
        return Result.success(adminAccountService.pageUsers(page, pageSize));
    }
    /**
     * 启用/禁用用户
     */
    @PutMapping("/user/{id}/status")
    public Result status(@PathVariable Long id, @RequestBody AdminUserStatusDTO adminUserStatusDTO) {
        log.info("启用/禁用用户：{}", id, adminUserStatusDTO);
        adminAccountService.updateUserStatus(id, adminUserStatusDTO.getStatus());
        return Result.success();
    }
}
