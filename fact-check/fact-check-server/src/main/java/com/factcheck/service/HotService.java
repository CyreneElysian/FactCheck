package com.factcheck.service;

import java.util.List;
import com.factcheck.vo.DetectRecordVO;
import com.factcheck.vo.HotClaimVO;

/**
 * 热门消息服务接口
 */
public interface HotService {

    /**
     * 查询热门消息 Top10
     */
    List<HotClaimVO> top10();

    /**
     * 公开查看热度榜中的某条记录详情（P3-34）
     *
     * <p>背景：`GET /detect/record/{id}` 只允许记录**归属者本人**查看（防越权），
     * 而产品文档 §4.4 要求热度榜可点击进详情——两者直接冲突。</p>
     *
     * <p>解法：新增本接口作为**受限的公开视图**，只有"当前确实在热度榜 Top10 里"的记录可被公开查看。
     * 这样既满足"热度榜可点击"，又不会把 `/detect/record/{id}` 敞开成可枚举的 IDOR 漏洞
     * （否则任何人换个 id 就能读到别人的消息原文、推理链与证据）。</p>
     *
     * @param recordId 记录 ID
     * @return 脱敏后的公开视图（不含指派人、诊断截止时间、我的收藏/评分等私有字段）
     * @throws com.factcheck.exception.RecordNotFoundException 记录不存在或不在热度榜中
     */
    DetectRecordVO publicDetail(Long recordId);
}
