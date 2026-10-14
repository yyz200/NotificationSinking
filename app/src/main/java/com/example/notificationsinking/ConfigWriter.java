package com.example.notificationsinking;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.concurrent.TimeUnit;

/**
 * 配置写入（清理版）：把 App 的配置写成 properties 文本，用 root 落到三处：
 *  1. /data/adb/lspd/modules/0/&lt;pkg&gt;/config.properties  —— SystemUI 直读 + FileObserver
 *  2. /data/adb/notificationsinking.conf                   —— root 手动通道
 *  3. /sdcard/Download/notificationsinking.conf           —— 无需 root 的手动通道
 * 另外按需写入系统键 lock_screen_notification_style（锁屏通知显示方式）。
 */
public final class ConfigWriter {

    private ConfigWriter() {
    }

    private static final String LSPD_DIR = "/data/adb/lspd/modules/0/" + ConfigStore.MODULE_PACKAGE;
    private static final String LSPD_FILE = LSPD_DIR + "/config.properties";
    private static final String ADB_FILE = "/data/adb/notificationsinking.conf";
    private static final String SD_FILE = "/sdcard/Download/notificationsinking.conf";

    /** 从 App 的 SharedPreferences 读取当前配置并写入全部位置。 */
    public static void writeFromPrefs(Context ctx, SharedPreferences prefs) {
        if (prefs == null) return;
        String text = "enabled=" + (prefs.getBoolean(ConfigStore.KEY_ENABLED, false) ? 1 : 0)
                + "\nlockscreen=" + (prefs.getBoolean(ConfigStore.KEY_LOCKSCREEN, true) ? 1 : 0)
                + "\naod=" + (prefs.getBoolean(ConfigStore.KEY_AOD, true) ? 1 : 0)
                + "\nkeep_clock=" + (prefs.getBoolean(ConfigStore.KEY_KEEP_CLOCK, false) ? 1 : 0)
                + "\nfold_notif=" + (prefs.getBoolean(ConfigStore.KEY_FOLD, false) ? 1 : 0)
                + "\nfold_max=" + prefs.getInt(ConfigStore.KEY_FOLD_MAX, 2)
                + "\nsink_engine=classic"
                + "\nclassic_cap_pct=" + prefs.getInt(ConfigStore.KEY_CLASSIC_CAP, 100)
                + "\nsink_dp=" + prefs.getInt(ConfigStore.KEY_SINK_DP, ConfigStore.SINK_DP_DEFAULT)
                + "\nsink_dp_media=" + prefs.getInt(ConfigStore.KEY_SINK_DP_MEDIA, -1)
                + "\nlock_notif_style=" + prefs.getInt(ConfigStore.KEY_LOCK_STYLE, -1)
                + "\n";
        int lockStyle = prefs.getInt(ConfigStore.KEY_LOCK_STYLE, -1);
        write(ctx, text, lockStyle);
    }

    /** 写入配置文件 + 按需写系统键（在后台线程调用）。 */
    public static void write(Context ctx, String text, int lockStyle) {
        try {
            File tmp = new File(ctx.getCacheDir(), "config.properties");
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8")) {
                w.write(text);
            }
            String src = tmp.getAbsolutePath();
            String style = (lockStyle >= 0)
                    ? "settings put system lock_screen_notification_style " + lockStyle + "; "
                    : "";
            String cmd = "mkdir -p " + LSPD_DIR + "; chmod 777 " + LSPD_DIR + "; "
                    + "cp \"" + src + "\" " + LSPD_FILE + "; chmod 666 " + LSPD_FILE + "; "
                    + "cp \"" + src + "\" " + ADB_FILE + "; chmod 666 " + ADB_FILE + "; "
                    + "cp \"" + src + "\" " + SD_FILE + "; chmod 666 " + SD_FILE + "; "
                    + style + "echo NSWRITTEN";
            String out = runRoot(cmd, 20);
            HookLog.i("ConfigWriter: wrote config (lockStyle=" + lockStyle + ") result="
                    + (out != null && out.contains("NSWRITTEN") ? "OK" : "FAILED"));
        } catch (Throwable t) {
            HookLog.e("ConfigWriter: write failed", t);
        }
    }

    /** root 执行命令并返回输出（失败返回空串，最长等 timeoutSec 秒以等待 Root 授权）。 */
    public static String runRoot(String cmd, int timeoutSec) {
        String[] suCandidates = {"su", "/system/bin/su", "/system/xbin/su"};
        for (String su : suCandidates) {
            Process p = null;
            try {
                p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
                final Process fp = p;
                final StringBuilder sb = new StringBuilder();
                Thread reader = new Thread(() -> {
                    try (java.io.InputStream in = fp.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            String s = new String(buf, 0, n, "UTF-8");
                            synchronized (sb) {
                                if (sb.length() < 200000) sb.append(s);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }, "su-read");
                reader.start();
                if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    p.destroy();
                    reader.join(500);
                }
                synchronized (sb) {
                    return sb.toString();
                }
            } catch (Throwable ignored) {
                // 尝试下一个 su 路径
            } finally {
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return "";
    }
}
