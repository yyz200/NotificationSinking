package com.example.notificationsinking;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 现代入口（libxposed API 101/102），注册于 assets/java_init.list。
 *
 * 为什么同时提供现代与传统两个入口：
 *  - 新版 LSPosed（libxposed 时代）只实例化 java_init.list 中的 XposedModule 子类；
 *  - 旧版 LSPosed 只读 assets/xposed_init 中的传统 IXposedHookLoadPackage；
 *  两个入口同时声明可覆盖两种框架，且用 {@link Compat#tryClaim} 保证同进程只安装一次 Hook。
 *
 * 分发规则：
 *  - com.android.systemui  → 锁屏岛/胶囊下沉 + AOD 下沉
 *  - com.oplus.exsystemui  → 同上（老固件承载进程）
 *  - com.oplus.aod         → 旧版独立 AOD 进程
 *  - 其它包（含 system_server）一律不处理。
 */
public class ModernEntry extends XposedModule {

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_EXSYSTEMUI = "com.oplus.exsystemui";
    private static final String PKG_AOD = "com.oplus.aod";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        try {
            HookLog.attachModern(this);
            HookLog.i("ModernEntry: module loaded [" + Compat.describeFramework(this)
                    + ", systemServer=" + param.isSystemServer()
                    + ", process=" + param.getProcessName() + "]");
            try {
                ConfigStore.setRemotePreferences(getRemotePreferences(ConfigStore.PREFS_NAME));
            } catch (Throwable t) {
                HookLog.i("ModernEntry: remote preferences unavailable (" + t + ")");
            }
        } catch (Throwable t) {
            HookLog.e("ModernEntry: onModuleLoaded failed", t);
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        try {
            String pkg = param.getPackageName();
            boolean systemui = PKG_SYSTEMUI.equals(pkg);
            boolean exsystemui = PKG_EXSYSTEMUI.equals(pkg);
            boolean aod = PKG_AOD.equals(pkg);
            if (!systemui && !exsystemui && !aod) return;

            HookLog.i("ModernEntry: package loaded " + pkg);
            ConfigStore.ensureInitialized();

            if (!Compat.tryClaim("modern:" + pkg)) {
                HookLog.i("ModernEntry: hooks already installed for " + pkg + " (skip)");
                return;
            }

            ModernHooks hooks = new ModernHooks(
                    this,
                    pkg,
                    param.getDefaultClassLoader(),
                    systemui || exsystemui,
                    true);
            hooks.start();
            HookLog.i("ModernEntry: hooks ready for " + pkg
                    + ", installed=" + hooks.installedHandleCount());
        } catch (Throwable t) {
            HookLog.e("ModernEntry: onPackageLoaded failed", t);
        }
    }
}
