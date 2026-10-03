package com.factcheck.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 管理员补充证据入参（P3-35）
 *
 * <p>人工诊断时 AI 可能没找到证据，或证据不足；管理员可以自行补充来源，
 * 这条证据会以 {@code source_type=2}（管理员补充）写入 {@code t_evidence}，
 * 与 AI 检索到的证据（{@code source_type=1}）区分开，前端也会据此标注来源。</p>
 */
@Data
public class EvidenceCreateDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 证据标题（必填，对应 t_evidence.title，VARCHAR(200)） */
    private String title;

    /** 原文链接（选填，必须是 http(s)） */
    private String url;

    /** 摘要（选填，对应 t_evidence.snippet） */
    private String snippet;
}
