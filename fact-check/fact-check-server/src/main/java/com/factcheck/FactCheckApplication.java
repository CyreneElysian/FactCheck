package com.factcheck;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

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

        // 启动时打印生效的 profile 与解析后的数据源地址，便于快速定位配置问题：
        //   - 只看得到 profile 而 dbUrl 是 null / 仍带 ${...} → 说明对应的 application-<profile>.yml 没被加载
        //   - 常见原因：本地密钥文件 application-local.yml 未放到 src/main/resources，
        //     或构建时把该文件排除掉了（见 fact-check-server/pom.xml 的 dist profile 说明）
        app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            ConfigurableEnvironment env = event.getEnvironment();
            log.info("启动环境：activeProfiles={}, dbUrl={}, dbUsername={}",
                    Arrays.toString(env.getActiveProfiles()),
                    env.getProperty("fact-check.datasource.url"),
                    env.getProperty("fact-check.datasource.username"));
        });

        app.run(args);
        log.info("fact-check server started");
    }
}
