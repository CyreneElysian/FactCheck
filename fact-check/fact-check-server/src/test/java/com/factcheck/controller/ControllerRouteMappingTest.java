package com.factcheck.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制器路由表的反射断言测试（不启动 Spring 上下文）。
 *
 * <p>目的：把“操作日志页 404”这类前后端路径不一致的缺陷钉在路由表层面。
 * 之所以不用 @WebMvcTest：本项目 {@code WebMvcConfiguration extends WebMvcConfigurationSupport}
 * 且拦截器是 @Component，切片测试会因为上下文不完整而启动失败；
 * 而这里关心的是“注解上声明的路由表”，直接读注解既准确又快，且完全离线。</p>
 *
 * <p>锁定的关键路由契约：</p>
 * <ul>
 *   <li>{@code GET /admin/stats/logs} 必须存在（操作日志页真正调用的地址）；</li>
 *   <li>旧的 {@code /admin/logs} 不得由任何控制器提供（前端旧路径不应再存在后端接口）；</li>
 *   <li>{@code POST/PUT /admin/records/{id}/conclusion} 必须存在（人工诊断提交与修改结论）；</li>
 *   <li>{@code POST /admin/appeals/{id}/handle} 必须存在（申诉处理）。</li>
 * </ul>
 */
class ControllerRouteMappingTest {

    /** 需要纳入路由表统计的控制器包 */
    private static final String[] CONTROLLER_PACKAGES = {
            "com.factcheck.controller.admin",
            "com.factcheck.controller.user"
    };

    /**
     * 显式类名清单：作为目录扫描的兜底，保证即使扫描失败（例如资源不是目录形式），
     * 下面的“必须存在/必须不存在”断言依然有效。
     */
    private static final String[] KNOWN_CONTROLLERS = {
            "com.factcheck.controller.admin.AdminAccountController",
            "com.factcheck.controller.admin.AdminAppealController",
            "com.factcheck.controller.admin.AdminAuthorityController",
            "com.factcheck.controller.admin.AdminPendingController",
            "com.factcheck.controller.admin.AdminProfileController",
            "com.factcheck.controller.admin.AdminRecordController",
            "com.factcheck.controller.admin.AdminStatsController",
            "com.factcheck.controller.user.AppealController",
            "com.factcheck.controller.user.AuthorityController",
            "com.factcheck.controller.user.CommonController",
            "com.factcheck.controller.user.DetectController",
            "com.factcheck.controller.user.FavoriteController",
            "com.factcheck.controller.user.HotController",
            "com.factcheck.controller.user.MessageController",
            "com.factcheck.controller.user.OcrController",
            "com.factcheck.controller.user.RatingController",
            "com.factcheck.controller.user.UserController"
    };

    /**
     * 汇总所有控制器的 "HTTP方法 + 完整路径" 组合。
     * 类级 @RequestMapping 作为前缀，与方法级注解拼接（方法级为空时直接使用类级路径）。
     */
    private static Set<String> collectRoutes() {
        Set<String> routes = new LinkedHashSet<>();
        for (Class<?> controller : controllers()) {
            String basePath = join("", firstPath(controller.getAnnotation(RequestMapping.class)));
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                addRoute(routes, basePath, method.getAnnotation(GetMapping.class), "GET");
                addRoute(routes, basePath, method.getAnnotation(PostMapping.class), "POST");
                addRoute(routes, basePath, method.getAnnotation(PutMapping.class), "PUT");
                addRoute(routes, basePath, method.getAnnotation(DeleteMapping.class), "DELETE");
                addRoute(routes, basePath, method.getAnnotation(PatchMapping.class), "PATCH");

                // 兼容方法级 @RequestMapping（本项目暂未使用，但避免漏统计）
                RequestMapping methodMapping = method.getAnnotation(RequestMapping.class);
                if (methodMapping != null) {
                    String path = join(basePath, firstPath(methodMapping));
                    RequestMethod[] requestMethods = methodMapping.method();
                    if (requestMethods.length == 0) {
                        routes.add("ANY " + path);
                    } else {
                        for (RequestMethod requestMethod : requestMethods) {
                            routes.add(requestMethod.name() + " " + path);
                        }
                    }
                }
            }
        }
        return routes;
    }

    /**
     * 收集控制器类：目录扫描（能自动发现新增控制器）+ 显式清单（兜底）。
     */
    private static List<Class<?>> controllers() {
        List<Class<?>> classes = new ArrayList<>();
        for (String pkg : CONTROLLER_PACKAGES) {
            for (String className : scanClassNames(pkg)) {
                Class<?> clazz = tryLoad(className);
                if (clazz != null && isController(clazz) && !classes.contains(clazz)) {
                    classes.add(clazz);
                }
            }
        }
        for (String className : KNOWN_CONTROLLERS) {
            Class<?> clazz = tryLoad(className);
            if (clazz != null && !classes.contains(clazz)) {
                classes.add(clazz);
            }
        }
        return classes;
    }

    /** 从 classpath 目录里扫描出包下的顶层类名 */
    private static List<String> scanClassNames(String pkg) {
        List<String> names = new ArrayList<>();
        URL url = ControllerRouteMappingTest.class.getClassLoader().getResource(pkg.replace('.', '/'));
        if (url == null || !"file".equals(url.getProtocol())) {
            return names;
        }
        File directory;
        try {
            directory = new File(url.toURI());
        } catch (Exception e) {
            // 路径含空格/中文等需要解码的场景兜底
            directory = new File(url.getPath().replace("%20", " "));
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return names;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".class") && !name.contains("$")) {
                names.add(pkg + "." + name.substring(0, name.length() - ".class".length()));
            }
        }
        return names;
    }

    private static Class<?> tryLoad(String className) {
        try {
            return Class.forName(className, false, ControllerRouteMappingTest.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isController(Class<?> clazz) {
        return clazz.isAnnotationPresent(RestController.class) || clazz.isAnnotationPresent(Controller.class);
    }

    /** 把某个方法级映射注解登记进路由集合 */
    private static void addRoute(Set<String> routes, String basePath, Object mapping, String httpMethod) {
        String path = null;
        if (mapping instanceof GetMapping) {
            path = firstPath((GetMapping) mapping);
        } else if (mapping instanceof PostMapping) {
            path = firstPath((PostMapping) mapping);
        } else if (mapping instanceof PutMapping) {
            path = firstPath((PutMapping) mapping);
        } else if (mapping instanceof DeleteMapping) {
            path = firstPath((DeleteMapping) mapping);
        } else if (mapping instanceof PatchMapping) {
            path = firstPath((PatchMapping) mapping);
        }
        if (path == null) {
            return;
        }
        routes.add(httpMethod + " " + join(basePath, path));
    }

    private static String firstPath(RequestMapping mapping) {
        if (mapping == null) {
            return "";
        }
        String[] values = mapping.value().length > 0 ? mapping.value() : mapping.path();
        return values.length > 0 ? values[0] : "";
    }

    private static String firstPath(GetMapping mapping) {
        String[] values = mapping.value().length > 0 ? mapping.value() : mapping.path();
        return values.length > 0 ? values[0] : "";
    }

    private static String firstPath(PostMapping mapping) {
        String[] values = mapping.value().length > 0 ? mapping.value() : mapping.path();
        return values.length > 0 ? values[0] : "";
    }

    private static String firstPath(PutMapping mapping) {
        String[] values = mapping.value().length > 0 ? mapping.value() : mapping.path();
        return values.length > 0 ? values[0] : "";
    }

    private static String firstPath(DeleteMapping mapping) {
        String[] values = mapping.value().length > 0 ? mapping.value() : mapping.path();
        return values.length > 0 ? values[0] : "";
    }

    private static String firstPath(PatchMapping mapping) {
        String[] values = mapping.value().length > 0 ? mapping.value() : mapping.path();
        return values.length > 0 ? values[0] : "";
    }

    /** 拼接类级路径与方法级路径，并归一化重复斜杠、去掉末尾斜杠 */
    private static String join(String base, String sub) {
        String prefix = base == null ? "" : base.trim();
        String suffix = sub == null ? "" : sub.trim();
        if (prefix.isEmpty()) {
            return normalize(suffix.isEmpty() ? "/" : suffix);
        }
        if (suffix.isEmpty() || "/".equals(suffix)) {
            return normalize(prefix);
        }
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return normalize(prefix + suffix);
    }

    private static String normalize(String path) {
        String normalized = path.replaceAll("/{2,}", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? "/" : normalized;
    }

    /**
     * 路由表整体健壮性：必须有足够数量的路由被统计到，
     * 否则后面的“不在集合中”断言会是空洞的（空集合永远不包含旧路径）。
     */
    @Test
    @DisplayName("路由表非空且数量合理（防止扫描失败导致后续断言空洞）")
    void shouldCollectEnoughRoutes() {
        Set<String> routes = collectRoutes();

        assertThat(routes).hasSizeGreaterThanOrEqualTo(40);
        assertThat(routes).allSatisfy(route -> assertThat(route).contains(" /"));
    }

    /**
     * 操作日志页调用的真实地址必须是 GET /admin/stats/logs。
     */
    @Test
    @DisplayName("存在 GET /admin/stats/logs（操作日志页 404 缺陷的修复契约）")
    void shouldExposeAdminStatsLogsRoute() {
        Set<String> routes = collectRoutes();

        assertThat(routes).contains("GET /admin/stats/logs");
        // 同一控制器下的概览接口作为对照，确认前缀拼接正确
        assertThat(routes).contains("GET /admin/stats/overview");
    }

    /**
     * 旧的 /admin/logs 路径不得再被任何控制器提供：
     * 前端旧地址调用它必须 404，而不是落到别的接口上。
     */
    @Test
    @DisplayName("不存在 /admin/logs 旧路径路由（前端旧路径不应由后端提供）")
    void shouldNotExposeLegacyAdminLogsRoute() {
        Set<String> routes = collectRoutes();

        List<String> legacy = new ArrayList<>();
        for (String route : routes) {
            String path = route.substring(route.indexOf(' ') + 1);
            if ("/admin/logs".equals(path) || path.startsWith("/admin/logs/")) {
                legacy.add(route);
            }
        }
        assertThat(legacy).as("旧路径 /admin/logs 不应再注册任何 HTTP 方法").isEmpty();
    }

    /**
     * 人工诊断的“提交结论”与“修改历史结论”必须分别挂在 POST / PUT 上。
     */
    @Test
    @DisplayName("存在 POST 与 PUT /admin/records/{id}/conclusion")
    void shouldExposeConclusionRoutes() {
        Set<String> routes = collectRoutes();

        assertThat(routes).contains("POST /admin/records/{id}/conclusion");
        assertThat(routes).contains("PUT /admin/records/{id}/conclusion");
    }

    /**
     * 申诉处理必须挂在 POST /admin/appeals/{id}/handle 上。
     */
    @Test
    @DisplayName("存在 POST /admin/appeals/{id}/handle")
    void shouldExposeAppealHandleRoute() {
        Set<String> routes = collectRoutes();

        assertThat(routes).contains("POST /admin/appeals/{id}/handle");
    }

    /**
     * 扫到的控制器数量必须覆盖显式清单，避免“新控制器没被纳入路由表”这类静默遗漏。
     */
    @Test
    @DisplayName("控制器清单完整（显式清单里的类都能被加载）")
    void shouldLoadAllKnownControllers() {
        List<Class<?>> classes = controllers();

        assertThat(classes).hasSizeGreaterThanOrEqualTo(KNOWN_CONTROLLERS.length);
        assertThat(Arrays.stream(KNOWN_CONTROLLERS).map(ControllerRouteMappingTest::tryLoad))
                .doesNotContainNull();
    }
}
