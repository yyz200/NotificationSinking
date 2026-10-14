package com.example.notificationsinking;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 锁屏通知下沉（ColorOS 16 锁屏岛 / 胶囊态 / 锁屏通知中心），v1.0.1 稳定版。
 *
 * 主路径（Tier-0）：ColorOS 16.0.x / 16.1.0 + Android 16 上经参考模块
 * com.rikumi.colorosmod「锁屏通知区域下移」功能实测核实的三个 Hook 点。
 * 锁屏通知区域顶部间距有三处来源，三处汇入 updateTopPadding 且参与内部差值计算，
 * 因此必须叠加同一偏移量：
 *  1. com.android.systemui.shade.NotificationPanelViewController
 *     #getKeyguardNotificationStaticPadding()
 *     —— 注意：非锁屏时原始返回 0，必须先校验 isKeyguardShowing() 再叠加；
 *  2. com.oplus.systemui.notification.lockscreen.stack.OplusLockscreenShadeTransitionControllerExImpl
 *     #getNtfTopPaddingInLockscreen()
 *  3. 同上类 #getNtfTopPaddingInLockscreenNtfCenter()
 *
 * 兜底路径（Tier-1）：按类名候选动态查找胶囊/锁屏通知视图（View 子类）→ translationY 下沉。
 *
 * v1.0.1 已移除：
 *  - 全局 View#onAttachedToWindow 特征兜底 Hook（高风险面，与 CPU 高占用相关）；
 *  - 控制器类触发方法 Hook（方法名是推测的，可能命中高频方法）。
 *
 * 所有类名均动态确认：findClassIfExists 找不到 → 静默日志 → 下一级回退。
 */
public final class LockscreenSinkHook extends BaseSinkHook {

    // ---- Tier-0：ColorOS 16 已核实的三件套 ----
    private static final String CLS_NOTIFICATION_PANEL_VC =
            "com.android.systemui.shade.NotificationPanelViewController";
    private static final String CLS_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL =
            "com.oplus.systemui.notification.lockscreen.stack."
                    + "OplusLockscreenShadeTransitionControllerExImpl";

    // ---- Tier-1：视图类名候选（用户提供 + 同类固件命名规律）----
    private static final String[] CANDIDATE_CLASSES = {
            "com.oplus.systemui.notification.capsule.CapsuleNotificationView",
            "com.oplus.systemui.notification.OplusNotificationCapsuleView",
            "com.oplus.systemui.notification.capsule.OplusCapsuleNotificationView",
            "com.oplus.systemui.lockscreen.notification.OplusLockScreenNotificationView",
            "com.oplus.systemui.lockscreen.notification.LockScreenNotificationView",
            "com.oplus.systemui.island.OplusIslandView",
            "com.oplus.systemui.island.OplusIslandNotificationView",
            "com.oplus.systemui.notification.lockscreen.OplusLockscreenNotificationContainer",
    };

    public LockscreenSinkHook(XC_LoadPackage.LoadPackageParam lpp) {
        super(lpp, "LockscreenSink");
    }

    @Override
    protected boolean featureEnabled() {
        return ConfigStore.isLockscreenEnabled();
    }

    @Override
    protected void attachHooks() {
        // Tier-0：通知区域顶部间距三件套（ColorOS 16 主路径）
        // 跨入口去重：现代/传统两个入口若同时被加载，只允许其一安装三件套（避免偏移叠加两次）
        if (Compat.tryClaim("padding:" + mLpp.packageName)) {
            hookTopPaddingMethod(CLS_NOTIFICATION_PANEL_VC,
                    "getKeyguardNotificationStaticPadding", true);
            hookTopPaddingMethod(CLS_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL,
                    "getNtfTopPaddingInLockscreen", false);
            hookTopPaddingMethod(CLS_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL,
                    "getNtfTopPaddingInLockscreenNtfCenter", false);
        } else {
            HookLog.i(mTag + ": padding hooks already installed by another entry (skip)");
        }

        // Tier-1：视图类名候选（View 子类才会 hook，控制器类自动跳过）
        hookCandidateClasses(CANDIDATE_CLASSES);
    }

    /** Tier-0：返回值为 int 的顶部间距方法，after 中叠加偏移。 */
    private void hookTopPaddingMethod(final String className, final String methodName,
                                      final boolean requireKeyguard) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, mLpp.classLoader);
            if (clazz == null) {
                HookLog.i(mTag + ": class not found (skip): " + className);
                return;
            }
            XC_MethodHook.Unhook u = XposedHelpers.findAndHookMethod(clazz, methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int extra = offsetPx();
                                if (extra <= 0) return;
                                Object result = param.getResult();
                                if (!(result instanceof Integer)) return;
                                // 非锁屏时该方法原始返回 0，不能叠加，否则会误伤展开态布局
                                if (requireKeyguard && !isKeyguardShowing(param.thisObject)) return;
                                param.setResult((Integer) result + extra);
                            } catch (Throwable t) {
                                HookLog.e(mTag + ": apply " + methodName + " failed", t);
                            }
                        }
                    });
            addUnhook(u);
            HookLog.i("HOOK OK " + className + "#" + methodName + " (lockscreen_sink)");
        } catch (Throwable t) {
            HookLog.e("HOOK FAIL " + className + "#" + methodName, t);
        }
    }

    /** 锁屏是否正在显示；取不到状态时返回 false（保持系统原样，最安全）。 */
    private static boolean isKeyguardShowing(Object panelVC) {
        try {
            Object r = XposedHelpers.callMethod(panelVC, "isKeyguardShowing");
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }
}
