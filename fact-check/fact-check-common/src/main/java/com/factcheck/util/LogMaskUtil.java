package com.factcheck.util;

import java.util.Collection;
import java.util.Map;

/**
 * 日志脱敏工具（零依赖、纯函数、可单测）
 *
 * <h3>为什么需要它</h3>
 * 事实检测平台的核心输入就是**用户提交的待核查文本**（可能包含病情、身份、纠纷等敏感信息），
 * 而这些内容如果被整段打进应用日志，就等于把它们复制进了一个长期留存、
 * 通常还会被采集到集中式日志系统的位置——这既不是用户授权的用途，也难以撤回。
 *
 * <p>审计发现的具体问题：</p>
 * <ul>
 *   <li>{@code AgentClient} 把发给 Python Agent 的整个请求体（含用户全文）与整个响应体
 *       （含推理链与证据）打成 info 日志；</li>
 *   <li>{@code UserServiceImpl} 打印完整的微信 {@code code2session} 响应
 *       （含 {@code openid} / {@code session_key} / {@code unionid}）；</li>
 *   <li>{@code OcrServiceImpl} 打印 OCR 识别结果（图片里的文字）。</li>
 * </ul>
 *
 * <h3>策略</h3>
 * 采用"**只留可运维所需的最小信息**"：
 * <ul>
 *   <li>{@link #preview(String)}：短文本原样、长文本只留前 N 字符 + 长度。
 *       保留少量前缀是为了能判断"这条日志对应哪次请求"，而不是为了看清内容。</li>
 *   <li>{@link #keysOfJson(String)}：只输出 JSON 的**字段名**，不输出取值——
 *       排查契约问题（字段有没有、叫什么）足够了。</li>
 *   <li>{@link #maskSensitiveJsonFields(String, String...)}：对指定字段的值打码，
 *       用于 {@code openid} / {@code session_key} 这类"必须看一眼才能确认流程对不对、
 *       但绝不能整段留档"的值（保留首尾各几个字符，足以比对两次调用是否同一个用户）。</li>
 * </ul>
 */
public final class LogMaskUtil {

    /** 文本预览保留的最大字符数 */
    public static final int PREVIEW_MAX = 40;

    /** 打码时保留的首尾字符数 */
    private static final int MASK_KEEP = 4;

    private LogMaskUtil() {
    }

    /**
     * 文本预览：null 原样返回 null；不超过 {@link #PREVIEW_MAX} 字符的原样返回，
     * 超出则截断并追加以总长度，便于"看得出是长文本"。
     */
    public static String preview(String text) {
        if (text == null) {
            return null;
        }
        if (text.length() <= PREVIEW_MAX) {
            return text;
        }
        return text.substring(0, PREVIEW_MAX) + "…(共 " + text.length() + " 字符)";
    }

    /**
     * 单值打码：保留首尾各 {@value #MASK_KEEP} 个字符，中间以 {@code ***} 代替。
     * 长度不足时全部打码，避免"看起来像打码其实等于原文"。
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() <= MASK_KEEP * 2) {
            return "***";
        }
        return value.substring(0, MASK_KEEP) + "***" + value.substring(value.length() - MASK_KEEP);
    }

    /**
     * 列出 JSON 字符串的顶层字段名（不输出任何取值）
     *
     * <p>解析失败时退化为 {@code preview}，避免因为"日志格式化"本身抛异常。
     * 这里刻意不引入 JSON 库：本工具在 common 模块，而 common 不依赖 fastjson。</p>
     */
    public static String keysOfJson(String json) {
        if (json == null) {
            return null;
        }
        java.util.List<String> keys = new java.util.ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                    // 紧跟冒号说明这是一个"键"（depth==1 表示处于最外层对象内）
                    int j = i + 1;
                    while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
                        j++;
                    }
                    if (depth == 1 && j < json.length() && json.charAt(j) == ':') {
                        keys.add(token.toString());
                    }
                    token.setLength(0);
                    continue;
                }
                token.append(c);
                continue;
            }
            if (c == '"') {
                inString = true;
                token.setLength(0);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return keys.isEmpty() ? preview(json) : keys.toString();
    }

    /**
     * 对 JSON 里指定字段的值打码（用于 openid / session_key 这类必须比对、但不能留档的值）
     *
     * <p>实现是**字符串级替换**而非 JSON 解析：本工具在 common 模块、不引入 JSON 库。
     * 只处理形如 {@code "field":"value"} 的简单字符串值，够用且没有引入解析失败的风险。</p>
     */
    public static String maskSensitiveJsonFields(String json, String... fieldNames) {
        if (json == null || fieldNames == null || fieldNames.length == 0) {
            return json;
        }
        String result = json;
        for (String field : fieldNames) {
            if (field == null || field.isEmpty()) {
                continue;
            }
            String pattern = "\"" + field + "\"";
            int from = 0;
            while (true) {
                int keyAt = result.indexOf(pattern, from);
                if (keyAt < 0) {
                    break;
                }
                int colon = result.indexOf(':', keyAt + pattern.length());
                if (colon < 0) {
                    break;
                }
                int quoteStart = result.indexOf('"', colon + 1);
                if (quoteStart < 0) {
                    break;
                }
                int quoteEnd = quoteStart + 1;
                while (quoteEnd < result.length()) {
                    if (result.charAt(quoteEnd) == '\\') {
                        quoteEnd += 2;
                        continue;
                    }
                    if (result.charAt(quoteEnd) == '"') {
                        break;
                    }
                    quoteEnd++;
                }
                if (quoteEnd >= result.length()) {
                    break;
                }
                String raw = result.substring(quoteStart + 1, quoteEnd);
                String masked = mask(raw);
                result = result.substring(0, quoteStart + 1) + masked + result.substring(quoteEnd);
                from = quoteStart + 1 + masked.length() + 1;
            }
        }
        return result;
    }

    /**
     * 集合规模描述（不输出元素内容），用于"日志里想说明收到了几条"的场景
     */
    public static String sizeOf(Collection<?> collection) {
        return collection == null ? "null" : (collection.size() + " 条");
    }

    /**
     * Map 规模描述（不输出键值内容）
     */
    public static String sizeOf(Map<?, ?> map) {
        return map == null ? "null" : (map.size() + " 项");
    }
}
