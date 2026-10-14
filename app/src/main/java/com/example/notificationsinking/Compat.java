package com.example.notificationsinking;

import android.content.Context;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 兼容层小工具：
 *  - {@link #appContext()}：不依赖传统 AndroidAppHelper，优先反射 ActivityThread.currentApplication()；
 *  - {@link #tryClaim(String)}：防止现代/传统双入口在同一进程里重复安装 Hook。
 */
public final class Compat {

    private static final Set<String> CLAIMED = ConcurrentHashMap.newKeySet();
    private static volatile Context sAppContext;

    private Compat() {
    }

    /** 当前进程的 Application（取不到返回 null，调用方需判空）。 */
    public static Context appContext() {
        Context cached = sAppContext;
        if (cached != null) return cached;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sAppContext = (Context) app;
                return sAppContext;
            }
        } catch (Throwable ignored) {
        }
        try {
            // 传统框架兜底（反射调用，LSPosed 2.x 不再提供该类，缺失时静默）
            Class<?> helper = Class.forName("android.app.AndroidAppHelper");
            Object legacy = helper.getMethod("currentApplication").invoke(null);
            if (legacy instanceof Context) {
                sAppContext = (Context) legacy;
                return sAppContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 同一进程内只允许一个入口安装 Hook；重复调用返回 false。 */
    public static boolean tryClaim(String key) {
        return CLAIMED.add(key);
    }

    /** 启动阶段的一次性信息（现代入口）。 */
    public static String describeFramework(Object modernModule) {
        try {
            Class<?> iface = Class.forName("io.github.libxposed.api.XposedInterface");
            Object api = iface.getMethod("getApiVersion").invoke(modernModule);
            Object name = iface.getMethod("getFrameworkName").invoke(modernModule);
            Object version = iface.getMethod("getFrameworkVersion").invoke(modernModule);
            return "api=" + api + ", framework=" + name + " " + version;
        } catch (Throwable t) {
            return "framework info unavailable: " + t;
        }
    }
}
