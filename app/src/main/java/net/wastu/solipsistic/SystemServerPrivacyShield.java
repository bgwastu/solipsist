package net.wastu.solipsistic;

import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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

    private static final String TAG = "[Solipsistic-SysServer]";

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
        hookSettingsProvider(lpparam);
        hookComputerEngine(lpparam);
    }

    public static void hookSettingsProvider(LoadPackageParam lpparam) {
        try {
            Class<?> spClass = XposedHelpers.findClassIfExists(
                "com.android.providers.settings.SettingsProvider",
                lpparam.classLoader
            );
            if (spClass == null) {
                return;
            }

            XC_MethodHook callHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int callingUid = Binder.getCallingUid();
                    if (callingUid < 10000) {
                        return; // Allow system / root callers
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
                            Bundle b = (Bundle) param.getResult();
                            if (b == null) b = new Bundle();
                            b.putString("value", "0");
                            param.setResult(b);
                            XposedBridge.log(TAG + " Cloaked setting " + request + " -> 0 for UID " + callingUid);
                        } else if (EMPTY_SETTINGS.contains(request)) {
                            Bundle b = (Bundle) param.getResult();
                            if (b == null) b = new Bundle();
                            b.putString("value", "");
                            param.setResult(b);
                            XposedBridge.log(TAG + " Cloaked setting " + request + " -> '' for UID " + callingUid);
                        }
                    }
                }
            };

            for (Method m : spClass.getDeclaredMethods()) {
                if ("call".equals(m.getName())) {
                    XposedBridge.hookMethod(m, callHook);
                    XposedBridge.log(TAG + " Successfully hooked SettingsProvider.call (" + m.getParameterCount() + " params)");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking SettingsProvider: " + t.getMessage());
        }
    }

    private static void hookAccessibilityManagerService(LoadPackageParam lpparam) {
        try {
            Class<?> amsClass = XposedHelpers.findClassIfExists(
                "com.android.server.accessibility.AccessibilityManagerService",
                lpparam.classLoader
            );
            if (amsClass == null) {
                return;
            }

            // 1. Hook addClient to mask out enabled flags for non-system clients
            for (Method m : amsClass.getDeclaredMethods()) {
                if ("addClient".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            int callingUid = Binder.getCallingUid();
                            if (callingUid >= 10000) {
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
                }

                // 2. Return empty ParceledListSlice for query of enabled/installed accessibility services
                if ("getEnabledAccessibilityServiceList".equals(m.getName())
                        || "getInstalledAccessibilityServiceList".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int callingUid = Binder.getCallingUid();
                                if (callingUid >= 10000) {
                                    Class<?> plsClass = XposedHelpers.findClassIfExists(
                                        "android.content.pm.ParceledListSlice",
                                        lpparam.classLoader
                                    );
                                    if (plsClass != null) {
                                        Object emptySlice = XposedHelpers.callStaticMethod(plsClass, "emptyList");
                                        param.setResult(emptySlice);
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " Error cloaking " + param.method.getName() + ": " + t.getMessage());
                            }
                        }
                    });
                    XposedBridge.log(TAG + " Successfully hooked AccessibilityManagerService." + m.getName());
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking AccessibilityManagerService: " + t.getMessage());
        }
    }

    private static void hookComputerEngine(LoadPackageParam lpparam) {
        try {
            Class<?> ceClass = XposedHelpers.findClassIfExists(
                "com.android.server.pm.ComputerEngine",
                lpparam.classLoader
            );
            if (ceClass == null) {
                return;
            }

            XC_MethodHook queryServicesHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int callingUid = Binder.getCallingUid();
                    if (callingUid >= 10000 && param.args.length > 0 && param.args[0] instanceof Intent) {
                        Intent intent = (Intent) param.args[0];
                        if (intent != null && "android.accessibilityservice.AccessibilityService".equals(intent.getAction())) {
                            param.setResult(Collections.emptyList());
                        }
                    }
                }
            };

            for (Method m : ceClass.getDeclaredMethods()) {
                if ("queryIntentServicesInternal".equals(m.getName())) {
                    XposedBridge.hookMethod(m, queryServicesHook);
                }
            }
            XposedBridge.log(TAG + " Successfully hooked ComputerEngine.queryIntentServicesInternal for AccessibilityService");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ComputerEngine: " + t.getMessage());
        }
    }
}
