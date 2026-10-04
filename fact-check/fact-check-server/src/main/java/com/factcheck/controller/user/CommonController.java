package com.factcheck.controller.user;

import com.factcheck.constant.MessageConstant;
import com.factcheck.result.Result;
import com.factcheck.util.UploadValidator;
import com.factcheck.util.AliOssUtil;
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
import java.util.UUID;

/**
 * 通用接口（用户端与管理端共用上传能力）
 */
@RestController
@RequestMapping("/common")
@Slf4j
public class CommonController {

    @Autowired
    private AliOssUtil aliOssUtil;

    /**
     * 文件上传到 OSS
     */
    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        log.info("文件上传：filename={}, size={}", file.getOriginalFilename(), file.getSize());
        // 扩展名白名单与 5MB 大小限制，校验不通过会抛出 BaseException 由全局异常处理器返回
        String extension = UploadValidator.validateImage(file);
        try {
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
            String objectName = "fact-check/" + datePath + "/" + UUID.randomUUID() + extension;
            String url = aliOssUtil.upload(file.getBytes(), objectName);
            log.info("文件上传成功：url={}", url);
            return Result.success(url);
        } catch (IOException e) {
            log.error("文件上传失败", e);
            return Result.error(MessageConstant.UPLOAD_FAILED);
        }
    }
}
