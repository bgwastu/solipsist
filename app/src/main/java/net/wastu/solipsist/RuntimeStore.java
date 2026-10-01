package net.wastu.solipsist;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/** Local, bounded diagnostics. No API results or queried values are stored. */
final class RuntimeStore extends SQLiteOpenHelper {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long BUCKET_MS = 5L * 60L * 1000L;
    private static final int MAX_COUNT_ROWS = 5000;
    private static final int MAX_HOOK_ROWS = 10000;

    RuntimeStore(Context context) {
        super(context, "runtime_status.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE counts (bucket INTEGER NOT NULL, package TEXT NOT NULL, category TEXT NOT NULL, total INTEGER NOT NULL, PRIMARY KEY(bucket, package, category))");
        createHooks(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS hooks");
            createHooks(db);
        }
    }

    private static void createHooks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE hooks (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, package TEXT NOT NULL, process TEXT NOT NULL, hook TEXT NOT NULL, state TEXT NOT NULL, error TEXT NOT NULL, UNIQUE(package, process, hook))");
    }

    synchronized void record(List<Bundle> events, String callerPackage, boolean trustedSystem) {
        if (events == null || events.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            int accepted = 0;
            for (Bundle event : events) {
                if (event == null || ++accepted > 64) break;
                String pkg = trustedSystem ? safe(event.getString("package"), 120) : callerPackage;
                if (pkg.isEmpty()) pkg = "unknown";
                String kind = event.getString("kind", "");
                if ("count".equals(kind)) {
                    String category = safe(event.getString("category"), 40);
                    if (!RuntimeCategories.contains(category)) continue;
                    int delta = event.getInt("delta", 0);
                    if (delta < 1 || delta > 10000) continue;
                    long bucket = now / BUCKET_MS * BUCKET_MS;
                    db.execSQL("INSERT OR IGNORE INTO counts(bucket, package, category, total) VALUES(?, ?, ?, 0)",
                            new Object[]{bucket, pkg, category});
                    db.execSQL("UPDATE counts SET total = MIN(total + ?, 1000000) WHERE bucket = ? AND package = ? AND category = ?",
                            new Object[]{delta, bucket, pkg, category});
                } else if ("hook".equals(kind)) {
                    String hook = safe(event.getString("hook"), 100);
                    String state = safe(event.getString("state"), 16);
                    if (hook.isEmpty() || !("installed".equals(state) || "failed".equals(state))) continue;
                    db.execSQL("INSERT OR REPLACE INTO hooks(time, package, process, hook, state, error) VALUES(?, ?, ?, ?, ?, ?)",
                            new Object[]{now, pkg, safe(event.getString("process"), 100), hook, state,
                                    safe(event.getString("error"), 80)});
                }
            }
            prune(db, now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized String summary() {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        prune(db, now);
        StringBuilder out = new StringBuilder();
        try (Cursor c = db.rawQuery("SELECT category, SUM(total) FROM counts GROUP BY category ORDER BY category", null)) {
            while (c.moveToNext()) out.append(c.getString(0)).append(": ").append(c.getLong(1)).append('\n');
        }
        if (out.length() == 0) out.append("No queries recorded in the last 24 hours.\n");
        out.append("\nBy app\n");
        try (Cursor c = db.rawQuery("SELECT package, category, SUM(total) FROM counts GROUP BY package, category ORDER BY package, category", null)) {
            String last = "";
            while (c.moveToNext()) {
                String pkg = c.getString(0);
                if (!pkg.equals(last)) {
                    out.append(pkg).append('\n');
                    last = pkg;
                }
                out.append("  ").append(c.getString(1)).append(": ").append(c.getLong(2)).append('\n');
            }
        }
        out.append("\nHook reports: ");
        try (Cursor c = db.rawQuery("SELECT state, COUNT(*) FROM hooks GROUP BY state ORDER BY state", null)) {
            while (c.moveToNext()) out.append(c.getString(0)).append(' ').append(c.getLong(1)).append("  ");
        }
        out.append("\n\nHook failures\n");
        try (Cursor c = db.rawQuery("SELECT package, process, hook, error FROM hooks WHERE state = 'failed' ORDER BY id DESC LIMIT 40", null)) {
            if (c.getCount() == 0) out.append("No failures reported.\n");
            while (c.moveToNext()) {
                out.append(c.getString(0));
                if (!c.getString(1).isEmpty()) out.append(" (").append(c.getString(1)).append(')');
                out.append(": ").append(c.getString(2)).append(" — ").append(c.getString(3)).append('\n');
            }
        }
        out.append("\nRecent hook installations\n");
        try (Cursor c = db.rawQuery("SELECT package, process, hook FROM hooks WHERE state = 'installed' ORDER BY id DESC LIMIT 60", null)) {
            if (c.getCount() == 0) out.append("No hook reports yet.\n");
            while (c.moveToNext()) {
                out.append(c.getString(0));
                if (!c.getString(1).isEmpty()) out.append(" (").append(c.getString(1)).append(')');
                out.append(": ").append(c.getString(2)).append('\n');
            }
        }
        return out.toString();
    }

    private static void prune(SQLiteDatabase db, long now) {
        db.execSQL("DELETE FROM counts WHERE bucket < ?", new Object[]{now - DAY_MS});
        db.execSQL("DELETE FROM hooks WHERE time < ?", new Object[]{now - DAY_MS});
        db.execSQL("DELETE FROM counts WHERE rowid IN (SELECT rowid FROM counts ORDER BY bucket DESC LIMIT -1 OFFSET " + MAX_COUNT_ROWS + ")");
        db.execSQL("DELETE FROM hooks WHERE id IN (SELECT id FROM hooks ORDER BY id DESC LIMIT -1 OFFSET " + MAX_HOOK_ROWS + ")");
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("[^A-Za-z0-9._:$-]", "");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }
}
