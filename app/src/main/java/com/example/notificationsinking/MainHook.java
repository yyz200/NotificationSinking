package com.example.notificationsinking;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 传统入口（de.robv API）——**旧框架备用，现代入口清单中不声明**。
 *
 * v1.2.1 起模块以现代 API 形式发布：入口为 {@link ModernEntry}（META-INF/xposed/java_init.list）。
 * LSPosed 2.x 不再提供 XposedHelpers/XposedBridge 等传统类，也不会读取 assets/xposed_init，
 * 因此本类在 LSPosed 2.x 上不会被实例化；保留它只用于在旧框架（LSPosed 1.x）上手动启用
 * （把本类名写进 META-INF/xposed/java_init.list 会因新 API 要求而无效，需改用 legacy 布局）。
 *
 * v1.0.1 分发规则（收敛后）：
 *  - com.android.systemui      → 锁屏岛/胶囊下沉 + AOD 下沉
 *    （ColorOS 15/16 的 AOD 由 SystemUI 进程渲染，命名空间 com.oplus.systemui.aod.*）
 *  - com.oplus.aod             → 旧版 ColorOS 独立 AOD 进程
 *  - com.oplus.exsystemui      → ColorOS 12/13 时代的锁屏/AOD 承载进程（新版已并入 SystemUI）
 *
 * v1.0.0 曾注入 android（system_server）与 com.oplus.aodplugin：
 * 在 ColorOS 16 上实测与「重启后 CPU 高占用」相关（注入面过大），v1.0.1 已移除，
 * 本模块不需要任何 framework 进程注入。
 *
 * 安全约定：
 *  - 所有 Hook 默认关闭，用户开启后才安装；关闭时卸载 Hook 并还原布局；
 *  - 所有类查找使用 findClassIfExists，类不存在时静默跳过并写日志；
 *  - 任何异常都不会冒泡到目标进程。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_EXSYSTEMUI = "com.oplus.exsystemui";
    private static final String PKG_AOD = "com.oplus.aod";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        try {
            if (PKG_SYSTEMUI.equals(pkg)) {
                HookLog.i("MainHook: package loaded " + pkg);
                ConfigStore.ensureInitialized();
                new LockscreenSinkHook(lpparam).install();
                new AodSinkHook(lpparam).install();
            } else if (PKG_AOD.equals(pkg)) {
                HookLog.i("MainHook: package loaded " + pkg);
                ConfigStore.ensureInitialized();
                new AodSinkHook(lpparam).install();
            } else if (PKG_EXSYSTEMUI.equals(pkg)) {
                HookLog.i("MainHook: package loaded " + pkg);
                ConfigStore.ensureInitialized();
                new LockscreenSinkHook(lpparam).install();
                new AodSinkHook(lpparam).install();
            }
        } catch (Throwable t) {
            // 兜底：绝不让任何异常冒泡到目标进程
            HookLog.e("MainHook: fatal in handleLoadPackage(" + pkg + ")", t);
        }
    }
}
