package com.factcheck.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.factcheck.properties.AgentProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Python Agent HTTP 客户端
 * 负责调用 /dedup、/detect、/index/add 三个内部接口
 */
@Component
@Slf4j
public class AgentClient {

    /**
     * 读超时兜底值（秒）：应显著大于 Python 侧 DETECT_TOTAL_BUDGET（默认 45s），
     * 否则会出现"Java 已判失败、Agent 仍在跑并可能写索引"的状态不一致。
     */
    private static final int DEFAULT_READ_TIMEOUT_SECONDS = 90;

    private final RestTemplate restTemplate;
    private final AgentProperties agentProperties;

    public AgentClient(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
        int readTimeout = agentProperties.getTimeout() > 0
                ? agentProperties.getTimeout() : DEFAULT_READ_TIMEOUT_SECONDS;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(readTimeout * 1000);
        this.restTemplate = new RestTemplate(factory);
        log.info("初始化 Python Agent 客户端：baseUrl={}, readTimeout={}s, 内部鉴权={}",
                agentProperties.getBaseUrl(), readTimeout,
                StringUtils.hasText(agentProperties.getInternalToken()) ? "已启用" : "未配置令牌");
    }

    /**
     * 调用 /dedup 语义去重
     */
    public DedupResult dedup(String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        JSONObject json = post("/dedup", body);
        DedupResult result = new DedupResult();
        result.setMatched(json.getBooleanValue("matched"));
        result.setRecordId(json.getLong("record_id"));
        result.setScore(json.getDoubleValue("score"));
        result.setMatchedText(json.getString("matched_text"));
        return result;
    }

    /**
     * 调用 /detect 事实检测
     */
    public DetectResult detect(String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        JSONObject json = post("/detect", body);
        DetectResult result = new DetectResult();
        result.setCanJudge(json.getBooleanValue("can_judge"));
        // error=true 表示 Agent 侧故障（LLM 调用失败 / 输出无法解析），
        // 与"AI 确实无法判断"区分开，前者应标记检测失败而非转人工
        result.setError(json.getBooleanValue("error"));
        result.setVerdict(json.getString("verdict"));
        result.setConfidence(json.getDouble("confidence"));
        result.setReasoningChain(json.getString("reasoning_chain"));
        result.setHumanReason(json.getString("human_reason"));

        List<DetectEvidence> evidenceList = new ArrayList<>();
        if (json.getJSONArray("evidence") != null) {
            for (Object item : json.getJSONArray("evidence")) {
                JSONObject e = (JSONObject) item;
                DetectEvidence evidence = new DetectEvidence();
                evidence.setTitle(e.getString("title"));
                evidence.setUrl(e.getString("url"));
                evidence.setSnippet(e.getString("snippet"));
                evidence.setSource(e.getString("source"));
                evidence.setSourceCredibility(e.getBigDecimal("source_credibility"));
                evidence.setRelevanceScore(e.getBigDecimal("relevance_score"));
                evidenceList.add(evidence);
            }
        }
        result.setEvidence(evidenceList);
        return result;
    }

    /**
     * 调用 /index/add 新增向量索引
     */
    public void indexAdd(Long recordId, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("record_id", recordId);
        body.put("text", text);
        post("/index/add", body);
    }

    /**
     * 内部接口鉴权请求头（P2-21）
     *
     * <p>Python 侧对 /detect、/dedup、/index/add 做 fail-closed 校验：配置了 INTERNAL_TOKEN
     * 就必须带对，没配置则一律 401。早期版本 Java 根本不发这个头、Python 未配置就不校验，
     * 这层鉴权等于从未生效——Agent 一旦被误暴露，任何人都能消耗 LLM 额度并写向量索引。</p>
     */
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private JSONObject post(String path, Map<String, Object> body) {
        String url = agentProperties.getBaseUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (agentProperties != null && agentProperties.getBaseUrl() == null) {
            throw new IllegalStateException("Agent baseUrl 未配置");
        }
        if (StringUtils.hasText(agentProperties.getInternalToken())) {
            headers.set(INTERNAL_TOKEN_HEADER, agentProperties.getInternalToken());
        } else {
            // 不抛异常：本地先起 Agent（未配令牌）时仍允许联调，但每次调用都留下明确告警
            log.warn("未配置 fact-check.agent.internal-token，Agent 侧若启用了内部鉴权将返回 401");
        }
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        log.info("调用 Python Agent：url={}, body={}", url, JSON.toJSONString(body));
        String response = restTemplate.postForObject(url, request, String.class);
        log.info("Python Agent 响应：{}", response);
        return JSONObject.parseObject(response);
    }

    @Data
    public static class DedupResult {
        private boolean matched;
        private Long recordId;
        private double score;
        private String matchedText;
    }

    @Data
    public static class DetectResult {
        private boolean canJudge;
        /** Agent 侧故障标志（LLM 调用失败 / 输出无法解析）：应记为检测失败而非转人工 */
        private boolean error;
        private String verdict;
        private Double confidence;
        private String reasoningChain;
        private String humanReason;
        private List<DetectEvidence> evidence;
    }

    @Data
    public static class DetectEvidence {
        private String title;
        private String url;
        private String snippet;
        private String source;
        private BigDecimal sourceCredibility;
        private BigDecimal relevanceScore;
    }
}
