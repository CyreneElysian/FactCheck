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
        });

        app.run(args);
        log.info("fact-check server started");
    }
}
