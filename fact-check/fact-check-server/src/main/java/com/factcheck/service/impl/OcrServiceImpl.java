package com.factcheck.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.factcheck.constant.MessageConstant;
import com.factcheck.exception.BaseException;
import com.factcheck.properties.OcrProperties;
import com.factcheck.service.OcrService;
import com.factcheck.util.LogMaskUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * OCR 识别服务实现（阿里云市场通用文字识别）
 * 后续更换厂商时，新增实现类并在配置中切换 Bean 即可
 */
@Service
@Slf4j
public class OcrServiceImpl implements OcrService {

    @Autowired
    private OcrProperties ocrProperties;

    private final RestTemplate restTemplate;

    public OcrServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 识别图片中的文字
     * @param file 图片文件
     * @return 识别出的文本
     */
    @Override
    public String recognize(MultipartFile file) {
        log.info("调用阿里云 OCR 识别图片：fileName={}, size={}, endpoint={}",
                file.getOriginalFilename(), file.getSize(), ocrProperties.getEndpoint());
        if (file == null || file.isEmpty()) {
            throw new BaseException(MessageConstant.OCR_FAILED);
        }
        if (ocrProperties.getEndpoint() == null || ocrProperties.getEndpoint().isEmpty()
                || ocrProperties.getAppCode() == null || ocrProperties.getAppCode().isEmpty()) {
            log.error("OCR 未配置：请配置 fact-check.ocr.app-code（application-local.yml 或环境变量 FACTCHECK_OCR_APP_CODE）");
            throw new BaseException(MessageConstant.OCR_NOT_CONFIGURED);
        }

        try {
            byte[] bytes = file.getBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);

            Map<String, Object> body = new HashMap<>();
            body.put("img", base64);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "APPCODE " + ocrProperties.getAppCode());

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(ocrProperties.getEndpoint(), request, String.class);
            // 日志脱敏：OCR 响应里就是图片中的文字（用户内容），只记字段名
            log.info("阿里云 OCR 响应字段：{}", LogMaskUtil.keysOfJson(response));

            String text = parseText(response);
            log.info("OCR 识别成功：textLength={}", text != null ? text.length() : 0);
            return text;
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("OCR 识别失败", e);
            throw new BaseException(MessageConstant.OCR_FAILED);
        }
    }

    /**
     * 解析 OCR 响应文本
     */
    private String parseText(String response) {
        if (response == null || response.isEmpty()) {
            throw new BaseException(MessageConstant.OCR_FAILED);
        }
        JSONObject json = JSONObject.parseObject(response);
        // 阿里云市场通用文字识别返回 content 字段
        String content = json.getString("content");
        if (content != null && !content.isEmpty()) {
            return content;
        }
        // 兼容 prism_wordsInfo 格式
        if (json.getJSONArray("prism_wordsInfo") != null) {
            StringBuilder sb = new StringBuilder();
            json.getJSONArray("prism_wordsInfo").forEach(item -> {
                JSONObject word = (JSONObject) item;
                if (word.getString("word") != null) {
                    sb.append(word.getString("word")).append("\n");
                }
            });
            if (sb.length() > 0) {
                return sb.toString().trim();
            }
        }
        throw new BaseException(MessageConstant.OCR_FAILED);
    }
}
