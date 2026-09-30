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
     */
    DetectRecordVO getRecord(Long recordId);

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
