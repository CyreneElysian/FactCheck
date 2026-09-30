package com.factcheck.mapper;

import com.factcheck.entity.ConclusionRevision;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 结论修订表 Mapper
 */
@Mapper
public interface ConclusionRevisionMapper {

    /**
     * 新增结论修订记录
     */
    @Insert("insert into t_conclusion_revision(record_id, old_verdict, new_verdict, old_reasoning_chain, " +
            "new_reasoning_chain, reason, source, appeal_id, admin_id, create_time) " +
            "values(#{recordId}, #{oldVerdict}, #{newVerdict}, #{oldReasoningChain}, " +
            "#{newReasoningChain}, #{reason}, #{source}, #{appealId}, #{adminId}, #{createTime})")
    void insert(ConclusionRevision revision);

    /**
     * 查询指定记录的修订历史（按时间倒序）
     */
    @Select("select id, record_id, old_verdict, new_verdict, old_reasoning_chain, " +
            "new_reasoning_chain, reason, source, appeal_id, admin_id, create_time " +
            "from t_conclusion_revision where record_id = #{recordId} order by create_time desc")
    List<ConclusionRevision> listByRecordId(Long recordId);
}
