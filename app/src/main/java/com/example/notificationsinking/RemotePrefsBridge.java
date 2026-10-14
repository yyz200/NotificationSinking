package com.example.notificationsinking;

import android.content.SharedPreferences;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 现代框架（LSPosed 2.x）配置通道桥。
 *
 * 现代 API 下，模块 App 与框架的官方通信方式：模块 App 注册服务监听，框架启动后把
 * XposedService 下发给 App；App 通过 service.getRemotePreferences(group) 把配置写入
 * LSPosed 数据库；被 Hook 的 SystemUI 进程用 XposedModule.getRemotePreferences(同一 group)
 * 读取并监听变化 —— 这就是官方文档中的 Remote Preferences（支持变更监听）。
 *
 * 为什么需要它：在 SystemUI 进程里取 Application Context 需要隐藏 API
 * （ActivityThread.currentApplication 被 Android 16 拦截），ContentProvider 通道
 * 在现代框架下拿不到 Context；Remote Preferences 不依赖任何 Context。
 */
public final class RemotePrefsBridge {

    private static volatile XposedService sService;
    private static volatile SharedPreferences sRemote;
    private static volatile SharedPreferences sLocal;

    private RemotePrefsBridge() {
    }

    /** 模块 App 启动时注册服务监听；框架可用时会回调 onServiceBind 并自动镜像一次。 */
    public static void init(SharedPreferences local) {
        sLocal = local;
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    sService = service;
                    try {
                        sRemote = service.getRemotePreferences(ConfigStore.PREFS_NAME);
                        HookLog.i("RemotePrefsBridge: service bound ("
                                + service.getFrameworkName() + " " + service.getFrameworkVersion()
                                + ", api=" + service.getApiVersion() + ")");
                    } catch (Throwable t) {
                        HookLog.e("RemotePrefsBridge: getRemotePreferences failed", t);
                    }
                    mirror();
                }

                @Override
                public void onServiceDied(XposedService service) {
                    sService = null;
                    sRemote = null;
                }
            });
            HookLog.i("RemotePrefsBridge: listener registered");
        } catch (Throwable t) {
            // 非现代框架（或类缺失）→ 静默，配置走 ContentProvider 通道
            HookLog.i("RemotePrefsBridge: unavailable (" + t + ")");
        }
    }

    /** 把本地配置镜像进框架远程偏好（每次 persist 时调用；Hook 侧据此实时生效）。 */
    public static void mirror() {
        SharedPreferences remote = sRemote;
        SharedPreferences local = sLocal;
        if (remote == null || local == null) return;
        try {
            remote.edit()
                    .putBoolean(ConfigStore.KEY_ENABLED, local.getBoolean(ConfigStore.KEY_ENABLED, false))
                    .putBoolean(ConfigStore.KEY_LOCKSCREEN, local.getBoolean(ConfigStore.KEY_LOCKSCREEN, true))
                    .putBoolean(ConfigStore.KEY_AOD, local.getBoolean(ConfigStore.KEY_AOD, true))
                    .putInt(ConfigStore.KEY_SINK_DP, local.getInt(ConfigStore.KEY_SINK_DP, ConfigStore.SINK_DP_DEFAULT))
                    .apply();
            HookLog.i("RemotePrefsBridge: mirrored enabled="
                    + local.getBoolean(ConfigStore.KEY_ENABLED, false)
                    + ", sinkDp=" + local.getInt(ConfigStore.KEY_SINK_DP, ConfigStore.SINK_DP_DEFAULT));
        } catch (Throwable t) {
            HookLog.e("RemotePrefsBridge: mirror failed", t);
        }
    }

    public static boolean isReady() {
        return sRemote != null;
    }
}
