package net.wastu.solipsist;

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

import java.util.ArrayList;

/** Cross-process config and write-only diagnostic ingress. */
public final class RuntimeProvider extends ContentProvider {
    static final String AUTHORITY = "net.wastu.solipsist.runtime";
    static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    static final Uri EVENTS_URI = Uri.parse("content://" + AUTHORITY + "/events");
    static final String PREFS = "module_state";
    static final String ENABLED = "enabled";
    private RuntimeStore store;

    @Override
    public boolean onCreate() {
        store = new RuntimeStore(getContext());
        return true;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!CONFIG_URI.equals(uri)) return null;
        MatrixCursor cursor = new MatrixCursor(new String[]{ENABLED});
        cursor.addRow(new Object[]{prefs(getContext()).getBoolean(ENABLED, true) ? 1 : 0});
        return cursor;
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        if (!EVENTS_URI.equals(uri) || values == null || values.length == 0 || values.length > 64) return 0;
        int uid = Binder.getCallingUid();
        String caller = "";
        if (uid >= 10000) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages == null || packages.length == 0) return 0;
            caller = packages[0];
        } else if (uid != Process.SYSTEM_UID && uid != Process.myUid()) {
            return 0;
        }
        boolean privilegedReporter = uid < 10000
                || "com.android.providers.media.module".equals(caller)
                || "com.android.providers.media".equals(caller)
                || "com.google.android.providers.media.module".equals(caller)
                || "com.android.providers.settings".equals(caller);
        ArrayList<Bundle> events = new ArrayList<>();
        for (ContentValues value : values) {
            if (value == null) continue;
            Bundle event = new Bundle();
            for (String key : new String[]{"kind", "category", "package", "process", "hook", "state", "error"}) {
                event.putString(key, value.getAsString(key));
            }
            Integer delta = value.getAsInteger("delta");
            Integer targetUid = value.getAsInteger("targetUid");
            event.putInt("delta", delta == null ? 0 : delta);
            event.putInt("targetUid", targetUid == null ? -1 : targetUid);
            if ("count".equals(event.getString("kind")) && privilegedReporter) {
                if (event.getInt("targetUid") >= 10000) {
                    String[] targetPackages = getContext().getPackageManager().getPackagesForUid(event.getInt("targetUid"));
                    if (targetPackages != null && targetPackages.length > 0) event.putString("package", targetPackages[0]);
                }
            }
            events.add(event);
        }
        store.record(events, caller, privilegedReporter);
        return values.length;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
