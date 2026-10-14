package com.example.notificationsinking;

import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 现代 API（libxposed 101/102）下的 Hook 实现 —— 清理后的最终版。
 *
 * 生效机制（均已在 OPPO Find X8 / ColorOS 16.0.9.400 上验证）：
 *  1. 下沉：Hook 三个间距方法，在系统原值上叠加 sink_dp 对应的像素；
 *     - 只有锁屏静止态才叠加：面板展开态、动画态（基线异常）一律返回系统原值，
 *       否则展开动画中系统基线自己变大，再叠加就会把内容顶到屏幕底部；
 *     - 有音乐卡片时自动让出音乐卡片高度（sink_dp - 150dp），避免通知被挤出屏幕；
 *     - 经典模式上限 classic_cap_pct（默认 100＝不限制），只保留"屏高-500px"可见性护栏。
 *  2. 折叠：只对锁屏胶囊条目生效，"+N 条"汇总行由系统提供；面板展开时暂停折叠。
 *  3. 灵动胶囊固定复位：永远不位移胶囊容器，并主动清零历史位移。
 *  4. 保持大时钟：强制时钟尺寸决策返回大尺寸；面板展开时让位（不强制）。
 */
final class ModernHooks {

    // ==================== 目标类名 ====================

    private static final String CLS_PANEL_VC =
            "com.android.systemui.shade.NotificationPanelViewController";
    private static final String CLS_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL =
            "com.oplus.systemui.notification.lockscreen.stack."
                    + "OplusLockscreenShadeTransitionControllerExImpl";

    /** 视图级候选（本机固件多数不存在，命中即用；不存在时静默跳过）。 */
    private static final String[] LOCKSCREEN_CANDIDATES = {
            "com.oplus.systemui.notification.capsule.CapsuleNotificationView",
            "com.oplus.systemui.notification.OplusNotificationCapsuleView",
            "com.oplus.systemui.notification.capsule.OplusCapsuleNotificationView",
            "com.oplus.systemui.lockscreen.notification.OplusLockScreenNotificationView",
            "com.oplus.systemui.lockscreen.notification.LockScreenNotificationView",
            "com.oplus.systemi.island.OplusIslandView",
            "com.oplus.systemui.notification.lockscreen.OplusLockscreenNotificationContainer",
    };

    private static final String[] AOD_CANDIDATES = {
            "com.oplus.systemui.aod.notification.AodNotificationView",
            "com.oplus.systemui.aod.notification.OplusAodNotificationView",
            "com.oplus.systemui.aod.notification.AodNotificationContainer",
            "com.oplus.systemui.aod.notification.AodNotificationGroupView",
            "com.oplus.systemui.aod.notification.view.AodNotificationContentView",
            "com.oplus.aod.notification.AodNotificationView",
            "com.oplus.aod.notification.OplusAodNotificationView",
            "com.oplus.aod.notification.AodNotificationContainer",
            "com.oplus.aod.view.AodNotificationView",
            "com.oplus.aod.OplusAodNotificationContainer",
    };

    /** 时钟尺寸决策类候选（AOSP 弹性时钟 + Oplus 定制）。 */
    private static final String[] CLOCK_CANDIDATES = {
            "com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel",
            "com.android.systemui.keyguard.ui.binder.KeyguardClockBinder",
            "com.oplus.systemui.keyguard.clock.OplusKeyguardClockViewModel",
            "com.oplus.systemui.keyguard.clock.OplusKeyguardClockController",
            "com.oplus.systemui.keyguard.OplusKeyguardClockController",
            "com.oplus.systemui.lockscreen.clock.OplusLockScreenClockController",
            "com.oplus.systemui.lockscreen.clock.OplusLockScreenClockViewModel",
            "com.oplus.systemui.keyguard.clock.OplusClockSizeController",
            "com.oplus.systemui.keyguard.clock.OplusKeyguardClockSizeController",
    };

    // ==================== 常量 ====================

    /** 静止态基线上限（px）：系统原值大于它说明处于展开/动画过程，不再叠加下沉。 */
    private static final int RESTING_BASE_MAX = 900;
    /** 可见性护栏：通知区顶部不得越过「屏高 - 该值」，否则整块内容会被推出屏幕。 */
    private static final int BOTTOM_KEEP_PX = 500;
    /** 节流日志间隔（ms）。 */
    private static final long LOG_THROTTLE_MS = 3000L;

    // ==================== 实例状态 ====================

    private final XposedModule mModule;
    private final String mPackageName;
    private final ClassLoader mClassLoader;
    private final boolean mHasLockscreenScope;
    private final boolean mHasAodScope;

    private final SinkState mLockSink =
            new SinkState("LockscreenSink", ConfigStore::isLockscreenEnabled);
    private final SinkState mAodSink =
            new SinkState("AodSink", ConfigStore::isAodEnabled);
    private final List<XposedInterface.HookHandle> mLockHandles = new ArrayList<>();
    private final List<XposedInterface.HookHandle> mAodHandles = new ArrayList<>();
    private final List<XposedInterface.HookHandle> mKeepClockHandles = new ArrayList<>();
    private final java.util.Set<String> mClockHandled = new java.util.HashSet<>();

    private volatile boolean mLockInstalled = false;
    private volatile boolean mAodInstalled = false;
    private volatile boolean mKeepClockInstalled = false;
    private volatile java.util.Set<String> mMinedNames;

    ModernHooks(XposedModule module, String packageName, ClassLoader classLoader,
                boolean lockscreenScope, boolean aodScope) {
        mModule = module;
        mPackageName = packageName;
        mClassLoader = classLoader;
        mHasLockscreenScope = lockscreenScope;
        mHasAodScope = aodScope;
    }

    /** 注册配置监听并立即按当前配置决策（不 hook 时零开销）。 */
    void start() {
        ConfigStore.addListener(this::onConfigChanged);
        onConfigChanged();
        // 每个进程挖一次 SystemUI 真实类名，用于"保持大时钟"补挂 Hook
        if (Compat.tryClaim("mined:" + mPackageName)) {
            DexMiner.mineOnce(mClassLoader, names -> {
                mMinedNames = names;
                HookLog.i("DexMiner: mined " + names.size() + " candidate class names");
                if (ConfigStore.isEnabled() && ConfigStore.isKeepClockEnabled()) {
                    installKeepClockFromMined(names);
                }
            });
        }
    }

    private void onConfigChanged() {
        try {
            mLockSink.refreshDensity();
            mAodSink.refreshDensity();
            manage("LockscreenSink", mHasLockscreenScope && ConfigStore.isLockscreenEnabled(),
                    mLockSink, mLockHandles, true);
            manage("AodSink", mHasAodScope && ConfigStore.isAodEnabled(),
                    mAodSink, mAodHandles, false);
            manageKeepClock();
            // 灵动胶囊必须固定在系统原位：任何时候都把历史位移清零
            resetCapsule();
            if (!ConfigStore.isFoldEnabled()) {
                restoreFolded();
            }
        } catch (Throwable t) {
            HookLog.e("ModernHooks: onConfigChanged failed", t);
        }
    }

    private void manage(String tag, boolean want, SinkState sink,
                        List<XposedInterface.HookHandle> handles, boolean lockscreen) {
        boolean installed = lockscreen ? mLockInstalled : mAodInstalled;
        boolean target = want && ConfigStore.isEnabled();
        if (target && !installed) {
            if (lockscreen) {
                installLockscreen();
                mLockInstalled = true;
            } else {
                installAod();
                mAodInstalled = true;
            }
            HookLog.i(tag + ": hooks installed (modern)");
        } else if (!target && installed) {
            for (XposedInterface.HookHandle h : new ArrayList<>(handles)) {
                try {
                    h.unhook();
                } catch (Throwable ignored) {
                }
            }
            handles.clear();
            sink.restoreAll();
            if (lockscreen) {
                mLockInstalled = false;
            } else {
                mAodInstalled = false;
            }
            HookLog.i(tag + ": hooks detached & layout restored (modern)");
        }
    }

    /** 锁屏：三个间距方法（Tier-0，已验证有效）+ 视图候选。 */
    private void installLockscreen() {
        // 子类与父类只由其中一个入口挂载，避免重复
        if (Compat.tryClaim("padding:" + mPackageName)) {
            hookIntPadding(CLS_PANEL_VC, "getKeyguardNotificationStaticPadding",
                    true, mLockSink, mLockHandles);
            hookIntPadding(CLS_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL,
                    "getNtfTopPaddingInLockscreen", false, mLockSink, mLockHandles);
            hookIntPadding(CLS_LOCKSCREEN_SHADE_TRANSITION_EX_IMPL,
                    "getNtfTopPaddingInLockscreenNtfCenter", false, mLockSink, mLockHandles);
        } else {
            HookLog.i("ModernHooks: padding hooks already installed by another entry (skip)");
        }
        for (String name : LOCKSCREEN_CANDIDATES) {
            hookViewClass(name, mLockSink, mLockHandles);
        }
    }

    private void installAod() {
        for (String name : AOD_CANDIDATES) {
            hookViewClass(name, mAodSink, mAodHandles);
        }
    }

    // ==================== 保持大时钟 ====================

    private void manageKeepClock() {
        boolean want = ConfigStore.isEnabled() && ConfigStore.isKeepClockEnabled();
        if (want && !mKeepClockInstalled) {
            installKeepClock();
            java.util.Set<String> mined = mMinedNames;
            if (mined != null && !mined.isEmpty()) {
                installKeepClockFromMined(mined);
            }
            mKeepClockInstalled = true;
            HookLog.i("KeepClock: hooks installed, count=" + mKeepClockHandles.size());
        } else if (!want && mKeepClockInstalled) {
            for (XposedInterface.HookHandle h : new ArrayList<>(mKeepClockHandles)) {
                try {
                    h.unhook();
                } catch (Throwable ignored) {
                }
            }
            mKeepClockHandles.clear();
            mKeepClockInstalled = false;
            HookLog.i("KeepClock: hooks detached");
        }
    }

    private void installKeepClock() {
        for (String name : CLOCK_CANDIDATES) {
            hookClockByName(name);
        }
    }

    /** 用挖掘出的本机真实类名补挂时钟 Hook（只看含 clock 且属于锁屏/Keyguard 的类）。 */
    private void installKeepClockFromMined(java.util.Set<String> names) {
        if (names == null || names.isEmpty()) return;
        int processed = 0;
        for (String s : names) {
            if (processed >= 30) break;
            String l = s.toLowerCase();
            if (!l.contains("clock") || l.contains("aod")) continue;
            if (l.contains("widget") || l.contains("plugin") || l.contains("weather")) continue;
            if (!(l.contains("keyguard") || l.contains("lockscreen") || l.contains("lock"))) continue;
            String className = s.replace('/', '.');
            if (className.startsWith("L")) className = className.substring(1);
            if (className.endsWith(";")) className = className.substring(0, className.length() - 1);
            if (mClockHandled.contains(className)) continue;
            hookClockByName(className);
            processed++;
        }
        HookLog.i("KeepClock: mined processing done, tried=" + processed
                + ", handles=" + mKeepClockHandles.size());
    }

    private void hookClockByName(String name) {
        if (!mClockHandled.add(name)) return;
        Class<?> clazz = findClass(name);
        if (clazz == null) {
            HookLog.i("KeepClock: class not found (skip): " + name);
            return;
        }
        hookClockClass(clazz, name);
    }

    /**
     * 通用「强制大时钟」：
     *  - 参数含 ClockSize 枚举 → 替换为大尺寸常量；
     *  - 返回 ClockSize 枚举 → 强制返回大尺寸常量；
     *  - 返回 boolean 且名字含 large/small 语义 → 强制大/非小。
     * 通知中心/下拉面板展开时不干预（让时钟正常让位，避免留在模糊层后面）。
     */
    private void hookClockClass(Class<?> clazz, String name) {
        int hooked = 0;
        for (final Method m : clazz.getDeclaredMethods()) {
            try {
                if (m.isSynthetic() || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                Class<?> rt = m.getReturnType();
                String mn = m.getName().toLowerCase();
                boolean returnsClockSize = rt.isEnum() && rt.getName().toLowerCase().contains("clocksize");
                boolean returnsBool = (rt == boolean.class || rt == Boolean.class);
                boolean clockish = mn.contains("clock") || mn.contains("keyguard");
                boolean largeName = mn.contains("large") || mn.contains("big")
                        || mn.contains("expand") || mn.contains("regular");
                boolean smallName = mn.contains("small") || mn.contains("shrink") || mn.contains("compact");
                boolean hasClockSizeParam = false;
                for (Class<?> p : m.getParameterTypes()) {
                    if (p.isEnum() && p.getName().toLowerCase().contains("clocksize")) {
                        hasClockSizeParam = true;
                        break;
                    }
                }
                boolean hookIt = returnsClockSize || hasClockSizeParam
                        || (returnsBool && clockish && (largeName || smallName));
                if (!hookIt) continue;

                m.setAccessible(true);
                final boolean fLarge = largeName;
                final boolean fSmall = smallName;
                final boolean fClockish = clockish;
                XposedInterface.HookHandle handle = mModule.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("ns-clock-" + m.getName())
                        .intercept(chain -> {
                            if (sShadeExpanded) {
                                logThrottled("keepclock-shade",
                                        "KeepClock: 面板已展开，本次不强制大时钟");
                                return chain.proceed();
                            }
                            Object[] args = chain.getArgs().toArray();
                            boolean argsChanged = false;
                            for (int i = 0; i < args.length; i++) {
                                Object a = args[i];
                                if (a != null && a.getClass().isEnum()
                                        && a.getClass().getName().toLowerCase().contains("clocksize")) {
                                    Object big = largeConstant(a.getClass());
                                    if (big != null && big != a) {
                                        args[i] = big;
                                        argsChanged = true;
                                    }
                                }
                            }
                            Object result = argsChanged ? chain.proceed(args) : chain.proceed();
                            try {
                                if (result != null && result.getClass().isEnum()
                                        && result.getClass().getName().toLowerCase().contains("clocksize")) {
                                    Object big = largeConstant(result.getClass());
                                    if (big != null) result = big;
                                } else if (result instanceof Boolean && fClockish) {
                                    if (fLarge) result = Boolean.TRUE;
                                    else if (fSmall) result = Boolean.FALSE;
                                }
                            } catch (Throwable ignored) {
                            }
                            logThrottled("keepclock-" + m.getName(),
                                    "HOOK fired(keepClock) " + chain.getExecutable().getName()
                                            + " -> " + result);
                            return result;
                        });
                mKeepClockHandles.add(handle);
                hooked++;
                HookLog.i("HOOK OK (keepClock) " + name + "#" + m.getName());
            } catch (Throwable t) {
                HookLog.e("KeepClock: hook failed " + name + "#" + m.getName(), t);
            }
        }
        HookLog.i("KeepClock: " + name + " hookedMethods=" + hooked);
    }

    /** 在 ClockSize 枚举里找"大尺寸"常量（REGULAR/LARGE/BIG/EXPAND 优先）。 */
    private static Object largeConstant(Class<?> enumClass) {
        try {
            Object[] constants = enumClass.getEnumConstants();
            if (constants == null || constants.length == 0) return null;
            for (Object c : constants) {
                String n = String.valueOf(c).toUpperCase();
                if (n.contains("REGULAR") || n.contains("LARGE") || n.contains("BIG")
                        || n.contains("EXPAND")) {
                    return c;
                }
            }
            for (Object c : constants) {
                String n = String.valueOf(c).toUpperCase();
                if (!n.contains("SMALL") && !n.contains("COMPACT") && !n.contains("SHRINK")) return c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================== 下沉：间距 Hook ====================

    /** Tier-0：返回 int 的顶部间距方法，proceed() 之后叠加偏移。 */
    private void hookIntPadding(String className, String methodName, boolean requireKeyguard,
                                final SinkState sink, List<XposedInterface.HookHandle> handles) {
        try {
            Class<?> clazz = findClass(className);
            if (clazz == null) {
                HookLog.i("ModernHooks: class not found (skip): " + className);
                return;
            }
            Method method = declaredMethod(clazz, methodName);
            if (method == null) {
                HookLog.i("HOOK FAIL (modern) " + className + "#" + methodName + " (method not found)");
                return;
            }
            method.setAccessible(true);
            XposedInterface.HookHandle handle = mModule.hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("ns-" + methodName)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            boolean keyguardOk = !requireKeyguard
                                    || isKeyguardShowing(chain.getThisObject());
                            probeShadeExpanded(chain.getThisObject());
                            int extra = branchExtra(sink, chain.getThisObject(), methodName);
                            if (extra > 0 && keyguardOk) {
                                // 展开态：系统基线会自己变化（622 → 1309+），再叠加会把内容顶到屏幕底部
                                if (sShadeExpanded) {
                                    logThrottled("shade-pause-" + methodName, "HOOK " + methodName
                                            + ": 面板展开中，暂停叠加下沉（保持系统原值）");
                                    return result;
                                }
                                // 基线异常（动画中）：同样不叠加
                                if (result instanceof Integer
                                        && ((Integer) result) > RESTING_BASE_MAX) {
                                    logThrottled("anim-pause-" + methodName, "HOOK " + methodName
                                            + ": 基线 " + result + "px 非静止态（动画中），暂停叠加下沉");
                                    return result;
                                }
                                applyFoldingIfEnabled(chain.getThisObject());
                                if (result instanceof Integer) {
                                    int target = capToScreen(((Integer) result) + extra, methodName);
                                    logThrottled(methodName, "HOOK fired " + methodName
                                            + ": " + result + " -> " + target
                                            + (requireKeyguard ? " (keyguard)" : ""));
                                    return Integer.valueOf(target);
                                }
                                return result;
                            }
                            if (extra <= 0) {
                                logThrottled("off-" + methodName, "HOOK skipped " + methodName
                                        + ": offset=0 (enabled=" + ConfigStore.isEnabled()
                                        + ", sinkDp=" + ConfigStore.getSinkDp() + ")");
                            } else {
                                logThrottled("kg-" + methodName, "HOOK skipped " + methodName
                                        + ": not keyguard (result=" + result + ")");
                            }
                            return result;
                        } catch (Throwable t) {
                            HookLog.e("ModernHooks: apply " + methodName + " failed", t);
                            return result;
                        }
                    });
            handles.add(handle);
            HookLog.i("HOOK OK (modern) " + className + "#" + methodName);
        } catch (Throwable t) {
            HookLog.e("HOOK FAIL (modern) " + className + "#" + methodName, t);
        }
    }

    /** Tier-1：候选视图类（仅 hook 该类自身声明的 onAttachedToWindow / onLayout）。 */
    private void hookViewClass(String className, final SinkState sink,
                               List<XposedInterface.HookHandle> handles) {
        try {
            Class<?> clazz = findClass(className);
            if (clazz == null) {
                HookLog.i("ModernHooks: class not found (skip): " + className);
                return;
            }
            if (!View.class.isAssignableFrom(clazz)) {
                HookLog.i("ModernHooks: non-view class skipped: " + className);
                return;
            }
            Method attach = declaredMethod(clazz, "onAttachedToWindow");
            if (attach != null) {
                attach.setAccessible(true);
                handles.add(mModule.hook(attach)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("ns-attach-" + clazz.getSimpleName())
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                sink.applySink((View) chain.getThisObject());
                            } catch (Throwable t) {
                                HookLog.e("ModernHooks: onAttachedToWindow failed " + className, t);
                            }
                            return result;
                        }));
            }
            Method onLayout = declaredMethod(clazz, "onLayout",
                    boolean.class, int.class, int.class, int.class, int.class);
            if (onLayout != null) {
                onLayout.setAccessible(true);
                handles.add(mModule.hook(onLayout)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("ns-layout-" + clazz.getSimpleName())
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                sink.applySink((View) chain.getThisObject());
                            } catch (Throwable ignored) {
                            }
                            return result;
                        }));
            }
        } catch (Throwable t) {
            HookLog.e("ModernHooks: hook view class failed " + className, t);
        }
    }

    /** 生效偏移：无音乐卡片用 sink_dp，有音乐卡片用 sink_dp_media（默认自动 −150dp）。 */
    private static int branchExtra(SinkState sink, Object controller, String tag) {
        int base = sink.offsetPx();
        try {
            if (!sink.active()) return base;
            boolean media = detectMediaPresent(controller);
            int dp = ConfigStore.effectiveSinkDp(media);
            int px = Math.round(dp * densityOf());
            if (px != base) {
                logThrottled("branch-" + tag, "ENGINE: " + (media ? "有音乐卡片" : "无音乐卡片")
                        + "方案 sink_dp=" + dp + "dp -> " + px + "px");
            }
            return px;
        } catch (Throwable ignored) {
            return base;
        }
    }

    /**
     * 间距上限：
     *  - classic_cap_pct >= 100：不做百分比钳制，只保留"屏高 - 500px"可见性护栏；
     *  - 否则按 classic_cap_pct 钳制。
     */
    private static int capToScreen(int value, String tag) {
        try {
            int screenH = android.content.res.Resources.getSystem().getDisplayMetrics().heightPixels;
            int pct = ConfigStore.getClassicCapPct();
            if (pct >= 100) {
                int guard = screenH - BOTTOM_KEEP_PX;
                if (value > guard) {
                    logThrottled("guard-" + tag, "HOOK " + tag + ": " + value
                            + "px 超出可见范围 → 收回到 " + guard
                            + "px（屏高-" + BOTTOM_KEEP_PX + "px）");
                    return guard;
                }
                logThrottled("nocap-" + tag, "HOOK " + tag + ": " + value
                        + "px（未限制，classic_cap_pct>=100）");
                return value;
            }
            int cap = (int) (screenH * pct / 100f);
            if (value > cap) {
                logThrottled("cap-" + tag, "HOOK clamped " + tag + ": " + value + " -> " + cap
                        + " (上限 " + pct + "% 屏高，classic_cap_pct)");
                return cap;
            }
        } catch (Throwable ignored) {
        }
        return value;
    }

    // ==================== 音乐卡片检测（带粘滞，避免动画期间误判） ====================

    private static volatile boolean sMediaPresent = false;
    private static volatile long sMediaProbeAt = 0L;
    private static volatile long sMediaSeenAt = 0L;
    private static volatile java.lang.ref.WeakReference<View> sMediaView =
            new java.lang.ref.WeakReference<>(null);

    /**
     * 是否存在音乐卡片：
     *  - 已缓存的音乐卡片只要还挂在窗口上就算存在（动画/展开时高度会变成 0，不能据此判无）；
     *  - 真正 detached 超过 5 秒才切回无音乐卡片分支。
     */
    private static boolean detectMediaPresent(Object controller) {
        long now = android.os.SystemClock.elapsedRealtime();
        try {
            View cached = sMediaView.get();
            if (cached != null && cached.isAttachedToWindow()) {
                sMediaSeenAt = now;
                sMediaPresent = true;
                return true;
            }
        } catch (Throwable ignored) {
        }
        if (now - sMediaProbeAt < 2000L) return sMediaPresent;
        sMediaProbeAt = now;
        try {
            View found = findMediaView(controller);
            if (found != null) {
                sMediaView = new java.lang.ref.WeakReference<>(found);
                sMediaSeenAt = now;
                if (!sMediaPresent) {
                    HookLog.i("MediaDetect: 音乐卡片出现 → 改用有音乐卡片方案（自动让出高度）");
                }
                sMediaPresent = true;
            } else if (sMediaPresent && now - sMediaSeenAt > 5000L) {
                HookLog.i("MediaDetect: 音乐卡片已消失 5s → 回到无音乐卡片方案(sink_dp)");
                sMediaPresent = false;
            }
        } catch (Throwable ignored) {
        }
        return sMediaPresent;
    }

    /** 在视图树里找锁屏音乐卡片（类名/资源名含 media/music，且高度适中）。 */
    private static View findMediaView(Object controller) {
        if (controller == null) return null;
        java.util.List<View> roots = collectViewFields(controller);
        int screenH = android.content.res.Resources.getSystem().getDisplayMetrics().heightPixels;
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        java.util.Set<View> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<View, Boolean>());
        for (View r : roots) {
            if (r != null && seen.add(r)) queue.add(r);
        }
        View best = null;
        int bestScore = 0;
        int visited = 0;
        while (!queue.isEmpty() && visited < 500) {
            View v = queue.poll();
            visited++;
            String n = v.getClass().getSimpleName().toLowerCase();
            String id = resName(v).toLowerCase();
            boolean named = n.contains("media") || n.contains("music") || n.contains("player")
                    || id.contains("media") || id.contains("music");
            if (named) {
                int h = v.getHeight();
                boolean sizeOk = h >= screenH * 0.06f && h <= screenH * 0.45f;
                if (sizeOk && v instanceof android.view.ViewGroup) {
                    int score = 10 + Math.min(h / 30, 10);
                    if (n.contains("media")) score += 5;
                    if (score > bestScore) {
                        bestScore = score;
                        best = v;
                    }
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                int count = Math.min(g.getChildCount(), 60);
                for (int i = 0; i < count; i++) {
                    View ch = g.getChildAt(i);
                    if (ch != null && seen.add(ch)) queue.add(ch);
                }
            }
        }
        return best;
    }

    // ==================== 锁屏通知折叠 ====================

    private static final java.util.List<java.lang.ref.WeakReference<View>> sFoldedItems =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 折叠：仅在开启时执行；通知中心/下拉面板展开时暂停（显示完整列表）。 */
    private static void applyFoldingIfEnabled(Object controller) {
        if (!ConfigStore.isFoldEnabled()) return;
        if (sShadeExpanded) {
            restoreFolded();
            logThrottled("fold-shade", "FOLD: 面板已展开，暂停折叠（恢复完整列表）");
            return;
        }
        try {
            View cap = findCapsuleContainer(controller);
            if (cap == null) return;
            // 灵动胶囊所在容器只承载"流体云胶囊"，不是通知卡片，禁止对它折叠
            if (cap.getClass().getSimpleName().toLowerCase().contains("capsulecontainer")) return;
            applyFolding(cap, ConfigStore.getFoldMax());
        } catch (Throwable ignored) {
        }
    }

    /** 容器内只保留前 maxVisible 个可见胶囊条目，其余隐藏（只动我们隐藏过的）。 */
    private static void applyFolding(View container, int maxVisible) {
        try {
            java.util.List<View> items = new java.util.ArrayList<>();
            collectItems(container, items, 0);
            if (items.isEmpty()) return;
            int shown = 0;
            int hiddenNow = 0;
            int restored = 0;
            for (View v : items) {
                boolean visible = v.getVisibility() == View.VISIBLE;
                if (visible && shown < maxVisible) {
                    shown++;
                    continue;
                }
                if (visible) {
                    v.setVisibility(View.GONE);
                    rememberFolded(v);
                    hiddenNow++;
                } else if (wasFoldedByUs(v) && shown < maxVisible) {
                    v.setVisibility(View.VISIBLE);
                    restored++;
                    shown++;
                }
            }
            logThrottled("fold", "FOLD: 胶囊条目=" + items.size() + ", 可见="
                    + Math.min(shown, maxVisible) + ", 本次隐藏=" + hiddenNow
                    + ", 恢复=" + restored + ", max=" + maxVisible);
        } catch (Throwable t) {
            HookLog.e("FOLD: apply failed", t);
        }
    }

    /** 收集容器内的胶囊条目（类名含 capsule 的 View），深度 ≤ 5。 */
    private static void collectItems(View v, java.util.List<View> out, int depth) {
        if (v == null || depth > 5 || out.size() > 40) return;
        String n = v.getClass().getSimpleName().toLowerCase();
        if (n.contains("capsule")) {
            out.add(v);
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int count = Math.min(g.getChildCount(), 40);
            for (int i = 0; i < count; i++) {
                collectItems(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    private static boolean wasFoldedByUs(View v) {
        for (java.lang.ref.WeakReference<View> ref : sFoldedItems) {
            if (ref.get() == v) return true;
        }
        return false;
    }

    private static void rememberFolded(View v) {
        for (java.lang.ref.WeakReference<View> ref : sFoldedItems) {
            if (ref.get() == v) return;
        }
        sFoldedItems.add(new java.lang.ref.WeakReference<>(v));
    }

    /** 恢复所有被我们隐藏的条目。 */
    private static void restoreFolded() {
        int restored = 0;
        for (java.lang.ref.WeakReference<View> ref : sFoldedItems) {
            View v = ref.get();
            if (v != null) {
                try {
                    if (v.getVisibility() == View.GONE) {
                        v.setVisibility(View.VISIBLE);
                        restored++;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        sFoldedItems.clear();
        if (restored > 0) HookLog.i("FOLD: 已恢复 " + restored + " 个条目");
    }

    // ==================== 灵动胶囊固定复位 ====================

    private static volatile java.lang.ref.WeakReference<View> sCapsuleView =
            new java.lang.ref.WeakReference<>(null);

    /** 找到胶囊容器并清零其位移（永远不主动位移它）。 */
    private static void resetCapsule() {
        try {
            View v = sCapsuleView.get();
            if (v == null) return;
            resetCapsuleSubtree(v);
            if (v.getTranslationY() != 0f) {
                v.setTranslationY(0f);
                HookLog.i("CapsuleFix: 灵动胶囊位移已复位 (" + v.getClass().getSimpleName() + ")");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把胶囊容器及其子视图的位移清零（只动 translationY，不动布局）。 */
    private static void resetCapsuleSubtree(View v) {
        try {
            int fixed = 0;
            java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
            queue.add(v);
            int visited = 0;
            while (!queue.isEmpty() && visited < 60) {
                View cur = queue.poll();
                visited++;
                if (cur.getTranslationY() != 0f) {
                    cur.setTranslationY(0f);
                    fixed++;
                }
                if (cur instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) cur;
                    int n = Math.min(g.getChildCount(), 30);
                    for (int i = 0; i < n; i++) {
                        View ch = g.getChildAt(i);
                        if (ch != null) queue.add(ch);
                    }
                }
            }
            if (fixed > 0) {
                HookLog.i("CapsuleFix: 胶囊容器子树复位 " + fixed + " 个视图位移");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 找 ColorOS 锁屏胶囊容器（类名含 capsule 的 View，或它的父容器）。 */
    private static View findCapsuleContainer(Object controller) {
        View cached = sCapsuleView.get();
        if (cached != null) return cached;
        java.util.List<View> roots = collectViewFields(controller);
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        java.util.Set<View> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<View, Boolean>());
        for (View r : roots) {
            if (r != null && seen.add(r)) queue.add(r);
        }
        int visited = 0;
        while (!queue.isEmpty() && visited < 500) {
            View v = queue.poll();
            visited++;
            String n = v.getClass().getSimpleName().toLowerCase();
            if (n.contains("capsule")) {
                boolean isItem = n.contains("item") || !(v instanceof android.view.ViewGroup);
                View target = v;
                if (isItem) {
                    try {
                        android.view.ViewParent p = v.getParent();
                        if (p instanceof View) target = (View) p;
                    } catch (Throwable ignored) {
                    }
                }
                sCapsuleView = new java.lang.ref.WeakReference<>(target);
                HookLog.i("CapsuleFix: 命中胶囊容器 " + v.getClass().getSimpleName()
                        + (isItem ? " 的父容器 " : " ") + target.getClass().getSimpleName()
                        + " (id=" + resName(target) + ")");
                return target;
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                int count = Math.min(g.getChildCount(), 60);
                for (int i = 0; i < count; i++) {
                    View ch = g.getChildAt(i);
                    if (ch != null && seen.add(ch)) queue.add(ch);
                }
            }
        }
        return null;
    }

    // ==================== 面板状态 ====================

    private static volatile boolean sShadeExpanded = false;
    private static volatile String sExpandedAccessor = null;

    /** 读取面板展开状态（多候选方法名，解析一次后缓存）。 */
    private static void probeShadeExpanded(Object controller) {
        if (controller == null) return;
        try {
            if (sExpandedAccessor == null) {
                String[] methods = {"isPanelExpanded", "isShadeExpanded", "isFullyExpanded",
                        "isExpanded", "isQsExpanded"};
                Class<?> c = controller.getClass();
                for (int depth = 0; depth < 6 && c != null && c != Object.class;
                     depth++, c = c.getSuperclass()) {
                    for (String mn : methods) {
                        try {
                            Method m = c.getDeclaredMethod(mn);
                            if (m.getReturnType() == boolean.class
                                    || m.getReturnType() == Boolean.class) {
                                m.setAccessible(true);
                                sExpandedAccessor = c.getSimpleName() + "#" + mn;
                                sShadeExpanded = Boolean.TRUE.equals(m.invoke(controller));
                                HookLog.i("ShadeState: 使用 " + sExpandedAccessor
                                        + " -> " + sShadeExpanded);
                                return;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                sExpandedAccessor = "(none)";
                return;
            }
            if ("(none)".equals(sExpandedAccessor)) return;
            String mn = sExpandedAccessor.substring(sExpandedAccessor.indexOf('#') + 1);
            Class<?> c = controller.getClass();
            for (int depth = 0; depth < 6 && c != null && c != Object.class;
                 depth++, c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(mn);
                    m.setAccessible(true);
                    sShadeExpanded = Boolean.TRUE.equals(m.invoke(controller));
                    return;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 小工具 ====================

    private static final java.util.Map<String, Long> LAST_FIRED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void logThrottled(String key, String msg) {
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            Long last = LAST_FIRED.get(key);
            if (last != null && now - last < LOG_THROTTLE_MS) return;
            LAST_FIRED.put(key, now);
            HookLog.i(msg);
        } catch (Throwable ignored) {
        }
    }

    /** 收集控制器（含父类）的所有 View 字段，作为视图树搜索的根。 */
    private static java.util.List<View> collectViewFields(Object controller) {
        java.util.List<View> roots = new java.util.ArrayList<>();
        if (controller == null) return roots;
        Class<?> c = controller.getClass();
        for (int depth = 0; depth < 8 && c != null && c != Object.class;
             depth++, c = c.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (java.lang.reflect.Field f : fields) {
                try {
                    if (!View.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object o = f.get(controller);
                    if (o instanceof View) roots.add((View) o);
                } catch (Throwable ignored) {
                }
            }
        }
        return roots;
    }

    private static String resName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "no-id";
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static float densityOf() {
        try {
            android.content.Context ctx = Compat.appContext();
            if (ctx != null) return ctx.getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {
        }
        return android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }

    private Class<?> findClass(String name) {
        try {
            return Class.forName(name, false, mClassLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method declaredMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isKeyguardShowing(Object target) {
        try {
            Method m = target.getClass().getMethod("isKeyguardShowing");
            Object v = m.invoke(target);
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            // 方法不存在时保守放行（避免完全不生效）
            return true;
        }
    }

    int installedHandleCount() {
        return mLockHandles.size() + mAodHandles.size();
    }
}
