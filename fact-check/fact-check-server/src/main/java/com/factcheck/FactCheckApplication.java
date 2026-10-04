package com.factcheck;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 事实检测项目启动类
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
@Slf4j
public class FactCheckApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FactCheckApplication.class);

        // 启动时打印生效的 profile 与解析后的配置，便于快速定位配置问题：
        //   - dbUrl 为 null 或仍带 ${...} → 对应的 application-<profile>.yml 没被加载
        //   - ossBucket 为空 → 说明 application-local.yml 没生效（本地密钥没读到）
        //   - 常见原因：本地密钥文件不在 src/main/resources，或构建时把它排除掉了
        //     （见 fact-check-server/pom.xml 中 dist profile 的说明）
        // 注意：数据源的键是 spring.datasource.druid.*（在 application.yml 里拼装），
        //      而不是 fact-check.datasource.*（那只是分项配置）。
        app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            ConfigurableEnvironment env = event.getEnvironment();
            log.info("启动环境：activeProfiles={}, dbUrl={}, dbUsername={}, ossBucket={}",
                    Arrays.toString(env.getActiveProfiles()),
                    env.getProperty("spring.datasource.druid.url"),
                    env.getProperty("spring.datasource.druid.username"),
                    env.getProperty("fact-check.alioss.bucket-name"));
            validateJwtSecrets(env);
        });

        app.run(args);
        log.info("fact-check server started");
    }

    /**
     * 校验两个 JWT 密钥是否足够强（HS256 要求 ≥ 256 位 = 32 字节）
     *
     * <p><b>为什么要在启动时校验</b>：容器化部署里常见一种静默失败——
     * compose 用 {@code FACTCHECK_JWT_USER_SECRET: ${...:-}} 注入，变量未设置时传入的是
     * <b>空字符串</b>；而 Spring 对"已定义但为空"的环境变量取空值、**不会**回落到
     * {@code application.yml} 里的默认密钥。于是密钥变成空串，jjwt 0.12.x 在**签发 token**
     * 时才抛 {@code WeakKeyException}：表现是"密码正确但登录返回未知错误"，
     * 而且此时密码校验、写 last_login_time 都已成功，极难定位。</p>
     *
     * <p>放在 {@link ApplicationEnvironmentPreparedEvent} 阶段做，是为了在**应用启动前**就失败，
     * 而不是等第一次有人登录才发现。</p>
     */
    private static void validateJwtSecrets(ConfigurableEnvironment env) {
        // 与 JwtUtil.MIN_KEY_BYTES 保持一致（那里是包级可见，此处不跨模块引用）
        final int minBytes = 32;
        String[] keys = {"fact-check.jwt.user-secret-key", "fact-check.jwt.admin-secret-key"};
        for (String key : keys) {
            String value = env.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalStateException(String.format(
                        "配置错误：%s 为空或未设置。JWT 密钥必须 ≥%d 字节（HS256 要求）。"
                                + "若用 docker compose 部署，请在宿主机设置 FACTCHECK_JWT_USER_SECRET / "
                                + "FACTCHECK_JWT_ADMIN_SECRET（生成：openssl rand -hex 32）。"
                                + "注意：把变量设成空字符串**不会**回落到 application.yml 的默认密钥。",
                        key, minBytes));
            }
            int bytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes < minBytes) {
                throw new IllegalStateException(String.format(
                        "配置错误：%s 长度不足（当前 %d 字节，要求 ≥%d 字节）。"
                                + "jjwt 0.12.x 会拒绝过短的 HMAC 密钥，登录时签发 token 会失败。"
                                + "生成：openssl rand -hex 32（得到 64 个十六进制字符 = 64 字节）。",
                        key, bytes, minBytes));
            }
        }
        log.info("JWT 密钥校验通过：user/admin 两个密钥均满足 ≥{} 字节", minBytes);
    }
}
