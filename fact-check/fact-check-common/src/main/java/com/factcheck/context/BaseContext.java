package com.factcheck.context;

/**
 * 基于 ThreadLocal 的上下文工具，用于在同一请求线程内传递当前登录用户/管理员 ID
 */
public class BaseContext {
    public static ThreadLocal<Long> threadLocal = new ThreadLocal<>();

    public static void setCurrentId(Long id) {
        threadLocal.set(id);
    }

    public static Long getCurrentId() {
        return threadLocal.get();
    }

    public static void removeCurrentId() {
        threadLocal.remove();
    }
}
