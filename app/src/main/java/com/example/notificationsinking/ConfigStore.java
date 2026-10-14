package com.example.notificationsinking;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 配置读取（清理版）：只保留最终生效的项。
 *
 * 读取优先级：ContentProvider（App 前台时最快）→ 框架远程偏好 → 兜底文件 → 传统 XSharedPreferences。
 * 兜底文件（任一存在且含 enabled 即生效）：
 *   1) /data/adb/lspd/modules/0/&lt;pkg&gt;/config.properties  （App 用 root 写入）
 *   2) /data/adb/notificationsinking.conf
 *   3) /sdcard/Download/notificationsinking.conf          （无需 root，文件管理器即可改）
 */
public final class ConfigStore {

    public static final String MODULE_PACKAGE = "com.example.notificationsinking";
    public static final String PREFS_NAME = "com.example.notificationsinking_preferences";
    public static final Uri PREFS_URI =
            Uri.parse("content://" + MODULE_PACKAGE + ".prefs/prefs");
    /** 兜底配置文件路径（App 用 root 写入，SystemUI 直读 + FileObserver）。 */
    public static final String CONFIG_FILE =
            "/data/adb/lspd/modules/0/" + MODULE_PACKAGE + "/config.properties";

    // ---- 配置键（仅保留在用的） ----
    public static final String KEY_ENABLED = "enabled";               // 总开关
    public static final String KEY_LOCKSCREEN = "lockscreen_enabled"; // 锁屏下沉
    public static final String KEY_AOD = "aod_enabled";               // AOD 下沉
    public static final String KEY_SINK_DP = "sink_dp";               // 下沉值（dp）
    public static final String KEY_SINK_DP_MEDIA = "sink_dp_media";   // 有音乐卡片时的下沉值（-1＝自动 −150dp）
    public static final String KEY_KEEP_CLOCK = "keep_clock";         // 锁屏保持大时钟
    public static final String KEY_FOLD = "fold_notif";               // 锁屏通知折叠
    public static final String KEY_FOLD_MAX = "fold_max";             // 折叠后最多显示条数
    public static final String KEY_CLASSIC_CAP = "classic_cap_pct";   // 下沉上限：>=100 不限制
    public static final String KEY_LOCK_STYLE = "lock_notif_style";   // 锁屏通知显示方式（-1=不改）
    public static final String KEY_PREFS_VERSION = "prefs_version";   // 偏好迁移标记

    public static final int SINK_DP_DEFAULT = 40;
    public static final int SINK_DP_MAX = 1200;
    public static final int SINK_DP_STEP = 4;
    /** 有音乐卡片时自动让出的高度（dp）≈ 音乐卡片高度。 */
    public static final int MEDIA_RESERVE_DP = 150;

    private static final long DISPATCH_DEBOUNCE_MS = 300L;

    public interface Listener {
        void onConfigChanged();
    }

    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();

    private static volatile boolean sInited = false;
    private static volatile boolean sEnabled = false;          // 默认全关
    private static volatile boolean sLockscreenEnabled = true;
    private static volatile boolean sAodEnabled = true;
    private static volatile int sSinkDp = SINK_DP_DEFAULT;
    private static volatile int sSinkDpMedia = -1;             // -1＝有音乐卡片时自动 −150dp
    private static volatile boolean sKeepClock = false;
    private static volatile boolean sFold = false;
    private static volatile int sFoldMax = 2;
    private static volatile int sClassicCapPct = 100;          // 100＝不限制
    private static volatile int sLockStyle = -1;               // -1＝不改系统键

    private static volatile SharedPreferences sModernPrefs;
    private static Object sLegacyXsp;
    private static Handler sMainHandler;

    private static final Runnable sDispatchRunnable = ConfigStore::dispatchListeners;

    private ConfigStore() {
    }

    /** 现代入口注入框架远程偏好（libxposed Remote Preferences）。 */
    public static void setRemotePreferences(SharedPreferences prefs) {
        sModernPrefs = prefs;
        try {
            prefs.registerOnSharedPreferenceChangeListener((sp, key) -> refreshAndNotify());
            HookLog.i("ConfigStore: modern remote preferences attached (change listener registered)");
        } catch (Throwable t) {
            HookLog.i("ConfigStore: modern remote preferences attached (no listener: " + t + ")");
        }
        refresh();
    }

    public static synchronized void ensureInitialized() {
        if (sInited) return;
        sInited = true;
        try {
            sMainHandler = new Handler(Looper.getMainLooper());
        } catch (Throwable ignored) {
        }

        // 传统只读兜底（类缺失时静默）
        try {
            Object xsp = Class.forName("de.robv.android.xposed.XSharedPreferences")
                    .getConstructor(String.class, String.class)
                    .newInstance(MODULE_PACKAGE, PREFS_NAME);
            xsp.getClass().getMethod("makeWorldReadable").invoke(xsp);
            sLegacyXsp = xsp;
        } catch (Throwable t) {
            sLegacyXsp = null;
        }

        ensureObserver();
        startConfigObserver();
        refresh();
        HookLog.i("ConfigStore: initialized, " + describe());
    }

    /** ContentObserver 懒注册（Context 就绪后补上）。 */
    private static void ensureObserver() {
        if (sObserverRegistered) return;
        Context ctx = Compat.appContext();
        if (ctx == null) {
            HookLog.i("ConfigStore: app context not ready yet (provider channel deferred)");
            return;
        }
        try {
            ctx.getContentResolver().registerContentObserver(
                    PREFS_URI, false,
                    new ContentObserver(sMainHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            refreshAndNotify();
                        }
                    });
            sObserverRegistered = true;
            HookLog.i("ConfigStore: ContentObserver registered on " + PREFS_URI);
        } catch (Throwable t) {
            HookLog.e("ConfigStore: ContentObserver register failed", t);
        }
    }

    private static volatile boolean sObserverRegistered = false;

    /** 监听兜底配置文件变化（App 写入后实时生效）。 */
    private static void startConfigObserver() {
        if (sConfigObserver != null) return;
        try {
            java.io.File dir = new java.io.File(CONFIG_FILE).getParentFile();
            if (dir == null) return;
            dir.mkdirs();
            android.os.FileObserver fo = new android.os.FileObserver(dir.getAbsolutePath(),
                    android.os.FileObserver.CREATE
                            | android.os.FileObserver.MODIFY
                            | android.os.FileObserver.CLOSE_WRITE) {
                @Override
                public void onEvent(int event, String path) {
                    try {
                        if (path != null && path.contains("config.properties")) {
                            refreshAndNotify();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            fo.startWatching();
            sConfigObserver = fo;
            HookLog.i("ConfigStore: file observer started on " + dir.getAbsolutePath());
        } catch (Throwable t) {
            HookLog.i("ConfigStore: file observer unavailable (" + t + ")");
        }
    }

    private static android.os.FileObserver sConfigObserver;

    /** 读取最新值（Provider → 远程偏好 → 兜底文件 → XSP）。 */
    public static void refresh() {
        ensureObserver();
        Context ctx = Compat.appContext();
        if (ctx != null) {
            Cursor c = null;
            try {
                ContentResolver cr = ctx.getContentResolver();
                c = cr.query(PREFS_URI, null, null, null, null);
                if (c != null) {
                    Bundle b = c.getExtras();
                    if (b != null) {
                        readFrom(b);
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // Provider 不可达，走下一级
            } finally {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        SharedPreferences modern = sModernPrefs;
        if (modern != null) {
            try {
                if (modern.contains(KEY_ENABLED) || modern.contains(KEY_SINK_DP)) {
                    readFrom(modern);
                    return;
                }
            } catch (Throwable t) {
                HookLog.e("ConfigStore: read modern prefs failed", t);
            }
        }
        if (readFromFile()) return;
        Object xsp = sLegacyXsp;
        if (xsp != null) {
            try {
                xsp.getClass().getMethod("reload").invoke(xsp);
                readFrom((SharedPreferences) xsp);
            } catch (Throwable t) {
                HookLog.e("ConfigStore: read XSharedPreferences failed", t);
            }
        }
    }

    /** 直读配置文件（三个候选路径，按优先级）。 */
    private static boolean readFromFile() {
        String[] candidates = {
                CONFIG_FILE,
                "/data/adb/notificationsinking.conf",
                "/sdcard/Download/notificationsinking.conf",
                "/storage/emulated/0/Download/notificationsinking.conf",
        };
        for (String path : candidates) {
            try {
                java.io.File f = new java.io.File(path);
                if (!f.isFile() || !f.canRead()) continue;
                java.util.Properties p = new java.util.Properties();
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    p.load(in);
                }
                if (!p.containsKey(KEY_ENABLED)) continue;
                sEnabled = "1".equals(p.getProperty(KEY_ENABLED, "0"));
                sLockscreenEnabled = "1".equals(p.getProperty(KEY_LOCKSCREEN, "1"));
                sAodEnabled = "1".equals(p.getProperty(KEY_AOD, "1"));
                sKeepClock = "1".equals(p.getProperty(KEY_KEEP_CLOCK, "0"));
                sFold = "1".equals(p.getProperty(KEY_FOLD, "0"));
                sSinkDp = clampSinkDp(parseInt(p.getProperty(KEY_SINK_DP, "40"), SINK_DP_DEFAULT));
                sSinkDpMedia = Math.max(-1, Math.min(SINK_DP_MAX,
                        parseInt(p.getProperty(KEY_SINK_DP_MEDIA, "-1"), -1)));
                sFoldMax = Math.max(1, Math.min(6, parseInt(p.getProperty(KEY_FOLD_MAX, "2"), 2)));
                sClassicCapPct = Math.max(30, Math.min(200,
                        parseInt(p.getProperty(KEY_CLASSIC_CAP, "100"), 100)));
                sLockStyle = Math.max(-1, Math.min(9,
                        parseInt(p.getProperty(KEY_LOCK_STYLE, "-1"), -1)));
                HookLog.i("ConfigStore: config loaded from " + path + " -> " + describe());
                return true;
            } catch (Throwable ignored) {
                // 尝试下一个候选路径
            }
        }
        return false;
    }

    private static void readFrom(SharedPreferences sp) {
        sEnabled = sp.getBoolean(KEY_ENABLED, false);
        sLockscreenEnabled = sp.getBoolean(KEY_LOCKSCREEN, true);
        sAodEnabled = sp.getBoolean(KEY_AOD, true);
        sKeepClock = sp.getBoolean(KEY_KEEP_CLOCK, false);
        sFold = sp.getBoolean(KEY_FOLD, false);
        sSinkDp = clampSinkDp(sp.getInt(KEY_SINK_DP, SINK_DP_DEFAULT));
        sSinkDpMedia = Math.max(-1, Math.min(SINK_DP_MAX, sp.getInt(KEY_SINK_DP_MEDIA, -1)));
        sFoldMax = Math.max(1, Math.min(6, sp.getInt(KEY_FOLD_MAX, 2)));
        sClassicCapPct = Math.max(30, Math.min(200, sp.getInt(KEY_CLASSIC_CAP, 100)));
        sLockStyle = Math.max(-1, Math.min(9, sp.getInt(KEY_LOCK_STYLE, -1)));
    }

    private static void readFrom(Bundle b) {
        sEnabled = b.getBoolean(KEY_ENABLED, false);
        sLockscreenEnabled = b.getBoolean(KEY_LOCKSCREEN, true);
        sAodEnabled = b.getBoolean(KEY_AOD, true);
        sKeepClock = b.getBoolean(KEY_KEEP_CLOCK, false);
        sFold = b.getBoolean(KEY_FOLD, false);
        sSinkDp = clampSinkDp(b.getInt(KEY_SINK_DP, SINK_DP_DEFAULT));
        sSinkDpMedia = Math.max(-1, Math.min(SINK_DP_MAX, b.getInt(KEY_SINK_DP_MEDIA, -1)));
        sFoldMax = Math.max(1, Math.min(6, b.getInt(KEY_FOLD_MAX, 2)));
        sClassicCapPct = Math.max(30, Math.min(200, b.getInt(KEY_CLASSIC_CAP, 100)));
        sLockStyle = Math.max(-1, Math.min(9, b.getInt(KEY_LOCK_STYLE, -1)));
    }

    public static void refreshAndNotify() {
        refresh();
        HookLog.i("ConfigStore: changed, " + describe());
        if (sMainHandler != null) {
            sMainHandler.removeCallbacks(sDispatchRunnable);
            sMainHandler.postDelayed(sDispatchRunnable, DISPATCH_DEBOUNCE_MS);
        } else {
            dispatchListeners();
        }
    }

    private static void dispatchListeners() {
        for (Listener l : sListeners) {
            try {
                l.onConfigChanged();
            } catch (Throwable t) {
                HookLog.e("ConfigStore: listener crashed", t);
            }
        }
    }

    public static void addListener(Listener l) {
        if (l != null && !sListeners.contains(l)) {
            sListeners.add(l);
        }
    }

    public static void removeListener(Listener l) {
        sListeners.remove(l);
    }

    // ---- 取值 ----

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static boolean isLockscreenEnabled() {
        return sLockscreenEnabled;
    }

    public static boolean isAodEnabled() {
        return sAodEnabled;
    }

    public static int getSinkDp() {
        return sSinkDp;
    }

    /** 有音乐卡片时的下沉值；-1 表示自动（sink_dp − 150dp）。 */
    public static int getSinkDpMedia() {
        return sSinkDpMedia;
    }

    public static boolean isKeepClockEnabled() {
        return sKeepClock;
    }

    public static boolean isFoldEnabled() {
        return sFold;
    }

    public static int getFoldMax() {
        return sFoldMax;
    }

    /** 下沉上限（屏高百分比）；>=100 表示不限制。 */
    public static int getClassicCapPct() {
        return sClassicCapPct;
    }

    /** 锁屏通知显示方式：-1＝不改系统键；0＝列表/堆叠；1/2＝胶囊。 */
    public static int getLockStyle() {
        return sLockStyle;
    }

    /**
     * 两套逻辑：按"有无音乐卡片"给出实际使用的下沉值。
     * 有音乐卡片时若未显式设置，则自动减去音乐卡片高度（否则通知堆叠会被挤出屏幕）。
     */
    public static int effectiveSinkDp(boolean mediaPresent) {
        if (!mediaPresent) return sSinkDp;
        if (sSinkDpMedia >= 0) return sSinkDpMedia;
        return Math.max(0, sSinkDp - MEDIA_RESERVE_DP);
    }

    public static String describe() {
        return "enabled=" + sEnabled
                + ", lockscreen=" + sLockscreenEnabled
                + ", aod=" + sAodEnabled
                + ", keepClock=" + sKeepClock
                + ", fold=" + sFold + "/" + sFoldMax
                + ", cap=" + sClassicCapPct
                + ", lockStyle=" + sLockStyle
                + ", sinkDp=" + sSinkDp
                + ", sinkDpMedia=" + sSinkDpMedia;
    }

    /** 范围 0..SINK_DP_MAX，步长 4dp。 */
    private static int clampSinkDp(int v) {
        int rounded = Math.round(v / (float) SINK_DP_STEP) * SINK_DP_STEP;
        return Math.max(0, Math.min(SINK_DP_MAX, rounded));
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
