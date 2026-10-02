package com.factcheck.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 分页接口的参数契约测试（P5-63 + P2-17）
 *
 * <p>锁定两件事：</p>
 * <ol>
 *   <li>分页参数必须有**默认值**——原先 {@code @RequestParam Integer page} 没有默认值，
 *       前端漏传就直接 400，而 Service 里明明写了 {@code page == null ? 1 : page} 的兜底；</li>
 *   <li>参数类型保持可空 Integer（校验交给 {@code ValidationUtils.requirePaging} 给中文提示）。</li>
 * </ol>
 *
 * <p>用反射读注解，完全离线。</p>
 */
class PaginationParamContractTest {

    /**
     * 四个分页接口：控制器类 → 方法名
     */
    private static final List<Class<?>> CONTROLLERS = Arrays.asList(
            com.factcheck.controller.admin.AdminAccountController.class,
            com.factcheck.controller.admin.AdminAuthorityController.class,
            com.factcheck.controller.admin.AdminStatsController.class,
            com.factcheck.controller.user.FavoriteController.class);

    @Test
    @DisplayName("所有分页接口的 page/pageSize 都必须声明默认值（1 / 10）")
    void shouldDeclareDefaultValuesForPagingParams() {
        int checked = 0;
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    RequestParam annotation = parameter.getAnnotation(RequestParam.class);
                    if (annotation == null) {
                        continue;
                    }
                    // 注意：写法是 @RequestParam(defaultValue = "1")，没有显式 name，
                    // 因此 annotation.name() 是空串，真实参数名要从字节码里取
                    // （Spring Boot 父 POM 默认开启 -parameters）
                    String name = annotation.name().isEmpty() ? parameter.getName() : annotation.name();
                    if (!"page".equals(name) && !"pageSize".equals(name)) {
                        continue;
                    }
                    assertThat(annotation.defaultValue())
                            .as("%s.%s 的参数 %s 必须声明默认值，否则前端漏传会直接 400",
                                    controller.getSimpleName(), method.getName(), name)
                            .isIn("1", "10");
                    checked++;
                }
            }
        }
        assertThat(checked)
                .as("应当检查到 4 个分页接口 × 2 个参数；若为 0 说明参数名不可用（未开启编译参数 -parameters）")
                .isEqualTo(8);
    }
}
