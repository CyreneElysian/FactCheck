package com.factcheck.service.impl;

import com.factcheck.constant.MessageConstant;
import com.factcheck.context.BaseContext;
import com.factcheck.dto.AdminAuthorityDTO;
import com.factcheck.entity.AuthorityInfo;
import com.factcheck.entity.OperationLog;
import com.factcheck.exception.BaseException;
import com.factcheck.mapper.AuthorityInfoMapper;
import com.factcheck.mapper.OperationLogMapper;
import com.factcheck.result.PageResult;
import com.factcheck.service.AdminAuthorityService;
import com.factcheck.util.ValidationUtils;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员权威信息管理服务实现
 */
@Service
@Slf4j
public class AdminAuthorityServiceImpl implements AdminAuthorityService {

    @Autowired
    private AuthorityInfoMapper authorityInfoMapper;
    @Autowired
    private OperationLogMapper operationLogMapper;

    /**
     * 分页查询权威信息（含草稿/下线）
     */
    @Override
    public PageResult page(Integer page, Integer pageSize) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员分页查询权威信息：adminId={}, page={}, pageSize={}", adminId, page, pageSize);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        // 参数校验（P2-17）：防止 page=0 触发"不分页"与超大 pageSize 全表加载
        ValidationUtils.requirePaging(page, pageSize);
        int pageNum = page == null ? 1 : page;
        int size = pageSize == null ? 10 : pageSize;
        PageHelper.startPage(pageNum, size);
        List<AuthorityInfo> list = authorityInfoMapper.pageAll();
        Page<AuthorityInfo> pageInfo = (Page<AuthorityInfo>) list;
        return new PageResult(pageInfo.getTotal(), list);
    }

    /**
     * 新增权威信息
     */
    @Override
    public void save(AdminAuthorityDTO adminAuthorityDTO) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员新增权威信息：adminId={}, title={}, status={}",
                adminId, adminAuthorityDTO.getTitle(), adminAuthorityDTO.getStatus());
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        validate(adminAuthorityDTO);

        int status = adminAuthorityDTO.getStatus() == null ? 0 : adminAuthorityDTO.getStatus();
        AuthorityInfo info = AuthorityInfo.builder()
                .title(adminAuthorityDTO.getTitle())
                .content(adminAuthorityDTO.getContent())
                .category(adminAuthorityDTO.getCategory())
                .source(adminAuthorityDTO.getSource())
                .sourceUrl(adminAuthorityDTO.getSourceUrl())
                .coverImage(adminAuthorityDTO.getCoverImage())
                .status(status)
                .publishTime(status == 1 ? LocalDateTime.now() : null)
                .createBy(adminId)
                .updateBy(adminId)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        authorityInfoMapper.insert(info);

        saveLog(adminId, "新增权威信息", "title=" + adminAuthorityDTO.getTitle());
        log.info("权威信息新增成功：id={}", info.getId());
    }

    /**
     * 编辑权威信息
     */
    @Override
    public void update(AdminAuthorityDTO adminAuthorityDTO) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员编辑权威信息：adminId={}, id={}", adminId, adminAuthorityDTO.getId());
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        if (adminAuthorityDTO.getId() == null) {
            throw new BaseException("请指定要编辑的权威信息ID");
        }
        validate(adminAuthorityDTO);

        AuthorityInfo exist = authorityInfoMapper.getById(adminAuthorityDTO.getId());
        if (exist == null) {
            throw new BaseException("权威信息不存在");
        }

        AuthorityInfo.AuthorityInfoBuilder builder = AuthorityInfo.builder()
                .id(adminAuthorityDTO.getId())
                .title(adminAuthorityDTO.getTitle())
                .content(adminAuthorityDTO.getContent())
                .category(adminAuthorityDTO.getCategory())
                .source(adminAuthorityDTO.getSource())
                .sourceUrl(adminAuthorityDTO.getSourceUrl())
                .coverImage(adminAuthorityDTO.getCoverImage())
                .status(adminAuthorityDTO.getStatus())
                .updateBy(adminId)
                .updateTime(LocalDateTime.now());
        // 从草稿/下线变为发布时，设置发布时间
        if (adminAuthorityDTO.getStatus() != null && adminAuthorityDTO.getStatus() == 1
                && (exist.getStatus() == null || exist.getStatus() != 1)) {
            builder.publishTime(LocalDateTime.now());
        }
        authorityInfoMapper.update(builder.build());

        saveLog(adminId, "编辑权威信息", "id=" + adminAuthorityDTO.getId());
        log.info("权威信息编辑成功：id={}", adminAuthorityDTO.getId());
    }

    /**
     * 上/下线权威信息
     */
    @Override
    public void updateStatus(Long id, Integer status) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员上下线权威信息：adminId={}, id={}, status={}", adminId, id, status);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        AuthorityInfo exist = authorityInfoMapper.getById(id);
        if (exist == null) {
            throw new BaseException("权威信息不存在");
        }

        AuthorityInfo.AuthorityInfoBuilder builder = AuthorityInfo.builder()
                .id(id)
                .status(status)
                .updateBy(adminId)
                .updateTime(LocalDateTime.now());
        if (status != null && status == 1 && (exist.getStatus() == null || exist.getStatus() != 1)) {
            builder.publishTime(LocalDateTime.now());
        }
        authorityInfoMapper.update(builder.build());

        saveLog(adminId, status != null && status == 1 ? "发布权威信息" : "下线权威信息", "id=" + id);
        log.info("权威信息状态更新成功：id={}, status={}", id, status);
    }

    /**
     * 删除权威信息
     */
    @Override
    public void delete(Long id) {
        Long adminId = BaseContext.getCurrentId();
        log.info("管理员删除权威信息：adminId={}, id={}", adminId, id);
        if (adminId == null) {
            throw new BaseException(MessageConstant.ADMIN_NOT_LOGIN);
        }
        AuthorityInfo exist = authorityInfoMapper.getById(id);
        if (exist == null) {
            throw new BaseException("权威信息不存在");
        }
        authorityInfoMapper.deleteById(id);

        saveLog(adminId, "删除权威信息", "id=" + id);
        log.info("权威信息删除成功：id={}", id);
    }

    /**
     * 校验标题和正文必填
     */
    private void validate(AdminAuthorityDTO adminAuthorityDTO) {
        if (!StringUtils.hasLength(adminAuthorityDTO.getTitle())) {
            throw new BaseException("请填写标题");
        }
        if (!StringUtils.hasLength(adminAuthorityDTO.getContent())) {
            throw new BaseException("请填写正文");
        }
        if (StringUtils.hasLength(adminAuthorityDTO.getSourceUrl())) {
            String url = adminAuthorityDTO.getSourceUrl().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new BaseException("原文链接必须以 http:// 或 https:// 开头");
            }
        }
    }

    /**
     * 保存操作日志
     */
    private void saveLog(Long adminId, String operation, String params) {
        operationLogMapper.insert(OperationLog.builder()
                .adminId(adminId)
                .operation(operation)
                .method("AdminAuthorityServiceImpl")
                .params(params)
                .createTime(LocalDateTime.now())
                .build());
    }
}
