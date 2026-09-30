package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 管理员创建账号入参
 */
@Data
public class AdminAccountCreateDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String username;
    private String password;
    private String nickname;
}

