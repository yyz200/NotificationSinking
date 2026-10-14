package com.example.notificationsinking;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * 配置跨进程通道（清理版）：被 Hook 进程通过 ContentResolver + ContentObserver 只读读取配置。
 * 只暴露几个开关数值（不含任何用户数据）；写入仅允许模块自身 uid，防第三方篡改。
 */
public class PrefsProvider extends ContentProvider {

    private static final String[] COLUMNS = {"_id"};
    private static final Object[] ROW = {1};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            Context ctx = getContext();
            if (ctx == null) return null;
            SharedPreferences sp = ctx.getSharedPreferences(
                    ConfigStore.PREFS_NAME, Context.MODE_PRIVATE);
            MatrixCursor mc = new MatrixCursor(COLUMNS);
            Bundle b = new Bundle();
            b.putBoolean(ConfigStore.KEY_ENABLED, sp.getBoolean(ConfigStore.KEY_ENABLED, false));
            b.putBoolean(ConfigStore.KEY_LOCKSCREEN, sp.getBoolean(ConfigStore.KEY_LOCKSCREEN, true));
            b.putBoolean(ConfigStore.KEY_AOD, sp.getBoolean(ConfigStore.KEY_AOD, true));
            b.putBoolean(ConfigStore.KEY_KEEP_CLOCK, sp.getBoolean(ConfigStore.KEY_KEEP_CLOCK, false));
            b.putBoolean(ConfigStore.KEY_FOLD, sp.getBoolean(ConfigStore.KEY_FOLD, false));
            b.putInt(ConfigStore.KEY_FOLD_MAX, sp.getInt(ConfigStore.KEY_FOLD_MAX, 2));
            b.putInt(ConfigStore.KEY_SINK_DP,
                    sp.getInt(ConfigStore.KEY_SINK_DP, ConfigStore.SINK_DP_DEFAULT));
            b.putInt(ConfigStore.KEY_SINK_DP_MEDIA, sp.getInt(ConfigStore.KEY_SINK_DP_MEDIA, -1));
            b.putInt(ConfigStore.KEY_CLASSIC_CAP, sp.getInt(ConfigStore.KEY_CLASSIC_CAP, 100));
            b.putInt(ConfigStore.KEY_LOCK_STYLE, sp.getInt(ConfigStore.KEY_LOCK_STYLE, -1));
            mc.setExtras(b);
            mc.addRow(ROW);
            return mc;
        } catch (Throwable t) {
            HookLog.e("PrefsProvider: query failed", t);
            return null;
        }
    }

    private static boolean mutateOnlySelf() {
        return Binder.getCallingUid() == Process.myUid();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return mutateOnlySelf() ? uri : null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + ConfigStore.MODULE_PACKAGE + ".prefs";
    }
}
