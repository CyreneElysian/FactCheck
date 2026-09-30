package com.factcheck;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 事实检测项目启动类
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
@Slf4j
public class FactCheckApplication {
    public static void main(String[] args) {
        SpringApplication.run(FactCheckApplication.class, args);
        log.info("fact-check server started");
    }
}
