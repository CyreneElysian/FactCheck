package com.factcheck.service;

import com.factcheck.dto.PasswordEditDTO;
import com.factcheck.dto.UserLoginDTO;
import com.factcheck.dto.UserProfileDTO;
import com.factcheck.dto.UserRegisterDTO;
import com.factcheck.dto.WechatBindDTO;
import com.factcheck.dto.WechatLoginDTO;
import com.factcheck.dto.WechatRegisterDTO;
import com.factcheck.entity.User;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 用户注册
     */
    User register(UserRegisterDTO userRegisterDTO);

    /**
     * 用户/管理员登录
     */
    User login(UserLoginDTO userLoginDTO);

    /**
     * 微信小程序登录
     */
    User wechatLogin(WechatLoginDTO wechatLoginDTO);

    /**
     * 微信小程序绑定 Web 账号
     */
    User bindWechat(WechatBindDTO wechatBindDTO);

    /**
     * 微信小程序**注册新账号**（可选同时绑定当前微信）
     *
     * <p>与 {@link #bindWechat} 并列的另一条路：小程序端在"该微信未绑定任何账号"时，
     * 让用户自己选是**绑定已有 Web 账号**还是**创建新账号**，而不是强制绑定。</p>
     *
     * <p>带 {@code code} 时，注册与绑定在**同一个事务**里完成 —— 避免出现
     * "账号建好了但微信没绑上、用户仍然登不进去"的中间态。</p>
     */
    User registerByWechat(WechatRegisterDTO wechatRegisterDTO);

    /**
     * 获取当前用户信息
     */
    User getProfile();

    /**
     * 修改用户资料
     */
    void updateProfile(UserProfileDTO userProfileDTO);

    /**
     * 修改密码
     */
    void updatePassword(PasswordEditDTO passwordEditDTO);

    /**
     * 注销当前账号
     */
    void cancelAccount();
}
