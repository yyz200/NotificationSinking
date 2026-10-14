package com.example.notificationsinking;

import android.util.Log;

/**
 * 统一日志（双入口兼容版）。
 *
 * - 现代（libxposed）入口：通过 {@link #attachModern(Object)} 注入 XposedModule 实例后，
 *   日志走框架的 log(priority, tag, msg)；
 * - 传统入口：XposedBridge.log（类不存在时静默忽略）；
 * - 两者都写 android.util.Log.e —— ColorOS 会丢弃 Log.d/v/i/w，必须用 error 级别。
 *
 * 排查命令：adb logcat -s NotificationSinking:E
 */
public final class HookLog {

    public static final String TAG = "NotificationSinking";

    /** 详细日志开关。 */
    public static boolean VERBOSE = true;

    /** 现代入口的 XposedInterface（反射调用 log，避免编译期强耦合）。 */
    private static volatile Object sModern;
    private static volatile java.lang.reflect.Method sModernLog;

    private HookLog() {
    }

    /** 现代入口启动时注入 XposedModule（其实现 XposedInterface.log）。 */
    public static void attachModern(Object module) {
        try {
            sModern = module;
            sModernLog = module.getClass().getMethod("log", int.class, String.class, String.class);
        } catch (Throwable ignored) {
            sModern = null;
            sModernLog = null;
        }
    }

    public static void i(String msg) {
        if (!VERBOSE) return;
        write(Log.ERROR, msg);
    }

    public static void e(String msg) {
        write(Log.ERROR, "[E] " + msg);
    }

    public static void e(String msg, Throwable t) {
        write(Log.ERROR, "[E] " + msg + " :: " + t);
    }

    private static void write(int priority, String msg) {
        // 1) 现代框架日志通道（libxposed XposedModule.log，可用时）
        Object modern = sModern;
        java.lang.reflect.Method logMethod = sModernLog;
        if (modern != null && logMethod != null) {
            try {
                logMethod.invoke(modern, priority, TAG, msg);
            } catch (Throwable ignored) {
            }
        } else {
            // 2) 传统框架日志通道（反射调用，LSPosed 2.x 不再提供该类，缺失时静默）
            try {
                Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge");
                bridge.getMethod("log", String.class).invoke(null, TAG + ": " + msg);
            } catch (Throwable ignored) {
            }
        }
        // 3) logcat（ColorOS 只保留 error 级别）
        try {
            Log.println(priority, TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
