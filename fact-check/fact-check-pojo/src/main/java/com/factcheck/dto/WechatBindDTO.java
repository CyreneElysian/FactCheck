package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 微信小程序绑定 Web 账号入参
 */
@Data
public class WechatBindDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String code;
    private String username;
    private String password;
}

