package com.factcheck.service.impl;

import com.factcheck.constant.RedisKeyConstant;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.service.HotService;
import com.factcheck.vo.HotClaimVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 热门消息服务实现
 */
@Service
@Slf4j
public class HotServiceImpl implements HotService {

    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;

    /**
     * 查询热门消息 Top10
     * 从 Redis ZSET 读取热度最高的 10 条，再回查 MySQL 补充内容与结论
     */
    @Override
    public List<HotClaimVO> top10() {
        log.info("查询热门消息Top10：key={}", RedisKeyConstant.HOT_CLAIM_ZSET);
        List<HotClaimVO> result = new ArrayList<>();
        try {
            Set<ZSetOperations.TypedTuple> tuples = redisTemplate.opsForZSet()
                    .reverseRangeWithScores(RedisKeyConstant.HOT_CLAIM_ZSET, 0, 9);
            if (tuples == null || tuples.isEmpty()) {
                log.info("热度榜为空，返回空列表");
                return result;
            }

            for (ZSetOperations.TypedTuple tuple : tuples) {
                Object value = tuple.getValue();
                if (value == null) {
                    continue;
                }
                Long recordId = Long.valueOf(value.toString());
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
                HotClaimVO vo = HotClaimVO.builder()
                        .recordId(record.getId())
                        .content(record.getContent())
                        .category(record.getCategory())
                        .verdict(record.getVerdict())
                        .hotScore(tuple.getScore())
                        .hitCount(record.getHitCount())
                        .build();
                result.add(vo);
            }
            log.info("热门消息Top10查询完成：返回 {} 条", result.size());
        } catch (Exception e) {
            log.error("查询热门消息Top10失败", e);
        }
        return result;
    }
}
