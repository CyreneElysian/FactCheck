package com.factcheck.util;

import com.factcheck.constant.MessageConstant;
import com.factcheck.exception.BaseException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 上传文件校验工具
 *
 * <p>统一图片上传的扩展名白名单与大小限制，供上传、OCR 等接口复用。
 * 规则见《技术方案》8.1 图片存储与第 10 章安全设计。</p>
 */
public class UploadValidator {

    /**
     * 允许的图片扩展名（小写，不含点）
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp")));

    /**
     * 单文件最大字节数：5MB
     */
    private static final long MAX_SIZE = 5 * 1024 * 1024L;

    private UploadValidator() {
    }

    /**
     * 校验上传的图片文件
     *
     * @param file 上传的文件
     * @return 规范化后的扩展名（小写且含点，例如 ".jpg"），可直接用于拼接对象名
     * @throws BaseException 文件为空、扩展名不在白名单、或超过大小限制时抛出
     */
    public static String validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BaseException(MessageConstant.UPLOAD_FILE_EMPTY);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BaseException(MessageConstant.UPLOAD_SIZE_EXCEEDED);
        }

        String originalFilename = file.getOriginalFilename();
        int dotIndex = originalFilename == null ? -1 : originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            throw new BaseException(MessageConstant.UPLOAD_TYPE_NOT_SUPPORTED);
        }

        String extension = originalFilename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BaseException(MessageConstant.UPLOAD_TYPE_NOT_SUPPORTED);
        }
        return "." + extension;
    }
}
