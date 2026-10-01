package net.wastu.solipsist;

import android.content.Intent;
import android.content.ContentValues;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * System Server & SettingsProvider Privacy Shield:
 * Globally cloaks adb, developer options, and accessibility services across the entire system
 * for all third-party applications (callingUid >= 10000), eliminating the need to manually
 * configure per-app scopes in LSPosed.
 */
public class SystemServerPrivacyShield {

    private static final String TAG = "[Solipsist-SysServer]";

    private static final Set<String> ZERO_SETTINGS = new HashSet<String>(Arrays.asList(
        "adb_enabled",
        "development_settings_enabled",
        "adb_wifi_enabled",
        "accessibility_enabled",
        "touch_exploration_enabled"
    ));

    private static final Set<String> EMPTY_SETTINGS = new HashSet<String>(Arrays.asList(
        "enabled_accessibility_services",
        "accessibility_shortcut_target_service",
        "accessibility_button_target_component"
    ));

    public static void init(LoadPackageParam lpparam) {
        XposedBridge.log(TAG + " Initializing System Server hooks in: " + lpparam.packageName);
        hookAccessibilityManagerService(lpparam);
        hookComputerEngine(lpparam);
    }

    public static void hookSettingsProvider(LoadPackageParam lpparam) {
        try {
            Class<?> spClass = XposedHelpers.findClassIfExists(
                "com.android.providers.settings.SettingsProvider",
                lpparam.classLoader
            );
            if (spClass == null) {
                RuntimeState.reportHook("System.SettingsProvider.call", new ClassNotFoundException("SettingsProvider"));
                return;
            }

            XC_MethodHook callHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int uid = Binder.getCallingUid();
                    param.setObjectExtra("solipsistCallingUid", uid);
                    int methodIndex = param.args.length >= 4 ? 1 : 0;
                    if (param.args.length <= methodIndex || !(param.args[methodIndex] instanceof String)) return;
                    String method = (String) param.args[methodIndex];
                    if (RuntimeProvider.RELAY_CONFIG.equals(method)) {
                        Bundle reply = new Bundle();
                        reply.putBoolean(RuntimeProvider.ENABLED, RuntimeState.isEnabled());
                        param.setResult(reply);
                    } else if (RuntimeProvider.RELAY_EVENTS.equals(method)) {
                        Bundle reply = new Bundle();
                        try {
                            Bundle input = (Bundle) param.args[param.args.length - 1];
                            if (input == null || !(param.thisObject instanceof android.content.ContentProvider)) {
                                param.setResult(reply);
                                return;
                            }
                            if (Build.VERSION.SDK_INT < 33) {
                                param.setResult(reply);
                                return;
                            }
                            ContentValues[] values = input.getParcelableArray("values", ContentValues.class);
                            if (values == null || values.length == 0 || values.length > 64) {
                                param.setResult(reply);
                                return;
                            }
                            Context context = ((android.content.ContentProvider) param.thisObject).getContext();
                            String[] packages = context.getPackageManager().getPackagesForUid(uid);
                            if (packages == null || packages.length == 0) {
                                param.setResult(reply);
                                return;
                            }
                            for (ContentValues value : values) {
                                if (value == null) continue;
                                value.put("package", packages[0]);
                                if ("count".equals(value.getAsString("kind"))) value.put("targetUid", uid);
                            }
                            long token = Binder.clearCallingIdentity();
                            try {
                                int accepted = context.getContentResolver().bulkInsert(RuntimeProvider.EVENTS_URI, values);
                                reply.putInt("accepted", accepted);
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        } catch (Throwable t) {
                            RuntimeState.reportHook("System.SettingsProvider.relay", t);
                        }
                        param.setResult(reply);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof android.content.ContentProvider) {
                        RuntimeState.attachContext(((android.content.ContentProvider) param.thisObject).getContext());
                    }
                    if (!RuntimeState.isEnabled()) return;
                    int callingUid = (Integer) param.getObjectExtra("solipsistCallingUid");
                    if (AppFilter.isExemptUid(callingUid)) {
                        return; // Allow system and camera callers
                    }

                    // AOSP call signatures:
                    // call(String method, String request, Bundle args)
                    // call(String authority, String method, String request, Bundle args)
                    String method = null;
                    String request = null;

                    if (param.args.length >= 3 && param.args[0] instanceof String && param.args[1] instanceof String) {
                        if (param.args[0].toString().startsWith("GET_")) {
                            method = (String) param.args[0];
                            request = (String) param.args[1];
                        } else if (param.args.length >= 4 && param.args[1] instanceof String && param.args[2] instanceof String) {
                            method = (String) param.args[1];
                            request = (String) param.args[2];
                        }
                    }

                    if (method != null && method.startsWith("GET_") && request != null) {
                        if (ZERO_SETTINGS.contains(request)) {
                            RuntimeState.count("settings", callingUid);
                            Bundle b = (Bundle) param.getResult();
                            if (b == null) b = new Bundle();
                            b.putString("value", "0");
                            param.setResult(b);
                            XposedBridge.log(TAG + " Cloaked setting " + request + " -> 0 for UID " + callingUid);
                        } else if (EMPTY_SETTINGS.contains(request)) {
                            RuntimeState.count("settings", callingUid);
                            Bundle b = (Bundle) param.getResult();
                            if (b == null) b = new Bundle();
                            b.putString("value", "");
                            param.setResult(b);
                            XposedBridge.log(TAG + " Cloaked setting " + request + " -> '' for UID " + callingUid);
                        }
                    }
                }
            };

            int installed = 0;
            for (Method m : spClass.getDeclaredMethods()) {
                if ("call".equals(m.getName())) {
                    XposedBridge.hookMethod(m, callHook);
                    installed++;
                    XposedBridge.log(TAG + " Successfully hooked SettingsProvider.call (" + m.getParameterCount() + " params)");
                    RuntimeState.reportInstalled("System.SettingsProvider.call");
                }
            }
            if (installed == 0) RuntimeState.reportHook("System.SettingsProvider.call", new NoSuchMethodException("call"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking SettingsProvider: " + t.getMessage());
            RuntimeState.reportHook("System.SettingsProvider.call", t);
        }
    }

    private static void hookAccessibilityManagerService(LoadPackageParam lpparam) {
        try {
            Class<?> amsClass = XposedHelpers.findClassIfExists(
                "com.android.server.accessibility.AccessibilityManagerService",
                lpparam.classLoader
            );
            if (amsClass == null) {
                RuntimeState.reportHook("System.AccessibilityManagerService", new ClassNotFoundException("AccessibilityManagerService"));
                return;
            }

            // 1. Hook addClient to mask out enabled flags for non-system clients
            for (Method m : amsClass.getDeclaredMethods()) {
                if ("addClient".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setObjectExtra("solipsistCallingUid", Binder.getCallingUid());
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!RuntimeState.isEnabled()) return;
                            int callingUid = (Integer) param.getObjectExtra("solipsistCallingUid");
                            if (!AppFilter.isExemptUid(callingUid)) {
                                RuntimeState.count("accessibility", callingUid);
                                Object result = param.getResult();
                                if (result instanceof Long) {
                                    long val = ((Long) result).longValue();
                                    val &= ~0x00000003L; // Clear enabled (0x1) and touch exploration (0x2)
                                    param.setResult(val);
                                } else if (result instanceof Integer) {
                                    int val = ((Integer) result).intValue();
                                    val &= ~0x00000003;
                                    param.setResult(val);
                                }
                            }
                        }
                    });
                    XposedBridge.log(TAG + " Successfully hooked AccessibilityManagerService.addClient");
                    RuntimeState.reportInstalled("System.AccessibilityManagerService.addClient");
                }

                // 2. Return empty ParceledListSlice for query of enabled/installed accessibility services
                if ("getEnabledAccessibilityServiceList".equals(m.getName())
                        || "getInstalledAccessibilityServiceList".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!RuntimeState.isEnabled()) return;
                            try {
                                int callingUid = Binder.getCallingUid();
                                if (!AppFilter.isExemptUid(callingUid)) {
                                    RuntimeState.count("accessibility", callingUid);
                                    Class<?> plsClass = XposedHelpers.findClassIfExists(
                                        "android.content.pm.ParceledListSlice",
                                        lpparam.classLoader
                                    );
                                    if (List.class.isAssignableFrom(((Method) param.method).getReturnType())) {
                                        param.setResult(Collections.emptyList());
                                    } else if (plsClass != null && plsClass.isAssignableFrom(((Method) param.method).getReturnType())) {
                                        Object emptySlice = XposedHelpers.callStaticMethod(plsClass, "emptyList");
                                        param.setResult(emptySlice);
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " Error cloaking " + param.method.getName() + ": " + t.getMessage());
                                RuntimeState.reportHook("System.AccessibilityManagerService." + param.method.getName(), t);
                            }
                        }
                    });
                    XposedBridge.log(TAG + " Successfully hooked AccessibilityManagerService." + m.getName());
                    RuntimeState.reportInstalled("System.AccessibilityManagerService." + m.getName());
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking AccessibilityManagerService: " + t.getMessage());
            RuntimeState.reportHook("System.AccessibilityManagerService", t);
        }
    }

    private static void hookComputerEngine(LoadPackageParam lpparam) {
        try {
            Class<?> ceClass = XposedHelpers.findClassIfExists(
                "com.android.server.pm.ComputerEngine",
                lpparam.classLoader
            );
            if (ceClass == null) {
                RuntimeState.reportHook("System.ComputerEngine", new ClassNotFoundException("ComputerEngine"));
                return;
            }

            XC_MethodHook queryServicesHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setObjectExtra("solipsistCallingUid", Binder.getCallingUid());
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!RuntimeState.isEnabled()) return;
                    int callingUid = (Integer) param.getObjectExtra("solipsistCallingUid");
                    if (!AppFilter.isExemptUid(callingUid) && param.args.length > 0 && param.args[0] instanceof Intent) {
                        Intent intent = (Intent) param.args[0];
                        if (intent != null && "android.accessibilityservice.AccessibilityService".equals(intent.getAction())) {
                            RuntimeState.count("accessibility", callingUid);
                            param.setResult(Collections.emptyList());
                        }
                    }
                }
            };

            int installed = 0;
            for (Method m : ceClass.getDeclaredMethods()) {
                if ("queryIntentServicesInternal".equals(m.getName())) {
                    XposedBridge.hookMethod(m, queryServicesHook);
                    installed++;
                }
            }
            if (installed > 0) {
                XposedBridge.log(TAG + " Successfully hooked ComputerEngine.queryIntentServicesInternal for AccessibilityService");
                RuntimeState.reportInstalled("System.ComputerEngine.queryIntentServicesInternal");
            } else {
                RuntimeState.reportHook("System.ComputerEngine.queryIntentServicesInternal", new NoSuchMethodException("queryIntentServicesInternal"));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ComputerEngine: " + t.getMessage());
            RuntimeState.reportHook("System.ComputerEngine.queryIntentServicesInternal", t);
        }
    }
}
