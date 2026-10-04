package com.factcheck.mapper;

import com.factcheck.entity.FactCheckRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检测记录表 Mapper
 */
@Mapper
public interface FactCheckRecordMapper {

    /**
     * 根据内容哈希查询记录（精确去重用）
     * @param contentHash 内容 SHA-256
     * @return 检测记录实体
     */
    FactCheckRecord getByContentHash(String contentHash);

    /**
     * 根据主键查询记录
     * @param id 记录ID
     * @return 检测记录实体
     */
    FactCheckRecord getById(Long id);

    /**
     * 新增检测记录
     * @param record 检测记录实体
     */
    void insert(FactCheckRecord record);

    /**
     * 按主键动态更新记录（只更新非空字段）
     * @param record 检测记录实体
     */
    void update(FactCheckRecord record);

    /**
     * 带**前置状态条件**地修改结论，并在 SQL 里原子地 {@code version = version + 1}
     *
     * <p>为什么需要它：</p>
     * <ul>
     *   <li><b>版本号</b>：原实现用 Java 侧读到的 {@code record.getVersion() + 1} 写回，
     *       两个管理员并发改同一结论时双方都读到同一个值、都写回同一个值 ——
     *       后者覆盖前者，而且**版本号只 +1，等于丢了一次留痕**（与"结论可追溯"的承诺冲突）。</li>
     *   <li><b>状态流转</b>：原实现是"先 getById 校验 status，再无条件下 update"，
     *       两个管理员同时点"人工诊断"会双双通过校验，于是写两条版本快照、发两条站内信。
     *       {@code where status = #{expectedStatus}} 由数据库行锁保证只有一个能成功。</li>
     * </ul>
     *
     * <p>{@code status} 为 null 时不改状态（例如只改结论的场景，状态本就是 2）。</p>
     *
     * @param expectedStatus 前置状态：只有当前状态等于它才会更新
     * @return 影响行数：1 表示改成功，0 表示状态已变（并发或非法流转）
     */
    int updateConclusionIfStatus(@Param("id") Long id,
                                 @Param("expectedStatus") Integer expectedStatus,
                                 @Param("status") Integer status,
                                 @Param("verdict") Integer verdict,
                                 @Param("verdictSource") Integer verdictSource,
                                 @Param("reasoningChain") String reasoningChain,
                                 @Param("completeTime") LocalDateTime completeTime);

    /**
     * 命中次数 +1
     * @param id 记录ID
     */
    void updateHitCount(@Param("id") Long id);

    /**
     * 将失败记录重置为检测中，重新检测
     * @param id 记录ID
     */
    void resetToDetecting(@Param("id") Long id);

    /**
     * 分页查询用户检测记录
     * @param userId 用户ID
     * @param category 分类
     * @param status 状态
     * @return 记录列表
     */
    List<FactCheckRecord> page(@Param("userId") Long userId,
                               @Param("category") String category,
                               @Param("status") Integer status);

    /**
     * 根据状态查询记录（按热度/命中次数倒序）
     * @param status 状态
     * @return 记录列表
     */
    List<FactCheckRecord> listByStatus(@Param("status") Integer status);

    /**
     * 按 ID 集合批量查询记录（调用方自行按需排序）
     *
     * <p>用于"先从 Redis 热度榜拿到 Top10 的 ID，再回查数据库"这条链路（P2-15）。
     * 调用方必须保证 {@code ids} 非空，否则 SQL 会变成 {@code in ()}。</p>
     *
     * @param ids 记录 ID 列表（非空）
     * @return 记录列表（顺序不保证，由调用方重排）
     */
    List<FactCheckRecord> listByIds(@Param("ids") List<Long> ids);

    /**
     * 查询某个用户名下全部记录的 ID（只取主键）
     *
     * <p>用于"用户注销后把 TA 的记录从热度榜摘下来"：产品文档 §4.5 要求注销后
     * 记录保留但脱敏展示，继续占据公开的 Top10 与"脱敏"的意图相悖。</p>
     *
     * <p>只取 id 是为了避免把大字段（content / reasoning_chain）拉进内存。</p>
     */
    List<Long> listIdsByUser(@Param("userId") Long userId);

    /**
     * 按主键物理删除一条检测记录
     *
     * <p><b>只供测试收尾使用。</b>产品上检测记录是**保留**的（用户注销后也只是脱敏展示），
     * 因此业务代码里没有任何删除记录的入口；集成测试为了不在开发库里留残留数据，
     * 需要一个精确删除自己造的那条记录的手段。</p>
     *
     * <p>如果将来要加"管理员删除记录"的功能，请勿直接复用它——那需要按业务要求
     * 一并处理证据、收藏、评分、申诉、站内信与热度榜（参见 {@code HotRankService#remove}）。</p>
     *
     * @return 影响行数
     */
    int deleteById(@Param("id") Long id);

    /**
     * 管理端分页查询检测记录
     * @param category 分类
     * @param status 状态
     * @param verdict 结论
     * @param keyword 内容关键字
     * @return 记录列表
     */
    List<FactCheckRecord> adminPage(@Param("category") String category,
                                    @Param("status") Integer status,
                                    @Param("verdict") Integer verdict,
                                    @Param("keyword") String keyword);

    /**
     * 按状态统计记录数
     */
    @Select("select count(*) from t_fact_check_record where status = #{status}")
    int countByStatus(Integer status);

    /**
     * 统计指定时间内指定判定来源的已完成记录数
     */
    @Select("select count(*) from t_fact_check_record where verdict_source = #{verdictSource} " +
            "and status = 2 and complete_time >= #{start} and complete_time <= #{end}")
    int countByVerdictSourceAndTime(@Param("verdictSource") Integer verdictSource,
                                    @Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);
}
