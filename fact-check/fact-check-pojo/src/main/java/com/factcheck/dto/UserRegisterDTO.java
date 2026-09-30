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
}

