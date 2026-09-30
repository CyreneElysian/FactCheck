package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 用户/管理员登录入参
 */
@Data
public class UserLoginDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String username;
    private String password;
}

