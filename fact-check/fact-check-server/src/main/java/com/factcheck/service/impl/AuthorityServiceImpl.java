package com.factcheck.service.impl;

import com.factcheck.dto.AuthorityPageQueryDTO;
import com.factcheck.entity.AuthorityInfo;
import com.factcheck.exception.BaseException;
import com.factcheck.mapper.AuthorityInfoMapper;
import com.factcheck.result.PageResult;
import com.factcheck.service.AuthorityService;
import com.factcheck.vo.AuthorityInfoVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 权威信息服务实现（用户端）
 */
@Service
@Slf4j
public class AuthorityServiceImpl implements AuthorityService {

    @Autowired
    private AuthorityInfoMapper authorityInfoMapper;

    /**
     * 分页查询已发布权威信息
     */
    @Override
    public PageResult page(AuthorityPageQueryDTO pageQueryDTO) {
        log.info("用户分页查询权威信息：category={}, keyword={}", pageQueryDTO.getCategory(), pageQueryDTO.getKeyword());
        int pageNum = pageQueryDTO.getPage() == null ? 1 : pageQueryDTO.getPage();
        int pageSize = pageQueryDTO.getPageSize() == null ? 10 : pageQueryDTO.getPageSize();
        PageHelper.startPage(pageNum, pageSize);
        List<AuthorityInfo> list = authorityInfoMapper.pagePublished(
                pageQueryDTO.getCategory(), pageQueryDTO.getKeyword());
        Page<AuthorityInfo> pageInfo = (Page<AuthorityInfo>) list;
        List<AuthorityInfoVO> records = list.stream().map(this::toVO).collect(Collectors.toList());
        return new PageResult(pageInfo.getTotal(), records);
    }

    /**
     * 查询权威信息详情（仅已发布）
     */
    @Override
    public AuthorityInfoVO detail(Long id) {
        log.info("用户查询权威信息详情：id={}", id);
        AuthorityInfo info = authorityInfoMapper.getById(id);
        if (info == null || info.getStatus() == null || info.getStatus() != 1) {
            throw new BaseException("权威信息不存在或未发布");
        }
        return toVO(info);
    }

    /**
     * 实体转视图
     */
    private AuthorityInfoVO toVO(AuthorityInfo info) {
        return AuthorityInfoVO.builder()
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
