package net.wastu.solipsist;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Hot-path hooks only read the volatile flag and update in-memory counts. */
final class RuntimeState {
    private static final String MODULE_PACKAGE = "net.wastu.solipsist";
    private static final Object LOCK = new Object();
    private static final Object FLUSH_LOCK = new Object();
    private static final LinkedHashMap<String, Integer> COUNTS = new LinkedHashMap<>();
    private static final ArrayList<Bundle> HOOKS = new ArrayList<>();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Solipsist-status");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean enabled = true;
    private static volatile Context context;
    private static volatile String processPackage = "";
    private static volatile String processName = "";
    private static boolean observerRegistered;
    private static boolean workerStarted;
    private static boolean syncFailureReported;

    private RuntimeState() {}

    static boolean isEnabled() { return enabled; }
    static boolean isKnownCategory(String category) { return RuntimeCategories.contains(category); }

    static void bootstrap(LoadPackageParam lpparam) {
        processPackage = lpparam.packageName;
        processName = lpparam.processName == null ? "" : lpparam.processName;
        readXposedFallback();
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    attachContext((Context) param.args[0]);
                }
            });
            reportInstalled("Runtime.Application.attach");
        } catch (Throwable t) {
            reportHook("Runtime.Application.attach", t);
        }
        if ("android".equals(lpparam.packageName) || "system".equals(lpparam.packageName)) {
            try {
                Class<?> threadClass = XposedHelpers.findClass("android.app.ActivityThread", null);
                Object thread = XposedHelpers.callStaticMethod(threadClass, "currentActivityThread");
                if (thread != null) attachContext((Context) XposedHelpers.callMethod(thread, "getSystemContext"));
            } catch (Throwable t) {
                reportHook("Runtime.systemContext", t);
            }
        }
    }

    static void attachContext(Context incoming) {
        if (incoming == null || context != null) return;
        synchronized (LOCK) {
            if (context != null) return;
            context = incoming.getApplicationContext() != null ? incoming.getApplicationContext() : incoming;
            try {
                context.getContentResolver().registerContentObserver(RuntimeProvider.CONFIG_URI, false,
                        new ContentObserver(null) {
                            @Override public void onChange(boolean selfChange) { WORKER.execute(RuntimeState::refreshConfig); }
                        });
                observerRegistered = true;
            } catch (Throwable t) {
                reportHook("Runtime.configObserver", t);
            }
            if (!workerStarted) {
                workerStarted = true;
                long refreshSeconds = observerRegistered ? 30 : 1;
                WORKER.scheduleWithFixedDelay(RuntimeState::refreshConfig, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
                WORKER.scheduleWithFixedDelay(RuntimeState::flush, 5, 5, TimeUnit.SECONDS);
            }
        }
        refreshConfig();
        flush();
    }

    private static void refreshConfig() {
        Context ctx = context;
        if (ctx != null) {
            try (Cursor cursor = ctx.getContentResolver().query(RuntimeProvider.CONFIG_URI,
                    new String[]{RuntimeProvider.ENABLED}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    enabled = cursor.getInt(0) != 0;
                    syncFailureReported = false;
                    return;
                }
            } catch (Throwable ignored) {
                // XSharedPreferences is the fallback for ROMs that block provider access.
            }
        }
        if (!readXposedFallback() && !syncFailureReported) {
            syncFailureReported = true;
            reportHook("Runtime.configSync", new IllegalStateException("config_unavailable"));
        }
    }

    private static boolean readXposedFallback() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(MODULE_PACKAGE, RuntimeProvider.PREFS);
            prefs.reload();
            if (!prefs.getFile().canRead()) return false;
            enabled = prefs.getBoolean(RuntimeProvider.ENABLED, true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void count(String category) { count(category, -1); }

    static void count(String category, int targetUid) {
        if (!enabled || !isKnownCategory(category)) return;
        String key = category + "|" + targetUid;
        synchronized (LOCK) {
            if (!COUNTS.containsKey(key) && COUNTS.size() >= 128) return;
            COUNTS.put(key, Math.min(10000, COUNTS.getOrDefault(key, 0) + 1));
        }
    }

    static void reportInstalled(String hook) { reportHook(hook, null); }

    static void reportHook(String hook, Throwable error) {
        if (error != null) XposedBridge.log("[Solipsist] " + hook + " failed: " + error.getClass().getSimpleName());
        Bundle event = new Bundle();
        event.putString("kind", "hook");
        event.putString("package", processPackage);
        event.putString("process", processName);
        event.putString("hook", hook);
        event.putString("state", error == null ? "installed" : "failed");
        event.putString("error", error == null ? "" : error.getClass().getSimpleName());
        synchronized (LOCK) {
            if (HOOKS.size() >= 200) HOOKS.remove(0);
            HOOKS.add(event);
        }
    }

    private static void flush() {
        synchronized (FLUSH_LOCK) { flushLocked(); }
    }

    private static void flushLocked() {
        Context ctx = context;
        if (ctx == null) return;
        ArrayList<Bundle> batch = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<String, Integer> entry : COUNTS.entrySet()) {
                if (batch.size() >= 48) break;
                String key = entry.getKey();
                int separator = key.lastIndexOf('|');
                Bundle event = new Bundle();
                event.putString("kind", "count");
                event.putString("category", key.substring(0, separator));
                event.putInt("targetUid", Integer.parseInt(key.substring(separator + 1)));
                event.putInt("delta", entry.getValue());
                event.putString("package", processPackage);
                batch.add(event);
            }
            for (int i = 0; i < HOOKS.size() && batch.size() < 64; i++) batch.add(HOOKS.get(i));
            if (batch.isEmpty()) return;
        }
        try {
            ContentValues[] values = new ContentValues[batch.size()];
            for (int i = 0; i < batch.size(); i++) {
                Bundle event = batch.get(i);
                ContentValues value = new ContentValues();
                for (String key : new String[]{"kind", "category", "package", "process", "hook", "state", "error"}) {
                    if (event.containsKey(key)) value.put(key, event.getString(key));
                }
                if (event.containsKey("delta")) value.put("delta", event.getInt("delta"));
                if (event.containsKey("targetUid")) value.put("targetUid", event.getInt("targetUid"));
                values[i] = value;
            }
            if (ctx.getContentResolver().bulkInsert(RuntimeProvider.EVENTS_URI, values) != values.length) return;
            synchronized (LOCK) {
                for (Bundle event : batch) {
                    if ("count".equals(event.getString("kind"))) {
                        String key = event.getString("category") + "|" + event.getInt("targetUid");
                        int left = COUNTS.getOrDefault(key, 0) - event.getInt("delta");
                        if (left > 0) COUNTS.put(key, left); else COUNTS.remove(key);
                    } else {
                        HOOKS.remove(event);
                    }
                }
            }
        } catch (Throwable t) {
            // Keep the bounded queue for the next flush; never block a hooked call.
        }
    }
}
