package com.factcheck.util;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * 取当前请求的客户端 IP（用于按 IP 限流）
 *
 * <p>生产部署是 nginx → Java，因此 {@code getRemoteAddr()} 拿到的是 nginx 的地址，
 * 必须优先读反向代理透传的 {@code X-Forwarded-For} / {@code X-Real-IP}
 * （nginx.conf 已配置 {@code proxy_set_header X-Real-IP / X-Forwarded-For}）。</p>
 *
 * <p><b>注意这里做了严格校验</b>：{@code X-Forwarded-For} 是客户端可伪造的请求头，
 * 若不加校验就把它拼进 Redis key，攻击者可以用任意值制造海量 key 把内存打满。
 * 因此只接受"看起来确实是 IP"的值（IPv4/IPv6 形态且有长度上限），
 * 否则退回到 {@code getRemoteAddr()}。取不到时返回 {@code null}，调用方跳过 IP 维度即可。</p>
 */
public final class ClientIpUtils {

    private static final String UNKNOWN = "unknown";

    /**
     * IPv6 最长 45 字符（含 IPv4 映射形式）
     */
    private static final int MAX_IP_LENGTH = 45;

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3}$");

    /**
     * IPv6：要求含冒号，只允许十六进制字符、冒号与点（覆盖 :: 压缩与 IPv4 映射写法）
     */
    private static final Pattern IPV6 = Pattern.compile("^(?=.*:)[0-9a-fA-F:.]{2,45}$");

    private ClientIpUtils() {
    }

    /**
     * @return 客户端 IP；不在 HTTP 请求上下文中（如定时任务、单元测试、异步线程）时返回 null
     */
    public static String currentIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes)) {
            return null;
        }
        return resolve(((ServletRequestAttributes) attributes).getRequest());
    }

    /**
     * 从请求中解析客户端 IP（包级可见，便于单测直接构造 MockHttpServletRequest）
     */
    static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        // X-Forwarded-For 形如 "客户端, 代理1, 代理2"，第一个才是原始客户端
        String forwarded = firstValidIp(request.getHeader("X-Forwarded-For"));
        if (forwarded != null) {
            return forwarded;
        }
        String realIp = normalize(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }
        return normalize(request.getRemoteAddr());
    }

    private static String firstValidIp(String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        for (String part : header.split(",")) {
            String ip = normalize(part);
            if (ip != null) {
                return ip;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String ip = value.trim();
        if (ip.isEmpty() || UNKNOWN.equalsIgnoreCase(ip) || ip.length() > MAX_IP_LENGTH) {
            return null;
        }
        if (IPV4.matcher(ip).matches() || IPV6.matcher(ip).matches()) {
            return ip;
        }
        return null;
    }
}
