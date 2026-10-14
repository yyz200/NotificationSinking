package com.example.notificationsinking;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 最小文件分享通道（不依赖 androidx FileProvider）：
 * 只允许读取本 App 外部私有目录 / 缓存目录下的日志文件，供 ACTION_SEND 分享给微信、邮件等。
 */
public class LogFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.notificationsinking.logs";

    public static Uri uriFor(Context ctx, File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(file.getName()));
    }

    private File resolve(String name) {
        Context ctx = getContext();
        if (ctx == null || name == null) return null;
        File external = ctx.getExternalFilesDir(null);
        if (external != null) {
            File f = new File(external, name);
            if (f.isFile()) return f;
        }
        File cached = new File(ctx.getCacheDir(), name);
        if (cached.isFile()) return cached;
        File internal = new File(ctx.getFilesDir(), name);
        if (internal.isFile()) return internal;
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri.getLastPathSegment());
        if (file == null) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
