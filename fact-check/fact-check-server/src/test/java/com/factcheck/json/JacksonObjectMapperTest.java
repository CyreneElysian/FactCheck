package com.factcheck.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JacksonObjectMapper} 与统一返回体格式测试（P5-49 / P5-57）
 *
 * <p>P5-49 的教训：{@code WebMvcConfiguration} 原先直接继承 {@code WebMvcConfigurationSupport}，
 * 既关掉了 Boot 的 MVC 自动配置，又没有 override {@code extendMessageConverters}，
 * 于是这个映射器一直是**死代码**——日期格式约定写了但从未生效。现在它被注册进消息转换器链，
 * 这里锁定它的实际行为。</p>
 */
class JacksonObjectMapperTest {

    private final JacksonObjectMapper mapper = new JacksonObjectMapper();

    @Test
    @DisplayName("LocalDateTime 序列化为 yyyy-MM-dd HH:mm:ss（前端按该格式解析）")
    void shouldSerializeLocalDateTimeWithConfiguredPattern() throws Exception {
        String json = mapper.writeValueAsString(
                Map.of("time", LocalDateTime.of(2026, 10, 3, 13, 24, 56)));

        assertThat(json).isEqualTo("{\"time\":\"2026-10-03 13:24:56\"}");
    }

    @Test
    @DisplayName("LocalDate 序列化为 yyyy-MM-dd")
    void shouldSerializeLocalDateWithConfiguredPattern() throws Exception {
        String json = mapper.writeValueAsString(Map.of("date", LocalDate.of(2026, 10, 3)));

        assertThat(json).isEqualTo("{\"date\":\"2026-10-03\"}");
    }

    @Test
    @DisplayName("反序列化同样接受该格式（前后端约定一致）")
    void shouldDeserializeConfiguredPattern() throws Exception {
        JsonNode node = mapper.readTree("{\"time\":\"2026-10-03 13:24:56\"}");

        LocalDateTime parsed = mapper.convertValue(node.get("time").asText(), LocalDateTime.class);

        assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 10, 3, 13, 24, 56));
    }

    @Test
    @DisplayName("忽略未知字段（前端多传字段不应导致 400）")
    void shouldIgnoreUnknownProperties() throws Exception {
        Map<String, Object> value = mapper.readValue(
                "{\"known\":1,\"unknownField\":\"x\"}", mapper.constructType(HashMap.class));

        assertThat(value).containsEntry("known", 1);
    }
}
