package com.factcheck.mapper;

import com.factcheck.entity.AuthorityInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 权威信息表 Mapper
 */
@Mapper
public interface AuthorityInfoMapper {

    /**
     * 根据主键查询权威信息
     */
    AuthorityInfo getById(Long id);

    /**
     * 分页查询已发布的权威信息（用户端）
     */
    List<AuthorityInfo> pagePublished(@Param("category") String category, @Param("keyword") String keyword);

    /**
     * 查询全部权威信息（管理端，配合 PageHelper）
     */
    List<AuthorityInfo> pageAll();

    /**
     * 新增权威信息
     */
    void insert(AuthorityInfo authorityInfo);

    /**
     * 按主键动态更新权威信息
     */
    void update(AuthorityInfo authorityInfo);

    /**
     * 按主键删除权威信息
     */
    void deleteById(Long id);

    /**
     * 查询用于检测联动的已发布权威信息列表（最多 100 条）
     */
    List<AuthorityInfo> listPublishedForMatch();
}
