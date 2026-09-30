package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 微信小程序登录入参
 */
@Data
public class WechatLoginDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String code;
}

