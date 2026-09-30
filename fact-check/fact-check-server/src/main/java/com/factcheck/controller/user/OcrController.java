package com.factcheck.controller.user;

import com.factcheck.constant.MessageConstant;
import com.factcheck.result.Result;
import com.factcheck.service.OcrService;
import com.factcheck.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 图片 OCR 控制器（用户端）
 */
@RestController
@RequestMapping("/ocr")
@Slf4j
public class OcrController {

    @Autowired
    private AliOssUtil aliOssUtil;
    @Autowired
    private OcrService ocrService;

    /**
     * 上传图片并 OCR 识别文字
     */
    @PostMapping("/recognize")
    public Result<Map<String, String>> recognize(@RequestParam("file") MultipartFile file) {
        log.info("上传图片并OCR识别：{}", file.getOriginalFilename());
        try {
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.lastIndexOf(".") >= 0
                    ? originalFilename.substring(originalFilename.lastIndexOf(".")) : "";
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
            String objectName = "fact-check/image/" + datePath + "/" + UUID.randomUUID() + extension;
            String imageUrl = aliOssUtil.upload(file.getBytes(), objectName);

            String text = ocrService.recognize(file);
            Map<String, String> data = new HashMap<>();
            data.put("imageUrl", imageUrl);
            data.put("text", text);
            return Result.success(data);
        } catch (IOException e) {
            log.error("图片上传或OCR识别失败", e);
            return Result.error(MessageConstant.OCR_FAILED);
        }
    }
}
