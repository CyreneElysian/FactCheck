package com.factcheck.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * OCR 识别服务接口，默认使用阿里云 OCR，后续可替换实现
 */
public interface OcrService {

    /**
     * 识别图片中的文字
     * @param file 图片文件
     * @return 识别出的文本
     */
    String recognize(MultipartFile file);
}
