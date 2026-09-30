package com.factcheck.mapper;

import com.factcheck.entity.Evidence;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 证据表 Mapper
 */
@Mapper
public interface EvidenceMapper {

    /**
     * 批量新增证据
     * @param list 证据列表
     */
    @Insert("<script>insert into t_evidence(record_id, title, url, snippet, source, source_credibility, " +
            "relevance_score, source_type, create_time) values " +
            "<foreach collection='list' item='e' separator=','>" +
            "(#{e.recordId}, #{e.title}, #{e.url}, #{e.snippet}, #{e.source}, #{e.sourceCredibility}, " +
            "#{e.relevanceScore}, #{e.sourceType}, #{e.createTime})" +
            "</foreach></script>")
    void insertBatch(@Param("list") List<Evidence> list);

    /**
     * 根据记录 ID 查询证据
     * @param recordId 记录ID
     * @return 证据列表
     */
    @Select("select id, record_id, title, url, snippet, source, source_credibility, relevance_score, " +
            "source_type, create_time from t_evidence where record_id = #{recordId} order by relevance_score desc")
    List<Evidence> getByRecordId(Long recordId);

    /**
     * 根据记录 ID 删除证据
     * @param recordId 记录ID
     */
    @Delete("delete from t_evidence where record_id = #{recordId}")
    void deleteByRecordId(Long recordId);

    /**
     * 统计记录关联的证据数量
     * @param recordId 记录ID
     * @return 证据数量
     */
    @Select("select count(*) from t_evidence where record_id = #{recordId}")
    int countByRecordId(Long recordId);
}
