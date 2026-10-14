package com.example.notificationsinking;

import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 视图级下沉状态（清理版）：只保留 translationY 方案。
 * 用于 Tier-1 视图候选（视图类存在时才生效），与间距 Hook 相互独立。
 */
final class SinkState {

    interface FeatureSwitch {
        boolean enabled();
    }

    private final String mTag;
    private final FeatureSwitch mFeature;

    private final Map<View, Integer> mOrigBottom =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());
    private final Set<View> mTouched =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<View, Boolean>()));

    private volatile float mDensity = 3.0f;

    SinkState(String tag, FeatureSwitch feature) {
        mTag = tag;
        mFeature = feature;
    }

    /** 该通道当前是否生效（总开关 + 本功能开关）。 */
    boolean active() {
        try {
            return ConfigStore.isEnabled() && mFeature.enabled();
        } catch (Throwable t) {
            return false;
        }
    }

    void refreshDensity() {
        try {
            android.content.Context ctx = Compat.appContext();
            if (ctx != null) {
                mDensity = ctx.getResources().getDisplayMetrics().density;
                return;
            }
            // 隐藏 API 限制拿不到 Context 时的兜底：系统 Resources 密度（公开 API）
            mDensity = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        } catch (Throwable ignored) {
        }
    }

    /** 当前偏移（px）；总开关或本功能关闭时返回 0。 */
    int offsetPx() {
        if (!ConfigStore.isEnabled() || !mFeature.enabled()) return 0;
        int dp = ConfigStore.getSinkDp();
        if (dp <= 0) return 0;
        return Math.round(dp * mDensity);
    }

    void applySink(View v) {
        if (v == null) return;
        int px = offsetPx();
        if (px <= 0) {
            clearSink(v);
            return;
        }
        if (v.getTranslationY() != px) {
            v.setTranslationY(px);
        }
        mTouched.add(v);
    }

    void clearSink(View v) {
        if (v == null) return;
        try {
            if (v.getTranslationY() != 0f) {
                v.setTranslationY(0f);
            }
        } finally {
            mTouched.remove(v);
        }
    }

    void restoreAll() {
        for (View v : snapshot()) {
            try {
                clearSink(v);
            } catch (Throwable ignored) {
            }
        }
        mOrigBottom.clear();
        HookLog.i(mTag + ": layout restored");
    }

    void reapplyAll() {
        for (View v : snapshot()) {
            try {
                applySink(v);
            } catch (Throwable ignored) {
            }
        }
    }

    private View[] snapshot() {
        Set<View> all = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
        synchronized (mTouched) {
            all.addAll(mTouched);
        }
        synchronized (mOrigBottom) {
            all.addAll(mOrigBottom.keySet());
        }
        return all.toArray(new View[0]);
    }
}
