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
 * 站内信控制器（用户端）
 */
@RestController
@RequestMapping("/message")
@Slf4j
public class MessageController {

    @Autowired
    private MessageService messageService;

    /**
     * 分页查询站内信
     */
    @GetMapping("/list")
    public Result<PageResult> list(MessagePageQueryDTO messagePageQueryDTO) {
        log.info("分页查询站内信：{}", messagePageQueryDTO);
        return Result.success(messageService.page(messagePageQueryDTO));
    }
    /**
     * 标记单条已读
     */
    @PutMapping("/{id}/read")
    public Result read(@PathVariable Long id) {
        log.info("标记单条已读：{}", id);
        messageService.read(id);
        return Result.success();
    }
    /**
     * 全部已读
     */
    @PutMapping("/read-all")
    public Result readAll() {
        log.info("全部已读");
        messageService.readAll();
        return Result.success();
    }

    /**
     * 查询当前用户未读消息数
     */
    @GetMapping("/unread-count")
    public Result<Integer> unreadCount() {
        log.info("查询未读消息数");
        return Result.success(messageService.countUnread());
    }
}
