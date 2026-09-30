package com.factcheck.service.impl;

import com.factcheck.dto.*;
import com.factcheck.entity.*;
import com.factcheck.result.PageResult;
import com.factcheck.service.ContentSafetyService;
import com.factcheck.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 内容安全审核服务实现（默认阿里云内容安全）
 */
@Service
@Slf4j
public class ContentSafetyServiceImpl implements ContentSafetyService {
    /**
     * 文字内容安全审核
     */
    @Override
    public void checkText(String text) {
        log.info("文字内容安全审核：{}", text);
        // TODO 实现业务逻辑
    }
    /**
     * 图片内容安全审核
     */
    @Override
    public void checkImage(String imageUrl) {
        log.info("图片内容安全审核：{}", imageUrl);
        // TODO 实现业务逻辑
    }
}
