package net.wastu.solipsist;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Comparator;
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

    static final class Event {
        final boolean check;
        final long time;
        final String packageName;
        final String process;
        final String name;
        final String state;
        final String error;
        final long count;

        Event(boolean check, long time, String packageName, String process, String name,
                String state, String error, long count) {
            this.check = check;
            this.time = time;
            this.packageName = packageName;
            this.process = process;
            this.name = name;
            this.state = state;
            this.error = error;
            this.count = count;
        }
    }

    static final class Snapshot {
        final long checks;
        final int apps;
        final int issues;
        final List<Event> events;

        Snapshot(long checks, int apps, int issues, List<Event> events) {
            this.checks = checks;
            this.apps = apps;
            this.issues = issues;
            this.events = events;
        }
    }

    synchronized Snapshot snapshot() {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        prune(db, now);
        long checks = 0;
        int apps = 0;
        int issues = 0;
        try (Cursor c = db.rawQuery("SELECT COALESCE(SUM(total), 0), COUNT(DISTINCT package) FROM counts", null)) {
            if (c.moveToFirst()) {
                checks = c.getLong(0);
                apps = c.getInt(1);
            }
        }
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM hooks WHERE state = 'failed'", null)) {
            if (c.moveToFirst()) issues = c.getInt(0);
        }
        ArrayList<Event> events = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT bucket, package, category, total FROM counts "
                + "ORDER BY bucket DESC LIMIT 200", null)) {
            while (c.moveToNext()) {
                events.add(new Event(true, c.getLong(0), c.getString(1), "", c.getString(2),
                        "", "", c.getLong(3)));
            }
        }
        for (String state : new String[] {"failed", "installed"}) {
            try (Cursor c = db.rawQuery("SELECT time, package, process, hook, state, error FROM hooks "
                    + "WHERE state = ? ORDER BY time DESC LIMIT 200", new String[] {state})) {
                while (c.moveToNext()) {
                    events.add(new Event(false, c.getLong(0), c.getString(1), c.getString(2),
                            c.getString(3), c.getString(4), c.getString(5), 0));
                }
            }
        }
        events.sort(Comparator.comparingLong((Event event) -> event.time).reversed());
        return new Snapshot(checks, apps, issues, events);
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
