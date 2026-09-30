package com.factcheck.service;

import com.factcheck.dto.RatingSubmitDTO;

/**
 * 检测结果评分服务接口
 */
public interface RatingService {

    /**
     * 提交或修改评分
     */
    void submit(RatingSubmitDTO ratingSubmitDTO);
}
