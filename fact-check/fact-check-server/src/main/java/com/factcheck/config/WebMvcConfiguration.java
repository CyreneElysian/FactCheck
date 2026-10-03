package com.factcheck.config;

import com.factcheck.interceptor.JwtTokenAdminInterceptor;
import com.factcheck.interceptor.JwtTokenUserInterceptor;
import com.factcheck.json.JacksonObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC 配置：注册用户端/管理端 JWT 拦截器，并统一 JSON 序列化格式
 *
 * <p><b>为什么是 implements WebMvcConfigurer 而不是 extends WebMvcConfigurationSupport（P5-49）</b>：
 * 继承 {@code WebMvcConfigurationSupport} 会让 Spring Boot 的 MVC 自动配置**整体失效**
 * ——{@code spring.mvc.*} 配置项（含 springfox 需要的 {@code pathmatch.matching-strategy}）、
 * 默认静态资源、消息转换器约定等都不再生效；而且本类并没有 override
 * {@code extendMessageConverters}，导致 {@link JacksonObjectMapper} 一直是死代码，
 * 日期格式与 Long 精度约定实际未生效。改为实现 {@code WebMvcConfigurer} 后两者都恢复正常，
 * 且不需要 {@code @EnableWebMvc}（那会再次关掉自动配置）。</p>
 */
@Configuration
@Slf4j
public class WebMvcConfiguration implements WebMvcConfigurer {

    @Autowired
    private JwtTokenUserInterceptor jwtTokenUserInterceptor;
    @Autowired
    private JwtTokenAdminInterceptor jwtTokenAdminInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("注册 JWT 拦截器");
        registry.addInterceptor(jwtTokenAdminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/login");
        registry.addInterceptor(jwtTokenUserInterceptor)
                .addPathPatterns("/user/**", "/detect/**", "/hot/**", "/appeal/**",
                        "/authority/**", "/favorite/**", "/rating/**", "/message/**", "/ocr/**")
                .excludePathPatterns("/user/login", "/user/register", "/user/wechat/login", "/user/wechat/bind");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // knife4j（接口文档）的静态资源
        registry.addResourceHandler("/doc.html").addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**").addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    /**
     * 把 {@link JacksonObjectMapper} 放到**最前**，覆盖 Boot 默认的 Jackson 转换器
     *
     * <p>这样 LocalDateTime 统一按 {@code yyyy-MM-dd HH:mm:ss} 输出、LocalDate 按
     * {@code yyyy-MM-dd} 输出，且忽略未知字段，与前端约定一致。</p>
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(new JacksonObjectMapper());
        converters.add(0, converter);
        log.info("已注册自定义 Jackson 消息转换器：LocalDateTime 格式 {}",
                JacksonObjectMapper.DEFAULT_DATE_TIME_FORMAT);
    }
}
