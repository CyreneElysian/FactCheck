package com.factcheck.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.factcheck.properties.AgentProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
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

    private final RestTemplate restTemplate;

    @Autowired
    private AgentProperties agentProperties;

    public AgentClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        this.restTemplate = new RestTemplate(factory);
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

    private JSONObject post(String path, Map<String, Object> body) {
        String url = agentProperties.getBaseUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (agentProperties != null && agentProperties.getBaseUrl() == null) {
            throw new IllegalStateException("Agent baseUrl 未配置");
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
