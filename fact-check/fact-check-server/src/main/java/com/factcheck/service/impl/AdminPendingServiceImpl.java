package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.entity.AuthorityInfo;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.OperationLog;
import com.factcheck.entity.User;
import com.factcheck.exception.BaseException;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.mapper.AuthorityInfoMapper;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.mapper.UserMapper;
import com.factcheck.service.AdminPendingService;
import com.factcheck.vo.AuthorityInfoVO;
import com.factcheck.vo.DetectRecordVO;
import com.factcheck.vo.EvidenceVO;
import com.factcheck.vo.PendingClaimVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理员待诊断消息服务实现
 */
@Service
@Slf4j
public class AdminPendingServiceImpl implements AdminPendingService {

    private static final int PENDING_TOP_LIMIT = 10;

    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private AuthorityInfoMapper authorityInfoMapper;
    @Autowired
    private EvidenceMapper evidenceMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OperationLogMapper operationLogMapper;

    /**
     * 查询待诊断热门消息 Top10
     */
    @Override
    public List<PendingClaimVO> top10() {
        log.info("管理员查询待诊断热门消息Top10：status=1");
        List<FactCheckRecord> records = factCheckRecordMapper.listByStatus(1);
        List<PendingClaimVO> result = records.stream()
                .limit(PENDING_TOP_LIMIT)
                .map(this::toPendingVO)
                .collect(Collectors.toList());
        log.info("待诊断热门消息Top10查询完成：返回 {} 条", result.size());
        return result;
    }

    /**
     * 查询待诊断消息详情（含 AI 证据）
     */
    @Override
    public DetectRecordVO detail(Long recordId) {
        log.info("管理员查询待诊断消息详情：recordId={}", recordId);
        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        return toDetectVO(record);
    }

    /**
     * 转派待诊断消息给其他管理员
     */
    @Override
    public void assign(Long recordId, Long assigneeId) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员转派待诊断消息：recordId={}, assigneeId={}, operator={}", recordId, assigneeId, adminId);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }

        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        if (record.getStatus() == null || record.getStatus() != 1) {
            throw new BaseException("该记录不在待诊断状态，无法转派");
        }

        User assignee = userMapper.getById(assigneeId);
        if (assignee == null || assignee.getRole() == null || assignee.getRole() != 1
                || assignee.getStatus() == null || assignee.getStatus() != 1) {
            throw new BaseException("转派目标不是正常状态的管理员");
        }

        factCheckRecordMapper.update(FactCheckRecord.builder()
                .id(recordId)
                .assigneeId(assigneeId)
                .updateTime(LocalDateTime.now())
                .build());

        operationLogMapper.insert(OperationLog.builder()
                .adminId(adminId)
                .operation("转派待诊断消息")
                .method("AdminPendingServiceImpl.assign")
                .params("recordId=" + recordId + ", assigneeId=" + assigneeId)
                .createTime(LocalDateTime.now())
                .build());
        log.info("待诊断消息转派成功：recordId={}, assigneeId={}", recordId, assigneeId);
    }

    /**
     * 待诊断记录转视图
     */
    private PendingClaimVO toPendingVO(FactCheckRecord record) {
        boolean timeout = record.getDeadline() != null && record.getDeadline().isBefore(LocalDateTime.now());
        return PendingClaimVO.builder()
                .recordId(record.getId())
                .content(record.getContent())
                .category(record.getCategory())
                .requestCount(record.getHitCount())
                .earliestTime(record.getCreateTime())
                .latestTime(record.getUpdateTime())
                .confidence(record.getConfidence())
                .evidenceCount(evidenceMapper.countByRecordId(record.getId()))
                .timeout(timeout)
                .build();
    }

    /**
     * 检测记录转详情视图
     */
    private DetectRecordVO toDetectVO(FactCheckRecord record) {
        List<EvidenceVO> evidenceList = evidenceMapper.getByRecordId(record.getId()).stream()
                .map(e -> EvidenceVO.builder()
                        .id(e.getId())
                        .title(e.getTitle())
                        .url(e.getUrl())
                        .snippet(e.getSnippet())
                        .source(e.getSource())
                        .sourceCredibility(e.getSourceCredibility())
                        .relevanceScore(e.getRelevanceScore())
                        .sourceType(e.getSourceType())
                        .build())
                .collect(Collectors.toList());
        AuthorityInfoVO authorityInfo = null;
        if (record.getAuthorityId() != null) {
            AuthorityInfo info = authorityInfoMapper.getById(record.getAuthorityId());
            if (info != null) {
                authorityInfo = AuthorityInfoVO.builder()
                        .id(info.getId())
                        .title(info.getTitle())
                        .content(info.getContent())
                        .category(info.getCategory())
                        .source(info.getSource())
                        .sourceUrl(info.getSourceUrl())
                        .coverImage(info.getCoverImage())
                        .publishTime(info.getPublishTime())
                        .build();
            }
        }
        return DetectRecordVO.builder()
                .id(record.getId())
                .content(record.getContent())
                .category(record.getCategory())
                .sourceType(record.getSourceType())
                .imageUrl(record.getImageUrl())
                .status(record.getStatus())
                .verdict(record.getVerdict())
                .verdictSource(record.getVerdictSource())
                .confidence(record.getConfidence())
                .reasoningChain(record.getReasoningChain())
                .aiSummary(record.getAiSummary())
                .version(record.getVersion())
                .hitCount(record.getHitCount())
                .assigneeId(record.getAssigneeId())
                .deadline(record.getDeadline())
                .authorityId(record.getAuthorityId())
                .authorityInfo(authorityInfo)
                .createTime(record.getCreateTime())
                .completeTime(record.getCompleteTime())
                .evidenceList(evidenceList)
                .build();
    }
}
