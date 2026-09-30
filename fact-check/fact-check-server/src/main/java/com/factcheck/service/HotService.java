package com.factcheck.service;

import java.util.List;
import com.factcheck.vo.HotClaimVO;

/**
 * 热门消息服务接口
 */
public interface HotService {

    /**
     * 查询热门消息 Top10
     */
    List<HotClaimVO> top10();
}
