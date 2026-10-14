package com.example.notificationsinking;

/**
 * 诊断命令集（v1.3.2）。
 *
 * 关键设计：**分段执行**——每段是一条短命令，App 侧逐段调用 root 并各自超时，
 * 单段超时/失败不影响其余段；对 LSPosed 日志文件只读尾部或直接 grep（避免超时）。
 * 新增：配置库二进制 hexdump（直接读出模块的 enabled 标志位）、log.old / kmsg 扫描。
 */
public final class Diag {

    private Diag() {
    }

    public static final class Section {
        public final String label;
        public final String command;
        public final int timeoutSec;

        Section(String label, String command, int timeoutSec) {
            this.label = label;
            this.command = command;
            this.timeoutSec = timeoutSec;
        }
    }

    private static final String PKG = "com.example.notificationsinking";

    /** 完整诊断分段。 */
    public static Section[] sections() {
        return new Section[]{
                new Section("[1] logcat: 本模块/入口/LSPosed 相关",
                        "logcat -d | grep -iE \"notification.?sinking|lsposed|zygisk\" "
                                + "| grep -vE \"ActivityThread|PackageSetting|WindowManager|AppsFilter|OplusDex|OplusAppSwitch|FlexibleTask|InputMethod|StartingSurface|AppSnapshot|NativeFreeze|VRI|OPM|Transition\" "
                                + "| tail -n 120", 20),
                new Section("[2] 本模块 tag 的全部 logcat 行",
                        "logcat -d -s NotificationSinking:E | tail -n 60; echo \"(若为空=模块未被注入)\"", 15),
                new Section("[3] LSPosed 目录与 modules/<user> 内容",
                        "ls -l /data/adb/lspd/log/ 2>&1 | head -n 15; "
                                + "echo \"--- modules/ ---\"; ls -l /data/adb/lspd/modules/ 2>&1 | head -n 15; "
                                + "echo \"--- modules/0/ ---\"; ls -l /data/adb/lspd/modules/0/ 2>&1 | head -n 30; "
                                + "echo \"--- log.old ---\"; ls -l /data/adb/lspd/log.old/ 2>&1 | head -n 10", 15),
                new Section("[4] 本次开机后 LSPosed 日志中提到本模块的行",
                        "grep -i \"notificationsinking\" /data/adb/lspd/log/*.log 2>/dev/null | tail -n 30; "
                                + "echo \"(扫描完成，若为空=本次开机框架从未处理过本模块)\"", 20),
                new Section("[5] 重启前的旧日志（log.old）中提到本模块的行",
                        "for f in /data/adb/lspd/log.old/*.log; do [ -f \"$f\" ] || continue; "
                                + "n=$(tail -c 600000 \"$f\" | grep -i -c notificationsinking); "
                                + "if [ \"$n\" != \"0\" ]; then echo \"--- $f (hits=$n)\"; "
                                + "tail -c 600000 \"$f\" | grep -i notificationsinking | tail -n 25; fi; done; "
                                + "echo \"(old-log 扫描完成)\"", 25),
                new Section("[6] kmsg 中本模块/LSPosed 相关行",
                        "tail -c 500000 /data/adb/lspd/log/kmsg.log 2>/dev/null "
                                + "| grep -iE \"notificationsinking|lsposed\" | tail -n 30; "
                                + "echo \"(kmsg 扫描完成)\"", 20),
                new Section("[7] 配置库：模块记录 + enabled 标志位 hexdump",
                        "ls -l /data/adb/lspd/config/ 2>&1 | head -n 12; "
                                + "echo \"--- grep 命中 ---\"; "
                                + "grep -a -o \"com\\.example\\.notificationsinking\" /data/adb/lspd/config/modules_config.db 2>&1 | head -n 3; "
                                + "echo \"--- hexdump（模块记录附近字节，enabled 通常在包名/路径后的 00 或 01）---\"; "
                                + "off=$(grep -a -b -o \"com\\.example\\.notificationsinking\" /data/adb/lspd/config/modules_config.db 2>/dev/null | head -n 1 | cut -d: -f1); "
                                + "echo \"first offset=$off\"; "
                                + "if [ -n \"$off\" ]; then s=$((off-60)); [ $s -lt 0 ] && s=0; "
                                + "dd if=/data/adb/lspd/config/modules_config.db bs=1 skip=$s count=700 2>/dev/null | od -A d -c | head -n 50; fi", 25),
                new Section("[8] 已安装 APK 版本与路径",
                        "dumpsys package " + PKG + " 2>&1 | grep -E \"versionCode|versionName|codePath|lastUpdateTime|primaryCpuAbi\" | head -n 10; "
                                + "pm path " + PKG + " 2>&1 | head -n 3", 20),
                new Section("[9] 已装 APK 内的模块入口文件",
                        "APK=$(pm path " + PKG + " | head -n 1 | cut -d: -f2); echo \"apk=$APK\"; "
                                + "if [ -n \"$APK\" ]; then unzip -l \"$APK\" 2>/dev/null | grep -iE \"xposed|module.prop|META-INF\" | head -n 20; "
                                + "else echo \"pm path 未返回路径\"; fi", 20),
                new Section("[10] App 配置与兜底文件（关键：确认开关真实状态与配置是否写入）",
                        "echo \"--- App SharedPreferences ---\"; "
                                + "cat /data/data/" + PKG + "/shared_prefs/" + PKG + "_preferences.xml 2>&1 | head -n 20; "
                                + "echo \"--- lspd 模块目录 ---\"; ls -l /data/adb/lspd/modules/0/" + PKG + "/ 2>&1; "
                                + "echo \"--- lspd config.properties ---\"; "
                                + "cat /data/adb/lspd/modules/0/" + PKG + "/config.properties 2>&1; "
                                + "echo \"--- /data/adb/notificationsinking.conf ---\"; "
                                + "cat /data/adb/notificationsinking.conf 2>&1; "
                                + "echo \"--- /sdcard/Download/notificationsinking.conf ---\"; "
                                + "cat /sdcard/Download/notificationsinking.conf 2>&1", 20),
                new Section("[12] 本机 SystemUI 真实类名（锁屏命名空间 + 时钟/胶囊）",
                        "echo \"--- 模块自挖类名 mined-classes.txt ---\"; "
                                + "F=/data/adb/lspd/modules/0/" + PKG + "/mined-classes.txt; "
                                + "[ -f \"$F\" ] && { wc -l \"$F\"; "
                                + "grep -iE \"notification/lockscreen|capsule|island|clock\" \"$F\" | head -n 50; } "
                                + "|| echo \"(无 mined-classes.txt)\"; "
                                + "APK=$(pm path com.android.systemui | head -n 1 | cut -d: -f2); echo \"apk=$APK\"; "
                                + "D=/data/local/tmp/ns-dex; rm -rf $D; mkdir -p $D; "
                                + "unzip -o -d $D \"$APK\" \"classes*.dex\" >/dev/null 2>&1; "
                                + "echo \"--- 锁屏通知命名空间 ---\"; "
                                + "grep -a -o \"Lcom/oplus/systemui/notification/lockscreen/[A-Za-z0-9_/$]*;\" $D/classes*.dex 2>/dev/null "
                                + "| sort -u | head -n 50; "
                                + "echo \"--- capsule/clock/keyguard ---\"; "
                                + "grep -a -o \"Lcom/oplus/systemui/[A-Za-z0-9_/$]*;\" $D/classes*.dex 2>/dev/null "
                                + "| grep -iE \"capsule|clock|keyguard\" | sort -u | head -n 40", 180),
                new Section("[13] 锁屏通知显示方式（系统键当前值）+ 相关设置",
                        "echo \"--- lock_screen_notification_style ---\"; "
                                + "settings get system lock_screen_notification_style; "
                                + "echo \"--- 相关设置（system） ---\"; "
                                + "settings list system 2>/dev/null | grep -iE \"notif|lockscreen|keyguard\" | head -n 30", 40),
                new Section("[14] logcat 自检（末尾 3 行）",
                        "logcat -d -t 3", 10),
        };
    }

    /** 精简诊断（一屏内）。 */
    public static Section[] briefSections() {
        return new Section[]{
                new Section("A. 本模块日志（tag NotificationSinking）",
                        "logcat -d -s NotificationSinking:E | tail -n 12; echo \"(若为空=模块未被注入)\"", 15),
                new Section("B. LSPosed 本次开机是否处理过本模块",
                        "grep -i \"notificationsinking\" /data/adb/lspd/log/*.log 2>/dev/null | tail -n 8; "
                                + "echo \"(若为空=框架未加载过本模块)\"", 20),
                new Section("C. 配置库 enabled 标志 hexdump",
                        "off=$(grep -a -b -o \"com\\.example\\.notificationsinking\" /data/adb/lspd/config/modules_config.db 2>/dev/null | head -n 1 | cut -d: -f1); "
                                + "if [ -n \"$off\" ]; then s=$((off-40)); [ $s -lt 0 ] && s=0; "
                                + "dd if=/data/adb/lspd/config/modules_config.db bs=1 skip=$s count=600 2>/dev/null | od -A d -c | head -n 40; fi", 20),
                new Section("D. 已装版本",
                        "dumpsys package " + PKG + " 2>&1 | grep -E \"versionCode|versionName\" | head -n 4", 15),
                new Section("E. APK 入口文件",
                        "APK=$(pm path " + PKG + " | head -n 1 | cut -d: -f2); "
                                + "unzip -l \"$APK\" 2>/dev/null | grep -iE \"xposed_init|java_init|module.prop\" | head -n 8", 15),
                new Section("F. App 开关真实值 + 兜底文件",
                        "cat /data/data/" + PKG + "/shared_prefs/" + PKG + "_preferences.xml 2>&1 | head -n 12; "
                                + "echo \"---lspd---\"; ls -l /data/adb/lspd/modules/0/" + PKG + "/ 2>&1 | tail -n 5; "
                                + "cat /data/adb/lspd/modules/0/" + PKG + "/config.properties 2>&1 | head -n 6", 20),
                new Section("G. Hook 是否触发（本模块日志）",
                        "logcat -d -s NotificationSinking:E | grep -iE \"HOOK fired|HOOK skipped|HOOK OK|hooks installed\" "
                                + "| tail -n 20; echo \"(若只有 HOOK OK 没有 fired = 系统没调用被 Hook 的方法)\"", 20),
        };
    }
}
