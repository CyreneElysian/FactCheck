package com.factcheck.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.factcheck.constant.HotScoreConstant;
import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.AdminConclusionDTO;
import com.factcheck.dto.AdminRecordPageQueryDTO;
import com.factcheck.entity.AuthorityInfo;
import com.factcheck.entity.ConclusionRevision;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.Message;
import com.factcheck.entity.OperationLog;
import com.factcheck.exception.BaseException;
import com.factcheck.handler.DetectWebSocketHandler;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.mapper.AuthorityInfoMapper;
import com.factcheck.mapper.ConclusionRevisionMapper;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.result.PageResult;
import com.factcheck.service.HotRankService;
import com.factcheck.util.ValidationUtils;
import com.factcheck.service.AdminRecordService;
import com.factcheck.service.AgentClient;
import com.factcheck.vo.AuthorityInfoVO;
import com.factcheck.vo.ConclusionRevisionVO;
import com.factcheck.vo.DetectRecordVO;
import com.factcheck.vo.EvidenceVO;
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
 * 管理员结论管理服务实现
 */
@Service
@Slf4j
public class AdminRecordServiceImpl implements AdminRecordService {

    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private AuthorityInfoMapper authorityInfoMapper;
    @Autowired
    private EvidenceMapper evidenceMapper;
    @Autowired
    private ConclusionRevisionMapper conclusionRevisionMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private OperationLogMapper operationLogMapper;
    @Autowired
    private AgentClient agentClient;
    @Autowired
    private HotRankService hotRankService;
    @Autowired
    private DetectWebSocketHandler detectWebSocketHandler;

    /**
     * 分页查询全部检测记录
     */
    @Override
    public PageResult page(AdminRecordPageQueryDTO pageQueryDTO) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员分页查询检测记录：adminId={}, category={}, status={}, verdict={}, keyword={}",
                adminId, pageQueryDTO.getCategory(), pageQueryDTO.getStatus(),
                pageQueryDTO.getVerdict(), pageQueryDTO.getKeyword());
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        int pageNum = pageQueryDTO.getPage() == null ? 1 : pageQueryDTO.getPage();
        int pageSize = pageQueryDTO.getPageSize() == null ? 10 : pageQueryDTO.getPageSize();
        PageHelper.startPage(pageNum, pageSize);
        List<FactCheckRecord> list = factCheckRecordMapper.adminPage(
                pageQueryDTO.getCategory(), pageQueryDTO.getStatus(),
                pageQueryDTO.getVerdict(), pageQueryDTO.getKeyword());
        Page<FactCheckRecord> pageInfo = (Page<FactCheckRecord>) list;
        List<DetectRecordVO> records = list.stream().map(r -> toVO(r, false)).collect(Collectors.toList());
        return new PageResult(pageInfo.getTotal(), records);
    }

    /**
     * 查询记录详情
     */
    @Override
    public DetectRecordVO detail(Long recordId) {
        log.info("管理员查询记录详情：recordId={}", recordId);
        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        return toVO(record, true);
    }

    /**
     * 人工诊断：提交结论与推理依据
     */
    @Override
    public void submitConclusion(Long recordId, AdminConclusionDTO adminConclusionDTO) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员人工诊断：recordId={}, adminId={}, verdict={}", recordId, adminId, adminConclusionDTO.getVerdict());
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        validateVerdictAndReasoning(adminConclusionDTO.getVerdict(), adminConclusionDTO.getReasoningChain());

        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        if (record.getStatus() == null || record.getStatus() != 1) {
            throw new BaseException("该记录不在待诊断状态，无法人工诊断");
        }

        factCheckRecordMapper.update(FactCheckRecord.builder()
                .id(recordId)
                .status(2)
                .verdict(adminConclusionDTO.getVerdict())
                .verdictSource(2)
                .reasoningChain(adminConclusionDTO.getReasoningChain())
                .completeTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());

        // 通知用户
        messageMapper.insert(Message.builder()
                .userId(record.getUserId())
                .type(3)
                .title("人工诊断完成")
                .content("您的消息已完成人工诊断，点击查看结论")
                .relatedId(recordId)
                .isRead(0)
                .createTime(LocalDateTime.now())
                .build());

        // 操作日志
        operationLogMapper.insert(OperationLog.builder()
                .adminId(adminId)
                .operation("人工诊断")
                .method("AdminRecordServiceImpl.submitConclusion")
                .params("recordId=" + recordId + ", verdict=" + adminConclusionDTO.getVerdict())
                .createTime(LocalDateTime.now())
                .build());

        // 热度 +2
        incrementHot(recordId, HotScoreConstant.MANUAL_SCORE);

        // 加入语义去重索引
        try {
            agentClient.indexAdd(recordId, record.getContent());
        } catch (Exception e) {
            log.error("人工诊断后添加去重索引失败：recordId={}, error={}", recordId, e.getMessage());
        }

        pushWebSocket(recordId);
        log.info("人工诊断完成：recordId={}", recordId);
    }

    /**
     * 修改历史结论并附原因
     */
    @Override
    public void updateConclusion(Long recordId, AdminConclusionDTO adminConclusionDTO) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员修改历史结论：recordId={}, adminId={}, verdict={}, reason={}",
                recordId, adminId, adminConclusionDTO.getVerdict(), adminConclusionDTO.getReason());
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        // 参数校验（P2-17）：修改原因入库到 VARCHAR(500) 列，超长要在写库前拦下
        ValidationUtils.requireText(adminConclusionDTO.getReason(), "修改原因", 500);
        validateVerdictAndReasoning(adminConclusionDTO.getVerdict(), adminConclusionDTO.getReasoningChain());

        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        if (record.getStatus() == null || record.getStatus() != 2) {
            throw new BaseException("只有已完成的记录才能修改结论");
        }

        // 保存版本快照
        conclusionRevisionMapper.insert(ConclusionRevision.builder()
                .recordId(recordId)
                .oldVerdict(record.getVerdict())
                .newVerdict(adminConclusionDTO.getVerdict())
                .oldReasoningChain(record.getReasoningChain())
                .newReasoningChain(adminConclusionDTO.getReasoningChain())
                .reason(adminConclusionDTO.getReason())
                .source(1)
                .adminId(adminId)
                .createTime(LocalDateTime.now())
                .build());

        // 更新最新结论，版本号 +1
        factCheckRecordMapper.update(FactCheckRecord.builder()
                .id(recordId)
                .verdict(adminConclusionDTO.getVerdict())
                .verdictSource(2)
                .reasoningChain(adminConclusionDTO.getReasoningChain())
                .version(record.getVersion() == null ? 2 : record.getVersion() + 1)
                .updateTime(LocalDateTime.now())
                .build());

        // 通知用户
        messageMapper.insert(Message.builder()
                .userId(record.getUserId())
                .type(5)
                .title("检测结论已被修改")
                .content("您提交的消息检测结论已由管理员修改，点击查看最新结论")
                .relatedId(recordId)
                .isRead(0)
                .createTime(LocalDateTime.now())
                .build());

        // 操作日志
        operationLogMapper.insert(OperationLog.builder()
                .adminId(adminId)
                .operation("修改历史结论")
                .method("AdminRecordServiceImpl.updateConclusion")
                .params("recordId=" + recordId + ", reason=" + adminConclusionDTO.getReason())
                .createTime(LocalDateTime.now())
                .build());
        log.info("历史结论修改完成：recordId={}, newVersion={}", recordId, record.getVersion() + 1);
    }

    /**
     * 通过 WebSocket 推送人工诊断结果
     */
    private void pushWebSocket(Long recordId) {
        try {
            FactCheckRecord record = factCheckRecordMapper.getById(recordId);
            if (record == null) {
                return;
            }
            JSONObject msg = new JSONObject();
            msg.put("recordId", recordId);
            msg.put("status", record.getStatus());
            msg.put("verdict", record.getVerdict());
            msg.put("verdictSource", record.getVerdictSource());
            detectWebSocketHandler.pushResult(recordId, msg.toJSONString());
        } catch (Exception e) {
            log.warn("WebSocket 推送异常：recordId={}, error={}", recordId, e.getMessage());
        }
    }

    /**
     * 查询指定记录的结论修订历史（管理端）
     */
    @Override
    public List<ConclusionRevisionVO> revisionHistory(Long recordId) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员查询结论修订历史：recordId={}, adminId={}", recordId, adminId);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        return conclusionRevisionMapper.listByRecordId(recordId).stream()
                .map(this::toRevisionVO)
                .collect(Collectors.toList());
    }

    /**
     * 修订记录实体转视图
     */
    private ConclusionRevisionVO toRevisionVO(ConclusionRevision revision) {
        return ConclusionRevisionVO.builder()
                .id(revision.getId())
                .recordId(revision.getRecordId())
                .oldVerdict(revision.getOldVerdict())
                .newVerdict(revision.getNewVerdict())
                .oldReasoningChain(revision.getOldReasoningChain())
                .newReasoningChain(revision.getNewReasoningChain())
                .reason(revision.getReason())
                .source(revision.getSource())
                .appealId(revision.getAppealId())
                .adminId(revision.getAdminId())
                .createTime(revision.getCreateTime())
                .build();
    }

    /**
     * 校验结论和推理依据
     */
    private void validateVerdictAndReasoning(Integer verdict, String reasoningChain) {
        ValidationUtils.requireOneOf(verdict, "结论", 1, 2, 3);
        // 推理依据会随结论一起下发并写入结论修订快照，限制在 2000 字以内（P2-17）
        ValidationUtils.requireText(reasoningChain, "推理依据", 2000);
    }

    /**
     * 热度累加（P2-14 之后收敛到 {@link HotRankService}）
     */
    private void incrementHot(Long recordId, double score) {
        hotRankService.addScore(recordId, score);
    }

    /**
     * 实体转视图
     */
    private DetectRecordVO toVO(FactCheckRecord record, boolean withEvidence) {
        List<EvidenceVO> evidenceList = null;
        if (withEvidence) {
            evidenceList = evidenceMapper.getByRecordId(record.getId()).stream()
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
        }
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
