package net.wastu.solipsist;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Build;
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
    private static boolean changeReceiverRegistered;
    private static boolean workerStarted;
    private static boolean syncFailureReported;
    private static boolean bridgeFailureReported;
    private static boolean bridgeInFlight;
    private static long bridgeStartedAt;
    private static long lastConfigRequest;
    private static volatile long providerRetryAfter;

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
                BroadcastReceiver changes = new BroadcastReceiver() {
                    @Override public void onReceive(Context ctx, Intent intent) {
                        WORKER.execute(RuntimeState::refreshConfig);
                    }
                };
                IntentFilter filter = new IntentFilter(RuntimeBridgeReceiver.ACTION_CHANGED);
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(changes, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(changes, filter);
                }
                changeReceiverRegistered = true;
                reportInstalled("Runtime.configBroadcast");
            } catch (Throwable t) {
                reportHook("Runtime.configBroadcast", t);
            }
            try {
                context.getContentResolver().registerContentObserver(RuntimeProvider.CONFIG_URI, false,
                        new ContentObserver(null) {
                            @Override public void onChange(boolean selfChange) { WORKER.execute(RuntimeState::refreshConfig); }
                        });
                observerRegistered = true;
            } catch (Throwable t) {
                if (!changeReceiverRegistered) reportHook("Runtime.configObserver", t);
            }
            if (!workerStarted) {
                workerStarted = true;
                long refreshSeconds = observerRegistered || changeReceiverRegistered ? 30 : 1;
                WORKER.scheduleWithFixedDelay(RuntimeState::refreshConfig, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
                WORKER.scheduleWithFixedDelay(RuntimeState::flush, 5, 5, TimeUnit.SECONDS);
            }
        }
        refreshConfig();
        flush();
    }

    private static void refreshConfig() {
        Context ctx = context;
        if (ctx != null && System.currentTimeMillis() >= providerRetryAfter) {
            try (Cursor cursor = ctx.getContentResolver().query(RuntimeProvider.CONFIG_URI,
                    new String[]{RuntimeProvider.ENABLED}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    enabled = cursor.getInt(0) != 0;
                    syncFailureReported = false;
                    providerRetryAfter = 0;
                    return;
                }
            } catch (Throwable ignored) {
                // Package visibility can hide the provider from scoped apps.
            }
            providerRetryAfter = System.currentTimeMillis() + 60000;
        }
        if (requestBridgeConfig()) return;
        if (!readXposedFallback() && !syncFailureReported) {
            syncFailureReported = true;
            reportHook("Runtime.configSync", new IllegalStateException("config_unavailable"));
        }
    }

    private static Intent bridgeIntent(String action) {
        Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(MODULE_PACKAGE, RuntimeBridgeReceiver.class.getName()));
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        return intent;
    }

    private static boolean requestBridgeConfig() {
        Context ctx = context;
        if (ctx == null) return false;
        long now = System.currentTimeMillis();
        if (now - lastConfigRequest < 10000) return true;
        lastConfigRequest = now;
        try {
            ctx.sendOrderedBroadcast(bridgeIntent(RuntimeBridgeReceiver.ACTION_CONFIG), null,
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent intent) {
                            int result = getResultCode();
                            if (result == RuntimeBridgeReceiver.RESULT_ENABLED
                                    || result == RuntimeBridgeReceiver.RESULT_DISABLED) {
                                enabled = result == RuntimeBridgeReceiver.RESULT_ENABLED;
                                syncFailureReported = false;
                            } else if (!readXposedFallback() && !syncFailureReported) {
                                syncFailureReported = true;
                                reportHook("Runtime.configSync", new IllegalStateException("bridge_unavailable"));
                            }
                        }
                    }, null, 0, null, null);
            return true;
        } catch (Throwable t) {
            reportHook("Runtime.configBridge", t);
            return false;
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
            if (bridgeInFlight) {
                if (System.currentTimeMillis() - bridgeStartedAt < 30000) return;
                bridgeInFlight = false;
            }
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
            if (System.currentTimeMillis() >= providerRetryAfter) {
                if (ctx.getContentResolver().bulkInsert(RuntimeProvider.EVENTS_URI, values) == values.length) {
                    acknowledge(batch);
                    return;
                }
                providerRetryAfter = System.currentTimeMillis() + 60000;
            }
        } catch (Throwable t) {
            // Try explicit broadcast delivery for scoped apps that cannot resolve the provider.
            providerRetryAfter = System.currentTimeMillis() + 60000;
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
            synchronized (LOCK) {
                bridgeInFlight = true;
                bridgeStartedAt = System.currentTimeMillis();
            }
            Intent intent = bridgeIntent(RuntimeBridgeReceiver.ACTION_EVENTS);
            intent.putExtra(RuntimeBridgeReceiver.EXTRA_VALUES, values);
            ctx.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (getResultCode() == RuntimeBridgeReceiver.RESULT_ACCEPTED) {
                        acknowledge(batch);
                        bridgeFailureReported = false;
                    } else if (!bridgeFailureReported) {
                        bridgeFailureReported = true;
                        reportHook("Runtime.eventBridge", new IllegalStateException("delivery_failed"));
                    }
                    synchronized (LOCK) { bridgeInFlight = false; }
                }
            }, null, 0, null, null);
        } catch (Throwable t) {
            synchronized (LOCK) { bridgeInFlight = false; }
            if (!bridgeFailureReported) {
                bridgeFailureReported = true;
                reportHook("Runtime.eventBridge", t);
            }
        }
    }

    private static void acknowledge(ArrayList<Bundle> batch) {
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
    }
}
