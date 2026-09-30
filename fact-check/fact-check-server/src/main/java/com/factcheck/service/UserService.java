package com.factcheck.service;

import com.factcheck.dto.PasswordEditDTO;
import com.factcheck.dto.UserLoginDTO;
import com.factcheck.dto.UserProfileDTO;
import com.factcheck.dto.UserRegisterDTO;
import com.factcheck.dto.WechatBindDTO;
import com.factcheck.dto.WechatLoginDTO;
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
