package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.UserFavorite;
import com.factcheck.exception.BaseException;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.exception.UserNotLoginException;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.UserFavoriteMapper;
import com.factcheck.result.PageResult;
import com.factcheck.service.FavoriteService;
import com.factcheck.util.ValidationUtils;
import com.factcheck.vo.FavoriteVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 收藏服务实现
 */
@Service
@Slf4j
public class FavoriteServiceImpl implements FavoriteService {

    @Autowired
    private UserFavoriteMapper userFavoriteMapper;
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;

    /**
     * 收藏检测结果
     */
    @Override
    public void add(Long recordId) {
        Long userId = BaseContext.getCurrentId();
        log.info("用户收藏检测结果：userId={}, recordId={}", userId, recordId);
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        if (recordId == null) {
            throw new BaseException("请指定要收藏的记录");
        }
        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        if (userFavoriteMapper.countByUserAndRecord(userId, recordId) > 0) {
            log.info("记录已收藏，忽略重复操作：userId={}, recordId={}", userId, recordId);
            return;
        }
        userFavoriteMapper.insert(UserFavorite.builder()
                .userId(userId)
                .recordId(recordId)
                .createTime(LocalDateTime.now())
                .build());
        log.info("收藏成功：userId={}, recordId={}", userId, recordId);
    }

    /**
     * 取消收藏
     */
    @Override
    public void remove(Long recordId) {
        Long userId = BaseContext.getCurrentId();
        log.info("用户取消收藏：userId={}, recordId={}", userId, recordId);
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        userFavoriteMapper.deleteByUserAndRecord(userId, recordId);
    }

    /**
     * 分页查询我的收藏
     */
    @Override
    public PageResult pageMy(Integer page, Integer pageSize) {
        Long userId = BaseContext.getCurrentId();
        log.info("分页查询我的收藏：userId={}, page={}, pageSize={}", userId, page, pageSize);
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        // 参数校验（P2-17）：防止 page=0 触发"不分页"与超大 pageSize 全表加载
        ValidationUtils.requirePaging(page, pageSize);
        int pageNum = page == null ? 1 : page;
        int size = pageSize == null ? 10 : pageSize;
        PageHelper.startPage(pageNum, size);
        List<UserFavorite> list = userFavoriteMapper.pageByUser(userId);
        Page<UserFavorite> pageInfo = (Page<UserFavorite>) list;
        List<FavoriteVO> records = list.stream().map(f -> {
            FavoriteVO.FavoriteVOBuilder builder = FavoriteVO.builder()
                    .recordId(f.getRecordId())
                    .createTime(f.getCreateTime());
            FactCheckRecord record = factCheckRecordMapper.getById(f.getRecordId());
            if (record != null) {
                builder.content(record.getContent())
                        .category(record.getCategory())
                        .verdict(record.getVerdict());
            }
            return builder.build();
        }).collect(Collectors.toList());
        return new PageResult(pageInfo.getTotal(), records);
    }
}
