package com.factcheck.service;

import com.factcheck.dto.MessagePageQueryDTO;
import com.factcheck.result.PageResult;

/**
 * 站内信服务接口
 */
public interface MessageService {

    /**
     * 分页查询我的站内信
     */
    PageResult page(MessagePageQueryDTO pageQueryDTO);

    /**
     * 标记单条已读
     */
    void read(Long id);

    /**
     * 全部已读
     */
    void readAll();

    /**
     * 查询当前用户未读消息数
     */
    Integer countUnread();
}
