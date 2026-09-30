package com.factcheck.service;

import com.factcheck.dto.AppealPageQueryDTO;
import com.factcheck.dto.AppealSubmitDTO;
import com.factcheck.result.PageResult;

/**
 * 申诉/纠错服务接口
 */
public interface AppealService {

    /**
     * 发起申诉
     */
    void submit(AppealSubmitDTO appealSubmitDTO);

    /**
     * 分页查询我的申诉
     */
    PageResult pageMy(AppealPageQueryDTO pageQueryDTO);
}
