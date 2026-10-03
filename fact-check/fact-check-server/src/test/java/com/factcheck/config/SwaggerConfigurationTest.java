package com.factcheck.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

/**
 * 接口文档配置测试（P2-26）
 *
 * <p>这里锁的是**容易被无意改坏的两件事**：</p>
 * <ol>
 *   <li>分组必须存在且互不重名（两个分组共用一个名字时，/v3/api-docs 只会返回其中一组，
 *       而且不会报错——属于"静默少了一半接口"的那类问题）；</li>
 *   <li>{@code spring.mvc.pathmatch.matching-strategy=ant_path_matcher} 必须保留：
 *       springfox 3.0.0 不支持 Boot 2.6+ 默认的 PathPatternParser，
 *       删掉这一项会让 /doc.html 与 /v3/api-docs 不可用。</li>
 * </ol>
 *
 * <p>完全离线：Docket 是普通对象，配置从 classpath 上的 application.yml 读取。</p>
 */
class SwaggerConfigurationTest {

    private final SwaggerConfiguration configuration = new SwaggerConfiguration();

    @Test
    @DisplayName("两个分组存在、互不重名，且都使用 OpenAPI 3.0")
    void shouldDeclareTwoDistinctGroups() {
        Docket user = configuration.userApiDocket();
        Docket admin = configuration.adminApiDocket();

        assertThat(user.getGroupName()).isEqualTo("用户端接口");
        assertThat(admin.getGroupName()).isEqualTo("管理端接口");
        assertThat(user.getGroupName()).isNotEqualTo(admin.getGroupName());
        assertThat(user.getDocumentationType()).isEqualTo(DocumentationType.OAS_30);
        assertThat(admin.getDocumentationType()).isEqualTo(DocumentationType.OAS_30);
    }

    @Test
    @DisplayName("application.yml 必须保留 springfox 兼容项与 /api 上下文路径")
    void shouldKeepSpringfoxCompatibilitySettings() {
        Map<String, Object> yaml = loadApplicationYml();

        Map<String, Object> spring = asMap(yaml.get("spring"));
        assertThat(asMap(asMap(spring.get("mvc")).get("pathmatch")).get("matching-strategy"))
                .as("springfox 3.0.0 配 Boot 2.6+ 必须使用 ant_path_matcher，否则接口文档不可用")
                .isEqualTo("ant_path_matcher");

        Map<String, Object> server = asMap(yaml.get("server"));
        assertThat(asMap(server.get("servlet")).get("context-path"))
                .as("文档地址为 /api/doc.html，依赖该上下文路径")
                .isEqualTo("/api");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadApplicationYml() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml 必须存在于 classpath").isNotNull();
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("读取 application.yml 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
