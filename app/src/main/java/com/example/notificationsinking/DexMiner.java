package com.example.notificationsinking;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.Enumeration;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * SystemUI dex 类名挖掘（纯 Java，不依赖手机的 unzip）。
 *
 * 用途：「保持大时钟」需要本机固件真实的时钟类名（各版本不同，猜不中就无法 Hook）。
 * 本类在 SystemUI 进程内流式扫描 SystemUI APK 的 classes*.dex 字符串池，收集
 * clock / keyguard / island / capsule / notif / lockscreen 相关的类描述符：
 *  - 写入 /data/adb/lspd/modules/0/&lt;pkg&gt;/mined-classes.txt（诊断可查看）
 *  - 回调给调用方，用于自动补挂时钟 Hook
 * 只读不写、单线程、每个进程执行一次。
 */
final class DexMiner {

    private static final String OUT_DIR = "/data/adb/lspd/modules/0/" + ConfigStore.MODULE_PACKAGE;
    private static final String OUT_FILE = OUT_DIR + "/mined-classes.txt";
    private static final int MAX_HITS = 4000;

    private DexMiner() {
    }

    /** 挖掘结果消费回调。 */
    interface Consumer {
        void accept(TreeSet<String> classNames);
    }

    static void mineOnce(final ClassLoader cl) {
        mineOnce(cl, null);
    }

    /** 带回调版本：挖掘完成后把类名集合交给调用方（用于自动挂时钟 Hook）。 */
    static void mineOnce(final ClassLoader cl, final Consumer onDone) {
        new Thread(() -> {
            try {
                String apk = systemUiApkPath(cl);
                if (apk == null) {
                    HookLog.i("DexMiner: systemui apk path not found");
                    return;
                }
                TreeSet<String> hits = new TreeSet<>();
                try (ZipFile zip = new ZipFile(apk)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!name.startsWith("classes") || !name.endsWith(".dex")) continue;
                        InputStream in = null;
                        try {
                            in = zip.getInputStream(entry);
                            scan(in, hits);
                        } catch (Throwable t) {
                            HookLog.e("DexMiner: scan failed " + name, t);
                        } finally {
                            if (in != null) try {
                                in.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                report(hits);
                if (onDone != null) {
                    try {
                        onDone.accept(hits);
                    } catch (Throwable t) {
                        HookLog.e("DexMiner: callback failed", t);
                    }
                }
            } catch (Throwable t) {
                HookLog.e("DexMiner: failed", t);
            }
        }, "ns-dex-miner").start();
    }

    /** 取 SystemUI APK 路径：优先 /proc/self/maps，再退到 CodeSource 与固定路径。 */
    private static String systemUiApkPath(ClassLoader cl) {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream("/proc/self/maps")));
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf(".apk");
                if (idx < 0) continue;
                int sp = line.lastIndexOf(' ', idx);
                if (sp < 0) continue;
                String path = line.substring(sp + 1, idx + 4).trim();
                if (path.startsWith("/") && new File(path).isFile()) {
                    HookLog.i("DexMiner: apk from /proc/self/maps = " + path);
                    return path;
                }
            }
        } catch (Throwable t) {
            HookLog.i("DexMiner: maps scan failed (" + t + ")");
        } finally {
            if (reader != null) try {
                reader.close();
            } catch (Throwable ignored) {
            }
        }
        String[] probes = {
                "com.android.systemui.shade.NotificationPanelViewController",
                "com.android.systemui.SystemUIApplication",
                "com.android.systemui.R",
        };
        for (String probe : probes) {
            try {
                Class<?> c = Class.forName(probe, false, cl);
                java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    String path = cs.getLocation().getPath();
                    if (path != null && path.endsWith(".apk") && new File(path).isFile()) {
                        HookLog.i("DexMiner: apk from CodeSource = " + path);
                        return path;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        String[] fixed = {
                "/system_ext/priv-app/SystemUI/SystemUI.apk",
                "/system/priv-app/SystemUI/SystemUI.apk",
                "/system_ext/priv-app/OplusSystemUI/OplusSystemUI.apk",
                "/my_product/priv-app/SystemUI/SystemUI.apk",
        };
        for (String p : fixed) {
            try {
                if (new File(p).isFile()) {
                    HookLog.i("DexMiner: apk from fixed path = " + p);
                    return p;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 流式扫描：识别 Lcom/...; 形式的类描述符。 */
    private static void scan(InputStream in, TreeSet<String> hits) throws Exception {
        byte[] buf = new byte[1 << 16];
        StringBuilder cur = null;
        int n;
        while ((n = in.read(buf)) != -1) {
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c == 'L') {
                    cur = new StringBuilder(64);
                    cur.append('L');
                } else if (cur != null) {
                    if (c == ';') {
                        String s = cur.toString();
                        if (s.length() > 14 && s.length() < 180 && interesting(s)
                                && hits.size() < MAX_HITS) {
                            hits.add(s);
                        }
                        cur = null;
                    } else if (isClassChar(c)) {
                        cur.append(c);
                    } else {
                        cur = null;
                    }
                }
            }
        }
    }

    private static boolean isClassChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '/' || c == '$' || c == '_';
    }

    private static boolean interesting(String s) {
        String l = s.toLowerCase();
        if (!l.startsWith("lcom/oplus/") && !l.startsWith("lcom/android/systemui/")
                && !l.startsWith("lcom/coloros/")) {
            return false;
        }
        return l.contains("clock") || l.contains("keyguard") || l.contains("island")
                || l.contains("capsule") || l.contains("notif") || l.contains("lockscreen");
    }

    /** 写出结果文件 + 日志摘要（clock/keyguard 子集优先）。 */
    private static void report(TreeSet<String> hits) {
        try {
            File dir = new File(OUT_DIR);
            dir.mkdirs();
            File out = new File(OUT_FILE);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) {
                w.write("# SystemUI 类名挖掘结果\n");
                for (String s : hits) {
                    w.write(s);
                    w.write('\n');
                }
            }
            HookLog.i("DexMiner: " + hits.size() + " classes written to " + OUT_FILE);
        } catch (Throwable t) {
            HookLog.i("DexMiner: write result skipped (" + t + ")");
        }
        int clockLogged = 0;
        for (String s : hits) {
            String l = s.toLowerCase();
            if (l.contains("clock") || l.contains("keyguard")) {
                HookLog.i("DexMiner: " + s);
                if (++clockLogged >= 40) break;
            }
        }
        HookLog.i("DexMiner: clock/keyguard subset logged=" + clockLogged);
    }
}
