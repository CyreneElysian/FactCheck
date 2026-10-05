package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 用户注册入参
 */
@Data
public class UserRegisterDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String username;
    private String password;
    private String nickname;
    /**
     * wx.login 返回的 code（**可选**，仅小程序端使用）。
     *
     * <p>带上时表示"小程序端创建新账号并绑定当前微信"：后端会在**同一事务**里
     * 完成注册 + 绑定，并直接返回 token（省掉一次登录）。</p>
     *
     * <p>Web 端注册不带该字段，走原路径，行为不变。</p>
     */
    private String code;
}

