package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.RatingSubmitDTO;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.RecordRating;
import com.factcheck.exception.BaseException;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.exception.UserNotLoginException;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.RecordRatingMapper;
import com.factcheck.service.RatingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 结果评分服务实现
 */
@Service
@Slf4j
public class RatingServiceImpl implements RatingService {

    @Autowired
    private RecordRatingMapper recordRatingMapper;
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;

    /**
     * 提交或修改评分
     */
    @Override
    public void submit(RatingSubmitDTO ratingSubmitDTO) {
        Long userId = BaseContext.getCurrentId();
        log.info("用户提交结果评分：userId={}, recordId={}, rating={}",
                userId, ratingSubmitDTO.getRecordId(), ratingSubmitDTO.getRating());
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        if (ratingSubmitDTO.getRecordId() == null) {
            throw new BaseException("请指定要评分的记录");
        }
        if (ratingSubmitDTO.getRating() == null || (ratingSubmitDTO.getRating() != 0
                && ratingSubmitDTO.getRating() != 1)) {
            throw new BaseException("评分只能为：1有帮助、0无帮助");
        }
        FactCheckRecord record = factCheckRecordMapper.getById(ratingSubmitDTO.getRecordId());
        // 归属校验：只能给自己的记录评分。原先只判 record == null，
        // 于是任意登录用户都能给别人的记录刷"有帮助/无帮助"（评分是模型优化的反馈信号，
        // 被外部刷入会直接污染数据）。
        if (record == null || !userId.equals(record.getUserId())) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }

        RecordRating exist = recordRatingMapper.getByUserAndRecord(userId, ratingSubmitDTO.getRecordId());
        if (exist == null) {
            recordRatingMapper.insert(RecordRating.builder()
                    .userId(userId)
                    .recordId(ratingSubmitDTO.getRecordId())
                    .rating(ratingSubmitDTO.getRating())
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build());
        } else {
            recordRatingMapper.updateRating(exist.getId(), ratingSubmitDTO.getRating());
        }
        log.info("结果评分提交成功：userId={}, recordId={}, rating={}",
                userId, ratingSubmitDTO.getRecordId(), ratingSubmitDTO.getRating());
    }
}
