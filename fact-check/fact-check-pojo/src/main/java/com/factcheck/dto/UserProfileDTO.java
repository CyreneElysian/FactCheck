package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 修改用户资料入参
 */
@Data
public class UserProfileDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String nickname;
    private String avatar;
}

