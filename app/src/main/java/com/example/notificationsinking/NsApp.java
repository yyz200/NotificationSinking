package com.example.notificationsinking;

import android.app.Application;
import android.content.SharedPreferences;

/**
 * 模块 App 进程入口：进程一启动就
 *  1) 打一条可辨识日志（确认新版代码确实运行，便于排查）；
 *  2) 注册 libxposed 服务监听（现代框架配置通道）；
 *  3) 把当前配置写入兜底文件通道（root）。
 *
 * 放在 Application 而非 Activity，是为了让配置写入不依赖用户是否打开设置界面。
 */
public class NsApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            HookLog.i("NsApp: process started, version=" + versionName());
        } catch (Throwable ignored) {
        }
        try {
            SharedPreferences prefs = getSharedPreferences(ConfigStore.PREFS_NAME, MODE_PRIVATE);
            migratePrefs(prefs);
            RemotePrefsBridge.init(prefs);
            new Thread(() -> ConfigWriter.writeFromPrefs(this, prefs), "ns-config-seed").start();
        } catch (Throwable t) {
            HookLog.e("NsApp: init failed", t);
        }
    }

    /**
     * 偏好迁移：旧版本把「视觉模式」开关的状态存成了 gesture_safe=true，
     * 新版本它变成「手势优先」，会继承成开启并把下沉上限锁在 45%（表现为"调 dp 不动"）。
     * 这里一次性复位为推荐值。
     */
    private void migratePrefs(SharedPreferences prefs) {
        try {
            int v = prefs.getInt(ConfigStore.KEY_PREFS_VERSION, 1);
            if (v >= 5) return;
            prefs.edit()
                    .putInt(ConfigStore.KEY_CLASSIC_CAP, 100)   // 不限制下沉
                    .putInt(ConfigStore.KEY_SINK_DP_MEDIA, -1)  // 有音乐卡片时自动让出高度
                    .putInt(ConfigStore.KEY_PREFS_VERSION, 5)
                    .apply();
            HookLog.i("NsApp: 偏好已迁移到 v5（classic_cap_pct=100, sink_dp_media=-1）");
        } catch (Throwable ignored) {
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
