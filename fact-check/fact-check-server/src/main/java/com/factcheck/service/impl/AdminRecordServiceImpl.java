package com.factcheck.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.factcheck.constant.HotScoreConstant;
import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.AdminConclusionDTO;
import com.factcheck.dto.AdminRecordPageQueryDTO;
import com.factcheck.dto.EvidenceCreateDTO;
import com.factcheck.entity.AuthorityInfo;
import com.factcheck.entity.ConclusionRevision;
import com.factcheck.entity.Evidence;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        // 参数校验：未传取默认值，传了非法值（page=0 / pageSize 过大）必须拒绝
        ValidationUtils.Paging paging = ValidationUtils.requirePagingOrDefault(
                pageQueryDTO.getPage(), pageQueryDTO.getPageSize());
        PageHelper.startPage(paging.getPage(), paging.getPageSize());
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
    @Transactional(rollbackFor = Exception.class)
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

        // 条件更新：仅当仍处于"待诊断"时才能提交，并在 SQL 里原子 version+1。
        // 原实现是"先校验 status、再无条件下 update"，两个管理员同时点会双双通过校验，
        // 于是写两条版本快照、发两条站内信。同时修正版本语义：原先写
        // record.getVersion()+1，而 version 从不 null（建记录时即为 1），
        // 导致首次人工诊断凭空把版本号从 1 推到 3；现在由 SQL 在原值上 +1，恒为 +1。
        int affected = factCheckRecordMapper.updateConclusionIfStatus(
                recordId, 1, 2,
                adminConclusionDTO.getVerdict(), 2, adminConclusionDTO.getReasoningChain(),
                LocalDateTime.now());
        if (affected == 0) {
            throw new BaseException("该记录已被其他管理员处理，请刷新后重试");
        }

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
    @Transactional(rollbackFor = Exception.class)
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

        // 更新最新结论：条件更新（仅当仍为已完成）+ SQL 侧原子 version = version + 1。
        // 若并发下状态已变，affected=0 会抛异常，连同上面刚写入的版本快照一起回滚
        // （本方法有 @Transactional(rollbackFor = Exception.class)），不会留下"快照写了、结论没改"的脏数据。
        int affected = factCheckRecordMapper.updateConclusionIfStatus(
                recordId, 2, null,
                adminConclusionDTO.getVerdict(), 2, adminConclusionDTO.getReasoningChain(),
                null);
        if (affected == 0) {
            throw new BaseException("该记录状态已变化，请刷新后重试");
        }

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

    /**
     * 管理员补充证据（P3-35）
     */
    @Override
    public void addEvidence(Long recordId, EvidenceCreateDTO evidenceCreateDTO) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员补充证据：adminId={}, recordId={}", adminId, recordId);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        if (evidenceCreateDTO == null) {
            throw new BaseException("证据内容不能为空");
        }
        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        // 参数校验（P2-17 的统一入口）：标题必填且不超过 200（t_evidence.title 列宽）
        String title = ValidationUtils.requireText(evidenceCreateDTO.getTitle(), "证据标题", 200);
        // 链接选填，但填了就必须是 http(s)，避免写入 javascript:/file: 之类的地址
        // （t_evidence.url 列宽 500）
        String url = ValidationUtils.optionalHttpUrl(evidenceCreateDTO.getUrl(), "证据链接", 500);
        String snippet = ValidationUtils.optionalText(evidenceCreateDTO.getSnippet(), "证据摘要", 500);

        evidenceMapper.insert(Evidence.builder()
                .recordId(recordId)
                .title(title)
                .url(url)
                .snippet(snippet)
                .source("管理员补充")
                // 人工补充的证据不臆造可信度/相关度分值：留空，前端显示"-"
                .sourceCredibility(null)
                .relevanceScore(null)
                .sourceType(2)
                .createTime(LocalDateTime.now())
                .build());

        operationLogMapper.insert(OperationLog.builder()
                .adminId(adminId)
                .operation("补充证据")
                .method("AdminRecordServiceImpl.addEvidence")
                .params("recordId=" + recordId + ", title=" + title)
                .createTime(LocalDateTime.now())
                .build());
    }

    /**
     * 删除证据（P3-35）
     */
    @Override
    public void removeEvidence(Long recordId, Long evidenceId) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员删除证据：adminId={}, recordId={}, evidenceId={}", adminId, recordId, evidenceId);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        if (evidenceId == null) {
            throw new BaseException("证据 ID 不能为空");
        }
        Evidence evidence = evidenceMapper.getById(evidenceId);
        // 归属校验：必须确认这条证据属于路径上的记录，否则等于"用某记录的入口删任意证据"
        if (evidence == null || !recordId.equals(evidence.getRecordId())) {
            throw new BaseException("证据不存在或不属于该记录");
        }
        evidenceMapper.deleteById(evidenceId);

        operationLogMapper.insert(OperationLog.builder()
                .adminId(adminId)
                .operation("删除证据")
                .method("AdminRecordServiceImpl.removeEvidence")
                .params("recordId=" + recordId + ", evidenceId=" + evidenceId
                        + ", title=" + evidence.getTitle())
                .createTime(LocalDateTime.now())
                .build());
    }
}
