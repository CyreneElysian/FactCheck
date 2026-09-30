package com.factcheck.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收藏实体，对应 t_user_favorite 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFavorite implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private Long recordId;
    private LocalDateTime createTime;
}

