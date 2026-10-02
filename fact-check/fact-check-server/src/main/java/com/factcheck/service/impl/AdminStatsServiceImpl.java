package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.entity.OperationLog;
import com.factcheck.exception.BaseException;
import com.factcheck.mapper.AppealMapper;
import com.factcheck.mapper.DetectLogMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.mapper.UserMapper;
import com.factcheck.result.PageResult;
import com.factcheck.service.AdminStatsService;
import com.factcheck.util.ValidationUtils;
import com.factcheck.vo.StatsOverviewVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员统计与日志服务实现
 */
@Service
@Slf4j
public class AdminStatsServiceImpl implements AdminStatsService {

    @Autowired
    private DetectLogMapper detectLogMapper;
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private AppealMapper appealMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OperationLogMapper operationLogMapper;

    /**
     * 查询工作台统计概览
     */
    @Override
    public StatsOverviewVO overview() {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员查询统计概览：adminId={}", adminId);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }

        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = LocalDateTime.now();

        long todayDetectCount = detectLogMapper.countByCreateTimeBetween(start, end);
        long todayAiCount = factCheckRecordMapper.countByVerdictSourceAndTime(1, start, end);
        long todayManualCount = factCheckRecordMapper.countByVerdictSourceAndTime(2, start, end);
        long pendingCount = factCheckRecordMapper.countByStatus(1);
        long pendingAppealCount = appealMapper.countByStatus(0);
        long totalUserCount = userMapper.countByRole(0);

        StatsOverviewVO vo = StatsOverviewVO.builder()
                .todayDetectCount(todayDetectCount)
                .todayAiCount(todayAiCount)
                .todayManualCount(todayManualCount)
                .pendingCount(pendingCount)
                .pendingAppealCount(pendingAppealCount)
                .totalUserCount(totalUserCount)
                .build();
        log.info("统计概览查询完成：{}", vo);
        return vo;
    }

    /**
     * 分页查询操作日志
     */
    @Override
    public PageResult pageLogs(Integer page, Integer pageSize) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员分页查询操作日志：adminId={}, page={}, pageSize={}", adminId, page, pageSize);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        // 参数校验（P2-17）：防止 page=0 触发"不分页"与超大 pageSize 全表加载
        ValidationUtils.requirePaging(page, pageSize);
        int pageNum = page == null ? 1 : page;
        int size = pageSize == null ? 10 : pageSize;
        PageHelper.startPage(pageNum, size);
        List<OperationLog> list = operationLogMapper.page();
        Page<OperationLog> pageInfo = (Page<OperationLog>) list;
        return new PageResult(pageInfo.getTotal(), list);
    }
}
