package com.example.notificationsinking;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 传统入口（de.robv API）的下沉 Hook 基类：负责生命周期与定点 Hook 组装。
 * 视图偏移状态机复用于 {@link SinkState}（与现代入口共享）。
 */
abstract class BaseSinkHook {

    protected final XC_LoadPackage.LoadPackageParam mLpp;
    protected final String mTag;

    /** 视图偏移状态机（传统 / 现代入口共用同一实现）。 */
    protected final SinkState mSink;

    private final List<XC_MethodHook.Unhook> mUnhooks = new ArrayList<>();
    private volatile boolean mInstalled = false;

    BaseSinkHook(XC_LoadPackage.LoadPackageParam lpp, String tag) {
        mLpp = lpp;
        mTag = tag;
        mSink = new SinkState(tag, this::featureEnabled);
    }

    /** 本 Hook 对应的独立开关（总开关由 ConfigStore 统一判断）。 */
    protected abstract boolean featureEnabled();

    /** 安装全部 Hook（仅在功能开启时调用一次）。 */
    protected abstract void attachHooks();

    /** 启动：注册配置监听并立即按当前配置决策。 */
    public void install() {
        if (!Compat.tryClaim(mTag + ":" + mLpp.packageName)) {
            HookLog.i(mTag + ": already claimed for " + mLpp.packageName + " (skip)");
            return;
        }
        ConfigStore.addListener(this::onConfigChanged);
        onConfigChanged();
    }

    private void onConfigChanged() {
        try {
            mSink.refreshDensity();
            boolean want = ConfigStore.isEnabled() && featureEnabled();
            if (want && !mInstalled) {
                mInstalled = true;
                attachHooks();
                HookLog.i(mTag + ": hooks installed");
            } else if (!want && mInstalled) {
                detachAll();
                mSink.restoreAll();
                mInstalled = false;
                HookLog.i(mTag + ": hooks detached & layout restored");
            } else if (want) {
                mSink.reapplyAll();
            }
        } catch (Throwable t) {
            HookLog.e(mTag + ": onConfigChanged failed", t);
        }
    }

    protected void addUnhook(XC_MethodHook.Unhook u) {
        if (u != null) {
            mUnhooks.add(u);
        }
    }

    protected void detachAll() {
        for (XC_MethodHook.Unhook u : mUnhooks) {
            try {
                u.unhook();
            } catch (Throwable ignored) {
            }
        }
        mUnhooks.clear();
    }

    /** 当前应施加的偏移（px）。 */
    protected int offsetPx() {
        return mSink.offsetPx();
    }

    protected void applySink(View v) {
        mSink.applySink(v);
    }

    protected void reapplyAll() {
        mSink.reapplyAll();
    }

    // ==================== 通用 hook 组装（子类复用） ====================

    protected void hookCandidateClass(String className) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, mLpp.classLoader);
            if (clazz == null) {
                HookLog.i(mTag + ": class not found (skip): " + className);
                return;
            }
            if (View.class.isAssignableFrom(clazz)) {
                hookViewLayout(clazz, className);
            } else {
                HookLog.i(mTag + ": non-view class skipped: " + className);
            }
        } catch (Throwable t) {
            HookLog.e(mTag + ": candidate hook failed " + className, t);
        }
    }

    protected void hookCandidateClasses(String[] names) {
        for (String name : names) {
            hookCandidateClass(name);
        }
    }

    protected void hookViewLayout(Class<?> clazz, final String className) {
        try {
            XC_MethodHook.Unhook u = XposedHelpers.findAndHookMethod(clazz, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                mSink.applySink((View) param.thisObject);
                            } catch (Throwable t) {
                                HookLog.e(mTag + ": onAttachedToWindow failed " + className, t);
                            }
                        }
                    });
            addUnhook(u);
        } catch (Throwable t) {
            HookLog.e(mTag + ": hook onAttachedToWindow failed " + className, t);
        }
        try {
            XC_MethodHook.Unhook u = XposedHelpers.findAndHookMethod(clazz, "onLayout",
                    boolean.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                mSink.applySink((View) param.thisObject);
                            } catch (Throwable t) {
                                HookLog.e(mTag + ": onLayout failed " + className, t);
                            }
                        }
                    });
            addUnhook(u);
        } catch (Throwable t) {
            HookLog.e(mTag + ": hook onLayout failed " + className, t);
        }
        HookLog.i("HOOK OK " + className + " (view layout)");
    }
}
