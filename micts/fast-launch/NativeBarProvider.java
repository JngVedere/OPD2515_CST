package dev.padhelper.fastcts;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Registration only. No queries, files, commands, or raw logs are exposed. */
public final class NativeBarProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingPermission("android.permission.DUMP", "ADB shell registration only");
        if (Binder.getCallingUid() != 2000) throw new SecurityException("ADB shell registration only");
        if (!NativeBarProtocol.REGISTER.equals(method)) throw new IllegalArgumentException("Unknown native-bar operation");
        return NativeBarBridge.register(extras);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
