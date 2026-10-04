package com.factcheck.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 待诊断队列配置，对应 {@code application.yml} 的 {@code fact-check.pending}
 *
 * <h3>为什么需要它</h3>
 * 处理时限原先写死在 {@code DetectServiceImpl} 里：
 * <pre>
 * private static final int PENDING_HOURS = 48;   // 硬编码，改不了
 * </pre>
 * 而产品文档 §4.7 / §8.1（S14）明确写的是
 * <b>「待诊断记录默认处理时限 48 小时（配置项可调）」</b> ——
 * 也就是说文档承诺了一个实际不存在的能力。部署方按文档去改配置会发现无处可改。
 *
 * <p>本类把这个时限提成配置项，与 S10 的其它可配置项（去重开关、文本长度阈值）
 * 保持同一风格：{@code application.yml} 里给默认值 + 支持环境变量覆盖。</p>
 */
@Component
@ConfigurationProperties(prefix = "fact-check.pending")
@Data
public class PendingProperties {

    /**
     * 待诊断记录的处理时限（小时），默认 48。
     *
     * <p>用于计算 {@code t_fact_check_record.deadline}：记录进入"待人工诊断"时
     * 置为「当前时间 + 本值」。前端据此把超时记录高亮为「已超时」。</p>
     *
     * <p>取值下限为 1 小时（见 {@code DetectServiceImpl} 的归一化处理）：
     * 配置成 0 或负数会让所有记录一进入队列就显示"已超时"，把提示变成噪音。</p>
     */
    private int timeoutHours = 48;
}
