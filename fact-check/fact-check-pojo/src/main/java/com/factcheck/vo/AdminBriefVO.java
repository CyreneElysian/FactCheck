package com.factcheck.vo;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员简要信息（P3-35：转派下拉框的数据源）
 *
 * <p>只暴露 id / username / nickname —— 转派只需要选人，不需要看到手机号等敏感字段。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminBriefVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String nickname;
}
