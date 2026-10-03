package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.constant.RedisKeyConstant;
import com.factcheck.dto.HotEntry;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.service.DetectService;
import com.factcheck.service.HotRankService;
import com.factcheck.service.HotService;
import com.factcheck.vo.DetectRecordVO;
import com.factcheck.vo.HotClaimVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 热门消息服务实现
 */
@Service
@Slf4j
public class HotServiceImpl implements HotService {

    /**
     * 热度榜展示条数
     */
    private static final int TOP_LIMIT = 10;

    @Autowired
    private HotRankService hotRankService;
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private DetectService detectService;

    /**
     * 查询热门消息 Top10
     * 从 Redis ZSET 读取热度最高的 10 条，再回查 MySQL 补充内容与结论
     *
     * <p>P2-14 之后 ZSET 的读取统一走 {@link HotRankService}（原先这里自己解析一次，
     * 管理端又是另一套 SQL 排序），异常与降级行为因此只在一处维护。</p>
     */
    @Override
    public List<HotClaimVO> top10() {
        log.info("查询热门消息Top10：key={}", RedisKeyConstant.HOT_CLAIM_ZSET);
        List<HotClaimVO> result = new ArrayList<>();
        List<HotEntry> entries = hotRankService.top(TOP_LIMIT);
        if (entries.isEmpty()) {
            log.info("热度榜为空（或 Redis 不可用），返回空列表");
            return result;
        }

        for (HotEntry entry : entries) {
            Long recordId = entry.getRecordId();
            FactCheckRecord record = factCheckRecordMapper.getById(recordId);
            if (record == null) {
                log.warn("热度榜中的记录不存在，跳过：recordId={}", recordId);
                continue;
            }
            // 用户端热度榜只展示已完成且有结论的消息
            if (record.getStatus() == null || record.getStatus() != 2 || record.getVerdict() == null) {
                log.info("热度榜跳过未完成或暂无结论的记录：recordId={}, status={}", recordId, record.getStatus());
                continue;
            }
            result.add(HotClaimVO.builder()
                    .recordId(record.getId())
                    .content(record.getContent())
                    .category(record.getCategory())
                    .verdict(record.getVerdict())
                    .hotScore(entry.getScore())
                    .hitCount(record.getHitCount())
                    .build());
        }
        log.info("热门消息Top10查询完成：返回 {} 条", result.size());
        return result;
    }

    /**
     * 公开查看热度榜中的记录详情（P3-34）
     *
     * <p><b>为什么需要"在榜"校验</b>：热度榜是公开的，但检测详情接口是"仅归属者可见"。
     * 若直接把详情接口放开，任何人都能靠遍历 id 读到别人的消息原文、推理链与证据（IDOR）；
     * 若不做这个接口，热度榜点了就 404。折中方案是**只公开当前确实在 Top10 里的记录**——
     * 这些内容本来就已经公开展示在榜上，公开范围没有实质扩大。</p>
     */
    @Override
    public DetectRecordVO publicDetail(Long recordId) {
        log.info("公开查看热度榜记录详情：recordId={}", recordId);
        if (recordId == null) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        boolean inTop = top10().stream()
                .anyMatch(item -> recordId.equals(item.getRecordId()));
        if (!inTop) {
            // 与"记录不存在"返回同一个错误，避免通过错误文案探测某条记录是否存在
            log.info("记录不在热度榜中，拒绝公开查看：recordId={}", recordId);
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        return detectService.buildPublicRecord(recordId);
    }
}
