package com.factcheck.mapper;

import com.factcheck.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户表 Mapper
 */
@Mapper
public interface UserMapper {

    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return 用户实体
     */
    User getByUsername(String username);

    /**
     * 根据主键查询用户
     * @param id 用户ID
     * @return 用户实体
     */
    User getById(Long id);

    /**
     * 根据微信 openid 查询用户
     * @param openid 微信 openid
     * @return 用户实体
     */
    User getByOpenid(String openid);

    /**
     * 新增用户
     * @param user 用户实体
     */
    void insert(User user);

    /**
     * 按主键动态更新用户信息（只更新非空字段）
     * @param user 用户实体
     */
    void update(User user);

    /**
     * 注销账号：状态置为已注销并解绑微信
     * @param id 用户ID
     */
    void cancel(@Param("id") Long id);

    /**
     * 按角色统计用户数
     */
    @Select("select count(*) from t_user where role = #{role}")
    int countByRole(Integer role);

    /**
     * 查询全部用户（管理端，配合 PageHelper）
     */
    @Select("select id, username, password, nickname, avatar, role, status, wechat_openid, " +
            "wechat_unionid, last_login_time, create_time, update_time from t_user order by create_time desc")
    List<User> pageAll();
}
