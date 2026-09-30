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
 * 权威信息控制器（用户端）
 */
@RestController
@RequestMapping("/authority")
@Slf4j
public class AuthorityController {

    @Autowired
    private AuthorityService authorityService;

    /**
     * 分页查询权威信息
     */
    @GetMapping("/list")
    public Result<PageResult> list(AuthorityPageQueryDTO authorityPageQueryDTO) {
        log.info("分页查询权威信息：{}", authorityPageQueryDTO);
        return Result.success(authorityService.page(authorityPageQueryDTO));
    }
    /**
     * 查询权威信息详情
     */
    @GetMapping("/{id}")
    public Result<AuthorityInfoVO> detail(@PathVariable Long id) {
        log.info("查询权威信息详情：{}", id);
        return Result.success(authorityService.detail(id));
    }
}
