package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.MessagePageQueryDTO;
import com.factcheck.entity.Message;
import com.factcheck.exception.UserNotLoginException;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.result.PageResult;
import com.factcheck.service.MessageService;
import com.factcheck.util.ValidationUtils;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内信服务实现
 */
@Service
@Slf4j
public class MessageServiceImpl implements MessageService {

    @Autowired
    private MessageMapper messageMapper;

    /**
     * 分页查询我的站内信
     */
    @Override
    public PageResult page(MessagePageQueryDTO pageQueryDTO) {
        Long userId = BaseContext.getCurrentId();
        log.info("分页查询站内信：userId={}, isRead={}", userId, pageQueryDTO.getIsRead());
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        // 参数校验：未传取默认值，传了非法值（page=0 / pageSize 过大）必须拒绝
        ValidationUtils.Paging paging = ValidationUtils.requirePagingOrDefault(
                pageQueryDTO.getPage(), pageQueryDTO.getPageSize());
        PageHelper.startPage(paging.getPage(), paging.getPageSize());
        List<Message> list = messageMapper.pageByUser(userId, pageQueryDTO.getIsRead());
        Page<Message> pageInfo = (Page<Message>) list;
        return new PageResult(pageInfo.getTotal(), list);
    }

    /**
     * 标记单条已读
     */
    @Override
    public void read(Long id) {
        Long userId = BaseContext.getCurrentId();
        log.info("标记站内信已读：userId={}, messageId={}", userId, id);
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        messageMapper.read(id, userId);
    }

    /**
     * 全部已读
     */
    @Override
    public void readAll() {
        Long userId = BaseContext.getCurrentId();
        log.info("站内信全部已读：userId={}", userId);
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        messageMapper.readAll(userId);
    }

    /**
     * 查询当前用户未读消息数
     */
    @Override
    public Integer countUnread() {
        Long userId = BaseContext.getCurrentId();
        log.info("查询未读消息数：userId={}", userId);
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        return messageMapper.countUnread(userId);
    }
}
