package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 修改密码入参
 */
@Data
public class PasswordEditDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String oldPassword;
    private String newPassword;
}

