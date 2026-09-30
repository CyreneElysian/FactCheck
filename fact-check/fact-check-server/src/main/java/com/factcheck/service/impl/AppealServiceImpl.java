package com.factcheck.service.impl;

import com.factcheck.constant.HotScoreConstant;
import com.factcheck.constant.MessageConstant;
import com.factcheck.constant.RedisKeyConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.AppealPageQueryDTO;
import com.factcheck.dto.AppealSubmitDTO;
import com.factcheck.entity.Appeal;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.exception.BaseException;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.exception.UserNotLoginException;
import com.factcheck.mapper.AppealMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.result.PageResult;
import com.factcheck.service.AppealService;
import com.factcheck.vo.AppealVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 申诉服务实现（用户端）
 */
@Service
@Slf4j
public class AppealServiceImpl implements AppealService {

    @Autowired
    private AppealMapper appealMapper;
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 用户发起申诉
     */
    @Override
    public void submit(AppealSubmitDTO appealSubmitDTO) {
        Long userId = BaseContext.getCurrentId();
        log.info("用户发起申诉：userId={}, recordId={}, reasonType={}, content={}",
                userId, appealSubmitDTO.getRecordId(), appealSubmitDTO.getReasonType(), appealSubmitDTO.getContent());
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        if (appealSubmitDTO.getRecordId() == null) {
            throw new BaseException("请指定要申诉的检测记录");
        }
        if (appealSubmitDTO.getReasonType() == null || appealSubmitDTO.getReasonType() < 1
                || appealSubmitDTO.getReasonType() > 4) {
            throw new BaseException("申诉类型不正确");
        }
        if (!StringUtils.hasLength(appealSubmitDTO.getContent())) {
            throw new BaseException("请填写申诉说明");
        }

        FactCheckRecord record = factCheckRecordMapper.getById(appealSubmitDTO.getRecordId());
        if (record == null || !userId.equals(record.getUserId())) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        if (record.getStatus() == null || record.getStatus() != 2) {
            throw new BaseException("只有已完成的检测结果才能申诉");
        }

        Appeal exist = appealMapper.getByRecordIdAndUser(record.getId(), userId);
        if (exist != null && exist.getStatus() != null && exist.getStatus() == 0) {
            throw new BaseException("该记录已有待处理的申诉，请勿重复提交");
        }

        appealMapper.insert(Appeal.builder()
                .recordId(record.getId())
                .userId(userId)
                .reasonType(appealSubmitDTO.getReasonType())
                .content(appealSubmitDTO.getContent())
                .status(0)
                .createTime(LocalDateTime.now())
                .build());

        // 申诉热度 +3
        try {
            redisTemplate.opsForZSet().incrementScore(RedisKeyConstant.HOT_CLAIM_ZSET,
                    String.valueOf(record.getId()), HotScoreConstant.APPEAL_SCORE);
        } catch (Exception e) {
            log.warn("申诉热度更新失败：recordId={}, error={}", record.getId(), e.getMessage());
        }
        log.info("申诉提交成功：recordId={}, userId={}", record.getId(), userId);
    }

    /**
     * 分页查询我的申诉
     */
    @Override
    public PageResult pageMy(AppealPageQueryDTO pageQueryDTO) {
        Long userId = BaseContext.getCurrentId();
        log.info("分页查询我的申诉：userId={}, status={}", userId, pageQueryDTO.getStatus());
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        int pageNum = pageQueryDTO.getPage() == null ? 1 : pageQueryDTO.getPage();
        int pageSize = pageQueryDTO.getPageSize() == null ? 10 : pageQueryDTO.getPageSize();
        PageHelper.startPage(pageNum, pageSize);
        List<Appeal> list = appealMapper.pageByUser(userId, pageQueryDTO.getStatus());
        Page<Appeal> pageInfo = (Page<Appeal>) list;
        List<AppealVO> records = list.stream().map(this::toVO).collect(Collectors.toList());
        return new PageResult(pageInfo.getTotal(), records);
    }

    /**
     * 申诉实体转视图
     */
    private AppealVO toVO(Appeal appeal) {
        String recordContent = null;
        FactCheckRecord record = factCheckRecordMapper.getById(appeal.getRecordId());
        if (record != null) {
            recordContent = record.getContent();
        }
        return AppealVO.builder()
                .id(appeal.getId())
                .recordId(appeal.getRecordId())
                .recordContent(recordContent)
                .reasonType(appeal.getReasonType())
                .content(appeal.getContent())
                .status(appeal.getStatus())
                .handleResult(appeal.getHandleResult())
                .createTime(appeal.getCreateTime())
                .handleTime(appeal.getHandleTime())
                .build();
    }
}
