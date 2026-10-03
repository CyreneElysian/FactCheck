package com.factcheck.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.factcheck.constant.HotScoreConstant;
import com.factcheck.constant.MessageConstant;
import com.factcheck.constant.RedisKeyConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.DetectRecordPageQueryDTO;
import com.factcheck.dto.DetectSubmitDTO;
import com.factcheck.entity.AuthorityInfo;
import com.factcheck.entity.ConclusionRevision;
import com.factcheck.entity.DetectLog;
import com.factcheck.entity.Evidence;
import com.factcheck.entity.FactCheckRecord;
import com.factcheck.entity.Message;
import com.factcheck.exception.BaseException;
import com.factcheck.exception.RateLimitException;
import com.factcheck.exception.RecordNotFoundException;
import com.factcheck.handler.DetectWebSocketHandler;
import com.factcheck.exception.UserNotLoginException;
import com.factcheck.mapper.AuthorityInfoMapper;
import com.factcheck.mapper.ConclusionRevisionMapper;
import com.factcheck.mapper.DetectLogMapper;
import com.factcheck.mapper.EvidenceMapper;
import com.factcheck.mapper.FactCheckRecordMapper;
import com.factcheck.mapper.MessageMapper;
import com.factcheck.properties.AgentProperties;
import com.factcheck.result.PageResult;
import com.factcheck.service.AgentClient;
import com.factcheck.service.ContentSafetyService;
import com.factcheck.service.DetectService;
import com.factcheck.service.RateLimitService;
import com.factcheck.util.ValidationUtils;
import com.factcheck.vo.AuthorityInfoVO;
import com.factcheck.vo.ConclusionRevisionVO;
import com.factcheck.vo.DetectRecordVO;
import com.factcheck.vo.EvidenceVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 事实检测服务实现
 */
@Service
@Slf4j
public class DetectServiceImpl implements DetectService {

    private static final String DEFAULT_CATEGORY = "其他";
    private static final int PENDING_HOURS = 48;

    @Autowired
    private FactCheckRecordMapper factCheckRecordMapper;
    @Autowired
    private AuthorityInfoMapper authorityInfoMapper;
    @Autowired
    private ConclusionRevisionMapper conclusionRevisionMapper;
    @Autowired
    private EvidenceMapper evidenceMapper;
    @Autowired
    private DetectLogMapper detectLogMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private AgentClient agentClient;
    @Autowired
    private AgentProperties agentProperties;
    @Autowired
    private ContentSafetyService contentSafetyService;
    @Autowired
    private RateLimitService rateLimitService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private DetectWebSocketHandler detectWebSocketHandler;
    @Autowired
    @Lazy
    private DetectService detectService;

    /**
     * 提交文字/图片检测
     */
    @Override
    public Long submit(DetectSubmitDTO detectSubmitDTO) {
        Long userId = BaseContext.getCurrentId();
        log.info("用户提交检测：userId={}, sourceType={}, category={}, content={}",
                userId, detectSubmitDTO.getSourceType(), detectSubmitDTO.getCategory(), detectSubmitDTO.getContent());
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        // 防刷限流
        checkRateLimit(userId);

        // 参数校验（P2-17）：
        // 原先空内容抛的是 IllegalArgumentException，它会被全局异常处理器的兜底分支吞成"未知错误"，
        // 用户根本看不到"检测内容不能为空"；改用 BaseException 才能把文案透传给前端。
        // 同时补上长度与取值范围校验，避免超长分类 / 图片地址在写库时才报数据完整性错误。
        String content = normalizeContent(ValidationUtils.requireText(
                detectSubmitDTO.getContent(), "检测内容", ValidationUtils.MAX_CLAIM_LENGTH));

        // 内容安全审核（文字）：命中"违法交易/招揽"类规则时抛业务异常，直接拒绝提交
        contentSafetyService.checkText(content);

        String contentHash = sha256Hex(content);
        String rawCategory = ValidationUtils.optionalText(detectSubmitDTO.getCategory(), "分类", 50);
        String category = rawCategory == null ? DEFAULT_CATEGORY : rawCategory;
        Integer sourceType = detectSubmitDTO.getSourceType() == null ? 1 : detectSubmitDTO.getSourceType();
        ValidationUtils.requireOneOf(sourceType, "来源类型", 1, 2);
        // 图片地址列宽为 VARCHAR(255)；图片类提交必须带地址，否则后续图片链路无从展示
        String imageUrl = ValidationUtils.optionalHttpUrl(detectSubmitDTO.getImageUrl(), "图片地址", 255);
        if (sourceType == 2 && imageUrl == null) {
            throw new BaseException("图片检测必须提供图片地址");
        }

        // 图片类提交额外审核图片地址（协议/主机名/可选域名白名单；
        // 本地规则无法识别图片内容本身，这一点见技术方案第 10 章的能力边界说明）
        if (sourceType == 2) {
            contentSafetyService.checkImage(imageUrl);
        }

        // 1. 精确去重
        if (agentProperties.isExactEnabled()) {
            FactCheckRecord exist = factCheckRecordMapper.getByContentHash(contentHash);
            if (exist != null) {
                return handleExactHit(exist, userId, category, sourceType, imageUrl, content, contentHash);
            }
        }

        // 2. 语义去重（短文本只做精确去重）
        if (agentProperties.isSemanticEnabled() && content.length() >= agentProperties.getMinTextLength()) {
            try {
                AgentClient.DedupResult dedupResult = agentClient.dedup(content);
                log.info("语义去重结果：matched={}, recordId={}, score={}",
                        dedupResult.isMatched(), dedupResult.getRecordId(), dedupResult.getScore());
                if (dedupResult.isMatched() && dedupResult.getRecordId() != null) {
                    FactCheckRecord exist = factCheckRecordMapper.getById(dedupResult.getRecordId());
                    if (exist != null && exist.getStatus() != null && exist.getStatus() != 3) {
                        factCheckRecordMapper.updateHitCount(exist.getId());
                        incrementHot(exist.getId(), HotScoreConstant.DETECT_SCORE);
                        saveLog(userId, content, contentHash, category, sourceType, 2, exist.getId(), null);
                        return exist.getId();
                    }
                }
            } catch (Exception e) {
                log.warn("语义去重调用失败，继续新建检测：{}", e.getMessage());
            }
        }

        // 3. 未命中，创建检测记录
        FactCheckRecord record = FactCheckRecord.builder()
                .userId(userId)
                .content(content)
                .contentHash(contentHash)
                .category(category)
                .sourceType(sourceType)
                .imageUrl(imageUrl)
                .status(0)
                .version(1)
                .hitCount(1)
                .deadline(LocalDateTime.now().plusHours(PENDING_HOURS))
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        factCheckRecordMapper.insert(record);
        log.info("创建检测记录：recordId={}, contentHash={}", record.getId(), contentHash);

        // 3.1 权威信息联动：命中已发布权威信息则记录 authority_id
        AuthorityInfo matchedAuthority = matchAuthority(content);
        if (matchedAuthority != null) {
            factCheckRecordMapper.update(FactCheckRecord.builder()
                    .id(record.getId())
                    .authorityId(matchedAuthority.getId())
                    .updateTime(LocalDateTime.now())
                    .build());
            record.setAuthorityId(matchedAuthority.getId());
            log.info("检测命中权威信息：recordId={}, authorityId={}, title={}",
                    record.getId(), matchedAuthority.getId(), matchedAuthority.getTitle());
        }

        saveLog(userId, content, contentHash, category, sourceType, 0, null, record.getId());
        incrementHot(record.getId(), HotScoreConstant.DETECT_SCORE);

        // 4. 异步调用 Python Agent 检测
        detectService.asyncDetect(record.getId(), content);
        return record.getId();
    }

    /**
     * 精确去重命中处理
     */
    private Long handleExactHit(FactCheckRecord exist, Long userId, String category, Integer sourceType,
                                String imageUrl, String content, String contentHash) {
        log.info("精确去重命中：recordId={}, status={}", exist.getId(), exist.getStatus());
        if (exist.getStatus() != null && exist.getStatus() == 3) {
            // 失败记录重新检测
            factCheckRecordMapper.resetToDetecting(exist.getId());
            saveLog(userId, content, contentHash, category, sourceType, 0, null, exist.getId());
            detectService.asyncDetect(exist.getId(), content);
            return exist.getId();
        }
        factCheckRecordMapper.updateHitCount(exist.getId());
        incrementHot(exist.getId(), HotScoreConstant.DETECT_SCORE);
        saveLog(userId, content, contentHash, category, sourceType, 1, exist.getId(), null);
        return exist.getId();
    }

    /**
     * 检测防刷限流：同一用户每天最多提交 N 次（N 由 fact-check.rate-limit.detect-per-day 配置）
     *
     * <p>P2-16 之后改为复用统一的 {@link RateLimitService}，与登录/注册共用一套计数逻辑与阈值配置，
     * 不再各写一份 INCR + EXPIRE。</p>
     */
    private void checkRateLimit(Long userId) {
        rateLimitService.checkDetectSubmit(userId);
    }

    /**
     * 记录检测请求日志
     */
    private void saveLog(Long userId, String content, String contentHash, String category,
                         Integer sourceType, Integer dedupHit, Long hitRecordId, Long recordId) {
        DetectLog detectLog = DetectLog.builder()
                .userId(userId)
                .content(content)
                .contentHash(contentHash)
                .category(category)
                .sourceType(sourceType)
                .dedupHit(dedupHit)
                .hitRecordId(hitRecordId)
                .recordId(recordId)
                .createTime(LocalDateTime.now())
                .build();
        detectLogMapper.insert(detectLog);
    }

    /**
     * 匹配已发布权威信息
     * 规则：消息包含权威信息标题，或标题包含消息（消息长度 >= 4）
     */
    private AuthorityInfo matchAuthority(String content) {
        List<AuthorityInfo> list = authorityInfoMapper.listPublishedForMatch();
        for (AuthorityInfo info : list) {
            String title = info.getTitle() == null ? "" : info.getTitle().trim();
            if (title.isEmpty()) {
                continue;
            }
            if (content.contains(title)) {
                return info;
            }
            if (content.length() >= 4 && title.contains(content)) {
                return info;
            }
        }
        return null;
    }

    /**
     * 内容规范化：去首尾空格、统一空白字符
     */
    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content.trim();
    }

    /**
     * 计算 SHA-256 哈希
     */
    private String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算失败", e);
        }
    }

    /**
     * 热度累加
     */
    private void incrementHot(Long recordId, double score) {
        try {
            redisTemplate.opsForZSet().incrementScore(RedisKeyConstant.HOT_CLAIM_ZSET, String.valueOf(recordId), score);
            log.info("热度更新：recordId={}, score={}", recordId, score);
        } catch (Exception e) {
            log.warn("热度更新失败：recordId={}, error={}", recordId, e.getMessage());
        }
    }

    /**
     * 异步执行 LLM 检测，并写回结果
     */
    @Async("detectExecutor")
    @Transactional
    @Override
    public void asyncDetect(Long recordId, String content) {
        log.info("开始异步检测：recordId={}", recordId);
        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null) {
            log.warn("异步检测：记录不存在，recordId={}", recordId);
            return;
        }

        try {
            AgentClient.DetectResult result = agentClient.detect(content);
            log.info("Agent 检测返回：recordId={}, error={}, canJudge={}, verdict={}",
                    recordId, result.isError(), result.isCanJudge(), result.getVerdict());

            // error=true 表示 Agent 侧故障（LLM 调用失败 / 输出无法解析）：
            // 记为"检测失败"允许重试，而不是把人工队列灌满
            boolean agentFailed = result.isError();
            Integer verdict = mapVerdict(result.getVerdict());
            // 模型声称能判定、却给了一个我们映射不了的结论（如 "MAYBE"）：
            // 这属于 Agent 契约问题，而不是"AI 确实判断不了"，
            // 因此同样记为"检测失败"（可重试），不占用人工队列
            boolean verdictUnmappable = !agentFailed && result.isCanJudge() && verdict == null;
            boolean failed = agentFailed || verdictUnmappable;
            boolean canJudge = !failed && result.isCanJudge() && verdict != null;

            FactCheckRecord.FactCheckRecordBuilder updateBuilder = FactCheckRecord.builder()
                    .id(recordId)
                    .updateTime(LocalDateTime.now());

            if (canJudge) {
                updateBuilder.status(2)
                        .verdict(verdict)
                        .verdictSource(1)
                        .confidence(result.getConfidence() != null ? BigDecimal.valueOf(result.getConfidence()) : null)
                        .reasoningChain(result.getReasoningChain())
                        .completeTime(LocalDateTime.now());
            } else if (failed) {
                String reason = verdictUnmappable
                        ? "Agent 返回的结论无法识别（verdict=" + result.getVerdict() + "），已标记为检测失败，可重试"
                        : (result.getHumanReason() != null ? result.getHumanReason() : "检测服务异常，请稍后重试");
                log.warn("检测失败归口：recordId={}, agentFailed={}, verdictUnmappable={}, verdict={}",
                        recordId, agentFailed, verdictUnmappable, result.getVerdict());
                updateBuilder.status(3).aiSummary(reason);
            } else {
                updateBuilder.status(1)
                        .aiSummary(result.getHumanReason() != null ? result.getHumanReason() : "AI 无法判断，转人工诊断");
            }
            factCheckRecordMapper.update(updateBuilder.build());

            // 保存证据
            saveEvidence(recordId, result);

            // 发送站内信
            if (canJudge) {
                messageMapper.insert(Message.builder()
                        .userId(record.getUserId())
                        .type(1)
                        .title("检测完成")
                        .content("您的消息已完成检测，点击查看结论")
                        .relatedId(recordId)
                        .isRead(0)
                        .createTime(LocalDateTime.now())
                        .build());
            } else if (failed) {
                messageMapper.insert(Message.builder()
                        .userId(record.getUserId())
                        .type(6)
                        .title("检测失败")
                        .content("很抱歉，检测服务暂时不可用，请稍后重新提交")
                        .relatedId(recordId)
                        .isRead(0)
                        .createTime(LocalDateTime.now())
                        .build());
            } else {
                messageMapper.insert(Message.builder()
                        .userId(record.getUserId())
                        .type(2)
                        .title("消息已进入人工诊断队列")
                        .content("AI 暂时无法判断您的消息，已转交管理员人工诊断，请稍后查看")
                        .relatedId(recordId)
                        .isRead(0)
                        .createTime(LocalDateTime.now())
                        .build());
            }

            // 有结论后加入语义去重索引
            if (canJudge) {
                try {
                    agentClient.indexAdd(recordId, content);
                } catch (Exception e) {
                    log.error("添加去重索引失败：recordId={}, error={}", recordId, e.getMessage());
                }
            }

            // WebSocket 实时推送检测结果
            pushWebSocket(recordId);
        } catch (Exception e) {
            log.error("异步检测失败：recordId={}", recordId, e);
            factCheckRecordMapper.update(FactCheckRecord.builder()
                    .id(recordId)
                    .status(3)
                    .updateTime(LocalDateTime.now())
                    .build());
            messageMapper.insert(Message.builder()
                    .userId(record.getUserId())
                    .type(6)
                    .title("检测失败")
                    .content("很抱歉，检测服务暂时不可用，请稍后重试")
                    .relatedId(recordId)
                    .isRead(0)
                    .createTime(LocalDateTime.now())
                    .build());

            pushWebSocket(recordId);
        }
    }

    /**
     * 通过 WebSocket 推送检测结果
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
     * 保存 Agent 返回的证据
     */
    private void saveEvidence(Long recordId, AgentClient.DetectResult result) {
        if (result.getEvidence() == null || result.getEvidence().isEmpty()) {
            return;
        }
        evidenceMapper.deleteByRecordId(recordId);
        List<Evidence> evidenceList = new ArrayList<>();
        for (AgentClient.DetectEvidence e : result.getEvidence()) {
            evidenceList.add(Evidence.builder()
                    .recordId(recordId)
                    .title(e.getTitle())
                    .url(e.getUrl())
                    .snippet(e.getSnippet())
                    .source(e.getSource())
                    .sourceCredibility(e.getSourceCredibility())
                    .relevanceScore(e.getRelevanceScore())
                    .sourceType(1)
                    .createTime(LocalDateTime.now())
                    .build());
        }
        evidenceMapper.insertBatch(evidenceList);
    }

    /**
     * 查询检测记录详情（前端轮询用）
     */
    @Override
    public DetectRecordVO getRecord(Long recordId) {
        Long userId = BaseContext.getCurrentId();
        log.info("查询检测记录详情：recordId={}, userId={}", recordId, userId);
        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null || (userId != null && !userId.equals(record.getUserId()))) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
        }
        return toVO(record, true);
    }

    /**
     * 分页查询当前用户的检测记录
     */
    @Override
    public PageResult pageRecords(DetectRecordPageQueryDTO pageQueryDTO) {
        Long userId = BaseContext.getCurrentId();
        log.info("分页查询我的检测记录：userId={}, category={}, status={}", userId,
                pageQueryDTO.getCategory(), pageQueryDTO.getStatus());
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        int pageNum = pageQueryDTO.getPage() == null ? 1 : pageQueryDTO.getPage();
        int pageSize = pageQueryDTO.getPageSize() == null ? 10 : pageQueryDTO.getPageSize();
        PageHelper.startPage(pageNum, pageSize);
        List<FactCheckRecord> list = factCheckRecordMapper.page(userId, pageQueryDTO.getCategory(), pageQueryDTO.getStatus());
        Page<FactCheckRecord> pageInfo = (Page<FactCheckRecord>) list;
        List<DetectRecordVO> records = list.stream().map(r -> toVO(r, false)).collect(Collectors.toList());
        return new PageResult(pageInfo.getTotal(), records);
    }

    /**
     * 实体转视图
     */
    private DetectRecordVO toVO(FactCheckRecord record, boolean withEvidence) {
        List<EvidenceVO> evidenceList = null;
        if (withEvidence) {
            evidenceList = evidenceMapper.getByRecordId(record.getId()).stream().map(e ->
                    EvidenceVO.builder()
                            .id(e.getId())
                            .title(e.getTitle())
                            .url(e.getUrl())
                            .snippet(e.getSnippet())
                            .source(e.getSource())
                            .sourceCredibility(e.getSourceCredibility())
                            .relevanceScore(e.getRelevanceScore())
                            .sourceType(e.getSourceType())
                            .build()
            ).collect(Collectors.toList());
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
     * 查询检测记录的结论修订历史（用户端，需归属校验）
     */
    @Override
    public List<ConclusionRevisionVO> revisionHistory(Long recordId) {
        Long userId = BaseContext.getCurrentId();
        log.info("用户查询结论修订历史：recordId={}, userId={}", recordId, userId);
        if (userId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        FactCheckRecord record = factCheckRecordMapper.getById(recordId);
        if (record == null || !userId.equals(record.getUserId())) {
            throw new RecordNotFoundException(MessageConstant.RECORD_NOT_FOUND);
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
     * 将 Agent 返回的 verdict 字符串映射为枚举值
     */
    private Integer mapVerdict(String verdict) {
        if ("TRUE".equalsIgnoreCase(verdict)) {
            return 1;
        }
        if ("FALSE".equalsIgnoreCase(verdict)) {
            return 2;
        }
        if ("UNVERIFIED".equalsIgnoreCase(verdict)) {
            return 3;
        }
        return null;
    }
}
