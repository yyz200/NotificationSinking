package com.example.notificationsinking.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.example.notificationsinking.ConfigStore;
import com.example.notificationsinking.ConfigWriter;
import com.example.notificationsinking.Diag;
import com.example.notificationsinking.HookLog;
import com.example.notificationsinking.LogFileProvider;
import com.example.notificationsinking.R;
import com.example.notificationsinking.RemotePrefsBridge;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 模块设置界面：
 *  - 总开关（控制整个下沉功能）
 *  - 锁屏下沉开关
 *  - AOD 下沉开关
 *  - 下沉值滑块（0–150dp，步长 4dp，默认 40dp）
 *  - 实时预览：模拟通知卡片随滑块即时下移（深色锁屏风格预览区）
 *  - 作用域管理：root 下一键重启 SystemUI / AOD 进程，免整机重启
 *
 * 所有修改立即写入模块 App 默认 SharedPreferences（与 Provider 同一文件），
 * 并通过 notifyChange 通知 SystemUI / AOD 进程 —— 配置即时生效，无需重启。
 */
public class SettingsActivity extends Activity
        implements CompoundButton.OnCheckedChangeListener, SeekBar.OnSeekBarChangeListener {

    private Switch mSwitchMaster;
    private Switch mSwitchLockscreen;
    private Switch mSwitchAod;
    private Switch mSwitchKeepClock;
    private Switch mSwitchFold;
    private Button mBtnStyleAuto;
    private Button mBtnStyle0;
    private Button mBtnStyle1;
    private Button mBtnStyle2;
    private TextView mTextStyleStatus;
    private TextView mTextStatusPill;
    private SeekBar mSeekSink;
    private TextView mTextSinkValue;
    private TextView mTextHint;
    private TextView mTextDiagStatus;
    private TextView mTextPreviewNote;
    private View mPreviewArea;
    private int mPreviewBaseTopPx = -1;
    private View mPreviewCard;
    private Button mBtnRestartScope;
    private Button mBtnViewLogs;
    private Button mBtnRestartAndLogs;
    private Button mBtnBriefLogs;
    private Button mBtnExportLogs;

    private SharedPreferences mPrefs;
    private boolean mLoading = false;
    private boolean mRestarting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HookLog.i("app: SettingsActivity.onCreate v" + appVersion()); // 版本标记，便于日志核对
        setContentView(R.layout.activity_settings);

        mSwitchMaster = findViewById(R.id.switch_master);
        mSwitchLockscreen = findViewById(R.id.switch_lockscreen);
        mSwitchAod = findViewById(R.id.switch_aod);
        mSwitchKeepClock = findViewById(R.id.switch_keep_clock);
        mSwitchFold = findViewById(R.id.switch_fold);
        mBtnStyleAuto = findViewById(R.id.btn_style_auto);
        mBtnStyle0 = findViewById(R.id.btn_style_0);
        mBtnStyle1 = findViewById(R.id.btn_style_1);
        mBtnStyle2 = findViewById(R.id.btn_style_2);
        mTextStyleStatus = findViewById(R.id.text_style_status);
        mTextStatusPill = findViewById(R.id.text_status_pill);
        mSeekSink = findViewById(R.id.seek_sink);
        mTextSinkValue = findViewById(R.id.text_sink_value);
        mTextHint = findViewById(R.id.text_hint);
        mTextDiagStatus = findViewById(R.id.text_diag_status);
        mTextPreviewNote = findViewById(R.id.text_preview_note);
        mPreviewArea = findViewById(R.id.preview_area);
        mPreviewCard = findViewById(R.id.preview_card);
        mPreviewCard.post(() -> {
            try {
                mPreviewBaseTopPx = mPreviewCard.getTop();
            } catch (Throwable ignored) {
            }
            updateUi();
        });
        mBtnRestartScope = findViewById(R.id.btn_restart_scope);
        mBtnViewLogs = findViewById(R.id.btn_view_logs);
        mBtnRestartAndLogs = findViewById(R.id.btn_restart_and_logs);
        mBtnBriefLogs = findViewById(R.id.btn_brief_logs);
        mBtnExportLogs = findViewById(R.id.btn_export_logs);

        // 必须与 ConfigStore.PREFS_NAME / ContentProvider 读取的文件一致
        mPrefs = getSharedPreferences(ConfigStore.PREFS_NAME, MODE_PRIVATE);

        // 现代框架（LSPosed 2.x）配置通道：服务绑定后自动把本地配置镜像进远程偏好
        RemotePrefsBridge.init(mPrefs);

        mSwitchMaster.setOnCheckedChangeListener(this);
        mSwitchLockscreen.setOnCheckedChangeListener(this);
        mSwitchAod.setOnCheckedChangeListener(this);
        mSwitchKeepClock.setOnCheckedChangeListener(this);
        mSwitchFold.setOnCheckedChangeListener(this);
        mSeekSink.setOnSeekBarChangeListener(this);

        mBtnRestartScope.setOnClickListener(v -> confirmRestartScope());
        mBtnViewLogs.setOnClickListener(v -> viewLogs(false, false));
        mBtnRestartAndLogs.setOnClickListener(v -> viewLogs(true, false));
        mBtnBriefLogs.setOnClickListener(v -> viewLogs(false, true));
        mBtnExportLogs.setOnClickListener(v -> exportLogs());
        mBtnStyleAuto.setOnClickListener(v -> applyLockStyle(-1));
        mBtnStyle0.setOnClickListener(v -> applyLockStyle(0));
        mBtnStyle1.setOnClickListener(v -> applyLockStyle(1));
        mBtnStyle2.setOnClickListener(v -> applyLockStyle(2));

        loadState();
        updateUi();
        writeRootConfig(); // 启动即播种兜底配置文件（root 通道），保证 SystemUI 能读到当前配置
    }

    /** 兜底配置通道：把当前配置写入 root 文件通道（见 ConfigWriter）。 */
    private void writeRootConfig() {
        final SharedPreferences prefs = mPrefs;
        new Thread(() -> ConfigWriter.writeFromPrefs(this, prefs), "write-root-config").start();
    }

    /** 写入锁屏通知显示方式（-1＝跟随系统）并同步系统键。 */
    private void applyLockStyle(int style) {
        mPrefs.edit().putInt(ConfigStore.KEY_LOCK_STYLE, style).apply();
        new Thread(() -> {
            if (style >= 0) {
                ConfigWriter.runRoot("settings put system lock_screen_notification_style " + style, 15);
            }
            ConfigWriter.writeFromPrefs(this, mPrefs);
            final String cur = readLockStyle();
            runOnUiThread(() -> {
                Toast.makeText(this, getString(R.string.toast_style_applied,
                                style < 0 ? getString(R.string.style_auto) : ("样式 " + style)),
                        Toast.LENGTH_SHORT).show();
                updateStyleStatus(cur);
            });
        }, "apply-lock-style").start();
    }

    /** 读取系统当前的 lock_screen_notification_style。 */
    private String readLockStyle() {
        try {
            String out = ConfigWriter.runRoot("settings get system lock_screen_notification_style", 10);
            return out == null ? "?" : out.trim();
        } catch (Throwable t) {
            return "?";
        }
    }

    private void updateStyleStatus(String systemValue) {
        int v = mPrefs.getInt(ConfigStore.KEY_LOCK_STYLE, -1);
        String label = v < 0 ? getString(R.string.style_auto) : ("样式 " + v);
        mTextStyleStatus.setText(getString(R.string.style_status_fmt, label,
                (systemValue == null || systemValue.isEmpty()) ? "?" : systemValue));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到界面都同步一次：镜像到框架远程偏好 + 写入兜底文件
        try {
            RemotePrefsBridge.mirror();
        } catch (Throwable ignored) {
        }
        writeRootConfig();
        new Thread(() -> {
            final String cur = readLockStyle();
            runOnUiThread(() -> updateStyleStatus(cur));
        }, "read-lock-style").start();
    }

    private void loadState() {
        mLoading = true;
        mSwitchMaster.setChecked(mPrefs.getBoolean(ConfigStore.KEY_ENABLED, false));
        mSwitchLockscreen.setChecked(mPrefs.getBoolean(ConfigStore.KEY_LOCKSCREEN, true));
        mSwitchAod.setChecked(mPrefs.getBoolean(ConfigStore.KEY_AOD, true));
        mSwitchKeepClock.setChecked(mPrefs.getBoolean(ConfigStore.KEY_KEEP_CLOCK, false));
        mSwitchFold.setChecked(mPrefs.getBoolean(ConfigStore.KEY_FOLD, false));
        mSeekSink.setProgress(mPrefs.getInt(ConfigStore.KEY_SINK_DP, ConfigStore.SINK_DP_DEFAULT));
        mLoading = false;
    }

    /** 写入配置并通知跨进程观察者。 */
    private void persist() {
        int dp = sinkDp();
        mPrefs.edit()
                .putBoolean(ConfigStore.KEY_ENABLED, mSwitchMaster.isChecked())
                .putBoolean(ConfigStore.KEY_LOCKSCREEN, mSwitchLockscreen.isChecked())
                .putBoolean(ConfigStore.KEY_AOD, mSwitchAod.isChecked())
                .putBoolean(ConfigStore.KEY_KEEP_CLOCK, mSwitchKeepClock.isChecked())
                .putBoolean(ConfigStore.KEY_FOLD, mSwitchFold.isChecked())
                .putInt(ConfigStore.KEY_SINK_DP, dp)
                .apply();
        try {
            getContentResolver().notifyChange(ConfigStore.PREFS_URI, null);
        } catch (Throwable ignored) {
        }
        // 现代框架通道：镜像进 LSPosed 远程偏好（SystemUI 侧实时监听）
        RemotePrefsBridge.mirror();
        // 兜底通道：root 写配置文件（SystemUI 直读 + FileObserver）
        writeRootConfig();
    }

    /** 滑块原始值 → 步长 4dp 且范围 0..150 的取值。 */
    private int sinkDp() {
        int v = mSeekSink.getProgress();
        int rounded = Math.round(v / (float) ConfigStore.SINK_DP_STEP) * ConfigStore.SINK_DP_STEP;
        return Math.max(0, Math.min(ConfigStore.SINK_DP_MAX, rounded));
    }

    /** 刷新 UI 状态与预览卡片偏移。 */
    private void updateUi() {
        boolean master = mSwitchMaster.isChecked();
        mSwitchLockscreen.setEnabled(master);
        mSwitchAod.setEnabled(master);
        mSwitchKeepClock.setEnabled(master);
        mSwitchFold.setEnabled(master);
        mSeekSink.setEnabled(master);

        // 顶部状态胶囊
        mTextStatusPill.setText(master ? R.string.pill_on : R.string.pill_off);
        mTextStatusPill.setBackgroundResource(master ? R.drawable.bg_pill_on : R.drawable.bg_pill_off);
        mTextStatusPill.setTextColor(getResources().getColor(
                master ? R.color.pill_on_fg : R.color.pill_off_fg));

        int dp = sinkDp();
        mTextSinkValue.setText(getString(R.string.sink_value_fmt, dp));

        // 预览：总开关 + 锁屏开关打开时按当前下沉值下移；超出预览区可视高度时钳制并提示
        boolean active = master && mSwitchLockscreen.isChecked();
        float density = getResources().getDisplayMetrics().density;
        int desiredPx = active ? Math.round(dp * density) : 0;
        int areaH = mPreviewArea.getHeight();
        int cardH = mPreviewCard.getHeight();
        int baseTop = Math.max(mPreviewBaseTopPx, 0);
        int maxPx = (areaH > 0 && cardH > 0) ? Math.max(0, areaH - cardH - baseTop) : desiredPx;
        int appliedPx = Math.min(desiredPx, maxPx);
        mPreviewCard.setTranslationY(appliedPx);
        if (active && appliedPx < desiredPx) {
            mTextPreviewNote.setText(getString(R.string.preview_clamped_fmt,
                    Math.round(appliedPx / density), dp));
        } else if (active) {
            mTextPreviewNote.setText(getString(R.string.preview_ok_fmt, dp));
        } else {
            mTextPreviewNote.setText("");
        }

        mTextHint.setText(master ? R.string.hint_applied : R.string.hint_disabled);

        // 诊断状态：App 侧当前配置（便于确认开关确实已保存）
        mTextDiagStatus.setText(getString(R.string.diag_status_fmt,
                master ? "开" : "关",
                dp,
                (master && mSwitchLockscreen.isChecked()) ? "开" : "关",
                (master && mSwitchAod.isChecked()) ? "开" : "关"));
    }

    // ==================== 重启作用域 ====================

    private void confirmRestartScope() {
        if (mRestarting) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_restart_confirm_title)
                .setMessage(R.string.dlg_restart_confirm_msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> doRestartScope())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doRestartScope() {
        mRestarting = true;
        mBtnRestartScope.setEnabled(false);
        Toast.makeText(this, R.string.toast_restart_sending, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            final boolean ok = restartScopeProcesses();
            runOnUiThread(() -> {
                mRestarting = false;
                mBtnRestartScope.setEnabled(true);
                if (ok) {
                    Toast.makeText(this, R.string.toast_restart_done, Toast.LENGTH_LONG).show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.dlg_restart_failed_title)
                            .setMessage(R.string.dlg_restart_failed_msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            });
        }, "restart-scope").start();
    }

    /**
     * root 下重启 SystemUI / AOD / ExSystemUI 主进程。
     * 返回 true 表示至少成功杀掉了 SystemUI（其余进程不存在时 pkill 返回非 0，忽略）。
     */
    private static boolean restartScopeProcesses() {
        String[] cmds = {
                "pkill -f \"com.android.systemui$\"",
                "pkill -f \"com.oplus.aod$\"",
                "pkill -f \"com.oplus.exsystemui$\"",
        };
        boolean any = false;
        for (String c : cmds) {
            if (runAsRoot(c)) {
                any = true;
            }
        }
        return any;
    }

    /** 以 root 执行单条命令；8 秒超时（等待 Root 授权弹窗），失败返回 false。 */
    private static boolean runAsRoot(String cmd) {
        String[] suCandidates = {"su", "/system/bin/su", "/system/xbin/su"};
        for (String su : suCandidates) {
            Process p = null;
            try {
                p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
                final Process fp = p;
                // 后台排空输出，防止管道缓冲阻塞
                new Thread(() -> {
                    try (InputStream in = fp.getInputStream()) {
                        byte[] buf = new byte[256];
                        while (in.read(buf) != -1) {
                            // drain
                        }
                    } catch (Throwable ignored) {
                    }
                }, "su-drain").start();
                if (p.waitFor(8, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return true;
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
        return false;
    }

    // ==================== 诊断中心 ====================

    /**
     * 通过 Root 采集日志并在弹窗中展示。
     * @param restartFirst true 时先重启 SystemUI/AOD 进程、等待 4 秒再抓取
     * @param brief true 时只抓关键行（可一屏截图）
     */
    private void viewLogs(final boolean restartFirst, final boolean brief) {
        if (mRestarting) return;
        if (restartFirst) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dlg_restart_confirm_title)
                    .setMessage(R.string.dlg_restart_confirm_msg)
                    .setPositiveButton(android.R.string.ok, (d, w) -> doCaptureLogs(true, brief))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            doCaptureLogs(false, brief);
        }
    }

    /** 逐段执行诊断命令，逐段拼接输出（单段超时不影响其余段）。 */
    private String runSections(Diag.Section[] sections) {
        StringBuilder sb = new StringBuilder();
        for (Diag.Section s : sections) {
            sb.append("==== ").append(s.label).append(" ====\n");
            String out = runRootCommand(s.command, s.timeoutSec);
            if (out == null || out.trim().isEmpty()) {
                sb.append("(无输出或超时)\n");
            } else {
                sb.append(out);
                if (!out.endsWith("\n")) sb.append('\n');
            }
            sb.append('\n');
        }
        sb.append("==== EOS ====\n");
        return sb.toString();
    }

    private void doCaptureLogs(final boolean restartFirst, final boolean brief) {
        setDiagBusy(true);
        String toastMsg = restartFirst ? getString(R.string.toast_restart_sending) : "正在读取日志…";
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            String out;
            if (restartFirst) {
                runRootCommand("pkill -f \"com.android.systemui$\"; "
                        + "pkill -f \"com.oplus.aod$\"; "
                        + "pkill -f \"com.oplus.exsystemui$\"; "
                        + "sleep 4", 30);
                out = runSections(brief ? Diag.briefSections() : Diag.sections());
            } else {
                out = runSections(brief ? Diag.briefSections() : Diag.sections());
            }
            final String result = out;
            runOnUiThread(() -> {
                setDiagBusy(false);
                showLogDialog(result, brief ? R.string.dlg_brief_title : R.string.dlg_logs_title);
            });
        }, "capture-logs").start();
    }

    /** 采集完整日志并写入 txt（App 目录副本 + /sdcard/Download 副本），随后可分享/复制路径。 */
    private void exportLogs() {
        if (mRestarting) return;
        setDiagBusy(true);
        Toast.makeText(this, R.string.toast_exporting, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            String body = runSections(Diag.sections());
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String name = "NotificationSinking-log-" + stamp + ".txt";
            String header = "通知下沉 诊断日志\n"
                    + "生成时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n"
                    + "设备: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " (Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + ")\n"
                    + "应用版本: " + appVersion() + "\n"
                    + "----------------------------------------\n";
            String content = header + body;

            String appPath = null;
            String downloadPath = null;
            String error = null;
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File f = new File(dir, name);
                writeText(f, content);
                appPath = f.getAbsolutePath();
            } catch (Throwable t) {
                error = String.valueOf(t);
            }
            if (appPath != null) {
                String dest = "/sdcard/Download/" + name;
                String r = runRootCommand("cp \"" + appPath + "\" \"" + dest
                        + "\" && chmod 644 \"" + dest + "\" && echo COPIED", 15);
                if (r != null && r.contains("COPIED")) downloadPath = dest;
            }

            final String fAppPath = appPath;
            final String fDownloadPath = downloadPath;
            final String fError = error;
            final int size = content.length();
            runOnUiThread(() -> {
                setDiagBusy(false);
                if (fAppPath == null) {
                    Toast.makeText(this, getString(R.string.toast_export_failed, String.valueOf(fError)),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showExportDialog(fAppPath, fDownloadPath, name, size);
            });
        }, "export-logs").start();
    }

    private void showExportDialog(String appPath, String downloadPath, String name, int chars) {
        String shown = downloadPath != null ? downloadPath : appPath;
        String note = downloadPath != null ? "（App 私有目录另有副本，可直接分享）" : "（仅 App 私有目录）";
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_export_title)
                .setMessage(getString(R.string.dlg_export_msg, shown + "\n" + note,
                        "共 " + chars + " 字符"))
                .setPositiveButton(R.string.btn_share, (d, w) -> shareLogFile(name))
                .setNeutralButton(R.string.btn_copy_path, (d, w) -> copyToClipboard(shown))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void shareLogFile(String name) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File f = new File(dir, name);
            if (!f.isFile()) {
                Toast.makeText(this, R.string.toast_no_share, Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = LogFileProvider.uriFor(this, f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, name);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.btn_share)));
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.toast_export_failed, String.valueOf(t)),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("path", text));
                Toast.makeText(this, R.string.toast_path_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeText(File file, String content) throws Exception {
        try (java.io.OutputStreamWriter w =
                     new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), "UTF-8")) {
            w.write(content);
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private void setDiagBusy(boolean busy) {
        mRestarting = busy;
        mBtnViewLogs.setEnabled(!busy);
        mBtnRestartAndLogs.setEnabled(!busy);
        mBtnBriefLogs.setEnabled(!busy);
        mBtnExportLogs.setEnabled(!busy);
        mBtnRestartScope.setEnabled(!busy);
    }

    /** 弹窗展示日志（可选中复制）。 */
    private void showLogDialog(String logText, int titleRes) {
        boolean empty = logText == null || logText.trim().isEmpty();
        TextView tv = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11f);
        tv.setText(empty ? getString(R.string.dlg_logs_empty) : logText);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 以 root 执行命令并返回输出（失败/超时返回空串）。 */
    private static String runRootCommand(String cmd, int timeoutSec) {
        String[] suCandidates = {"su", "/system/bin/su", "/system/xbin/su"};
        for (String su : suCandidates) {
            Process p = null;
            try {
                p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
                final Process fp = p;
                final StringBuilder sb = new StringBuilder();
                Thread reader = new Thread(() -> {
                    try (InputStream in = fp.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            String s = new String(buf, 0, n, "UTF-8");
                            synchronized (sb) {
                                if (sb.length() < 200000) {
                                    sb.append(s);
                                }
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

    // ==================== 监听回调 ====================

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (mLoading) return;
        updateUi();
        persist();
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (mLoading) return;
        updateUi(); // 拖动过程中实时预览，不写盘
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        persist(); // 松手落定后写入并同步
    }
}
