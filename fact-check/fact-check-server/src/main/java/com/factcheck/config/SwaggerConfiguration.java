package com.factcheck.config;

import io.swagger.models.auth.In;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.ApiKey;
import springfox.documentation.service.AuthorizationScope;
import springfox.documentation.service.SecurityReference;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.service.contexts.SecurityContext;
import springfox.documentation.spring.web.plugins.Docket;

import java.util.Collections;
import java.util.List;

/**
 * 接口文档配置（P2-26，knife4j 3.0.2 + springfox 3.0.0）
 *
 * <p>访问地址：{@code http://localhost:8080/api/doc.html}（knife4j UI），
 * 原始 OpenAPI JSON：{@code /api/v3/api-docs}。</p>
 *
 * <p>按"用户端 / 管理端"分成两个分组，便于分别调试；两端的鉴权请求头不同
 * （用户端 {@code authentication}、管理端 {@code token}，见《技术方案》第 10 章），
 * 因此各自声明自己的 Authorization，UI 上可以直接填 token 调接口。</p>
 *
 * <p>注意：{@code WebMvcConfiguration} 里的拦截器只拦 {@code /admin/**}、{@code /user/**} 等业务前缀，
 * 文档路径不在其中，因此访问文档本身不需要登录；文档页面里的接口调用仍会走鉴权。</p>
 */
@Configuration
public class SwaggerConfiguration {

    private static final String USER_GROUP = "用户端接口";
    private static final String ADMIN_GROUP = "管理端接口";

    /**
     * 用户端接口文档
     */
    @Bean
    public Docket userApiDocket() {
        return new Docket(DocumentationType.OAS_30)
                .groupName(USER_GROUP)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.factcheck.controller.user"))
                .paths(PathSelectors.any())
                .build()
                .securitySchemes(Collections.singletonList(
                        new ApiKey("用户端 token", "authentication", In.HEADER.name())))
                .securityContexts(Collections.singletonList(
                        securityContext("用户端 token", "authentication")));
    }

    /**
     * 管理端接口文档
     */
    @Bean
    public Docket adminApiDocket() {
        return new Docket(DocumentationType.OAS_30)
                .groupName(ADMIN_GROUP)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.factcheck.controller.admin"))
                .paths(PathSelectors.any())
                .build()
                .securitySchemes(Collections.singletonList(
                        new ApiKey("管理端 token", "token", In.HEADER.name())))
                .securityContexts(Collections.singletonList(
                        securityContext("管理端 token", "token")));
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("事实检测平台 API")
                .description("AI + 人工的事实核查平台：文字/图片检测、语义去重、权威信息、人工诊断与申诉。"
                        + "鉴权：用户端请求头 authentication，管理端请求头 token。")
                .version("1.0")
                .build();
    }

    private SecurityContext securityContext(String name, String header) {
        List<SecurityReference> references = Collections.singletonList(
                new SecurityReference(name, new AuthorizationScope[]{
                        new AuthorizationScope("global", "全部接口")}));
        return SecurityContext.builder()
                .securityReferences(references)
                .operationSelector(operation -> true)
                .build();
    }
}
