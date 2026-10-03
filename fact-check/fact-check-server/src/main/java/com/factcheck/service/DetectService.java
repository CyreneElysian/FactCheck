package com.factcheck.service;

import com.factcheck.dto.DetectRecordPageQueryDTO;
import com.factcheck.dto.DetectSubmitDTO;
import com.factcheck.result.PageResult;
import com.factcheck.vo.DetectRecordVO;

/**
 * 事实检测服务接口
 */
public interface DetectService {

    /**
     * 提交文字/图片检测，返回记录 ID
     */
    Long submit(DetectSubmitDTO detectSubmitDTO);

    /**
     * 查询检测记录详情（前端轮询用）
     *
     * <p>只允许记录归属者本人查看：轮询时若传入他人的 recordId 会被判为不存在。</p>
     */
    DetectRecordVO getRecord(Long recordId);

    /**
     * 构造**公开视图**（不校验归属，但会清空私有字段）
     *
     * <p>只应由已经把过关的调用方使用（当前仅 {@link HotService#publicDetail(Long)}：
     * 先确认记录在热度榜 Top10 内，再取公开视图）。直接把它暴露成接口会造成越权读取。</p>
     */
    DetectRecordVO buildPublicRecord(Long recordId);

    /**
     * 分页查询当前用户的检测记录
     */
    PageResult pageRecords(DetectRecordPageQueryDTO pageQueryDTO);

    /**
     * 异步执行检测主流程（去重未命中后调用 Python Agent）
     */
    void asyncDetect(Long recordId, String content);

    /**
     * 查询检测记录的结论修订历史
     */
    java.util.List<com.factcheck.vo.ConclusionRevisionVO> revisionHistory(Long recordId);
}
