package com.example.notificationsinking;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * AOD 息屏通知下沉，v1.0.1 稳定版。
 *
 * 进程分布（ColorOS 15/16 已核实，参考 ColorOS_Aod_Enhance 模块）：
 *  - ColorOS 15/16：AOD 由 SystemUI 进程渲染，命名空间 com.oplus.systemui.aod.*
 *    （AodUpdateManager / SmoothTransitionController / AodRootLayout 等均在其中）；
 *  - 旧版 ColorOS（12/13）：独立 com.oplus.aod APK 进程。
 *  本类在两个进程中都会安装（MainHook 分发），候选类名覆盖两套命名空间。
 *
 * 路径（v1.0.1）：
 *  Tier-1 类名候选（AodNotificationView / AodNotificationContainer 等，View 子类）→ translationY 下沉。
 *  若全部不命中，请按 README §6 反编译本机固件补充实际类名；
 *  资源替换回退（ResourceHook）已从入口清单移除（风险收敛），如需启用见其类注释。
 */
public final class AodSinkHook extends BaseSinkHook {

    private static final String[] CANDIDATE_CLASSES = {
            // ColorOS 15/16：SystemUI 进程内的 AOD 通知
            "com.oplus.systemui.aod.notification.AodNotificationView",
            "com.oplus.systemui.aod.notification.OplusAodNotificationView",
            "com.oplus.systemui.aod.notification.AodNotificationContainer",
            "com.oplus.systemui.aod.notification.AodNotificationGroupView",
            "com.oplus.systemui.aod.notification.view.AodNotificationContentView",
            // 旧版 ColorOS：独立 com.oplus.aod APK
            "com.oplus.aod.notification.AodNotificationView",
            "com.oplus.aod.notification.OplusAodNotificationView",
            "com.oplus.aod.notification.AodNotificationContainer",
            "com.oplus.aod.view.AodNotificationView",
            "com.oplus.aod.OplusAodNotificationContainer",
    };

    public AodSinkHook(XC_LoadPackage.LoadPackageParam lpp) {
        super(lpp, "AodSink");
    }

    @Override
    protected boolean featureEnabled() {
        return ConfigStore.isAodEnabled();
    }

    @Override
    protected void attachHooks() {
        // Tier-1：视图类名候选（View 子类才会 hook，控制器类自动跳过）
        hookCandidateClasses(CANDIDATE_CLASSES);
    }
}
