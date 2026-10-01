package net.wastu.solipsistic;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Privacy Shield Hook:
 * Cloaks developer options, USB debugging, wireless debugging, and active Accessibility Services
 * from target applications.
 */
public class PrivacyShieldHook {

    private static final String TAG = "[Solipsistic-Shield]";

    // Setting keys to cloak as "0"
    private static final Set<String> ZERO_SETTINGS = new HashSet<String>(Arrays.asList(
        "adb_enabled",
        "development_settings_enabled",
        "adb_wifi_enabled",
        "accessibility_enabled",
        "touch_exploration_enabled"
    ));

    // Setting keys to cloak as ""
    private static final Set<String> EMPTY_SETTINGS = new HashSet<String>(Arrays.asList(
        "enabled_accessibility_services",
        "accessibility_shortcut_target_service",
        "accessibility_button_target_component"
    ));

    public static void init(LoadPackageParam lpparam) {
        XposedBridge.log(TAG + " Activating Privacy Shield for package: " + lpparam.packageName);
        hookAccessibility(lpparam);
        hookSettings(lpparam);
        hookSystemProperties(lpparam);
        hookPackageManager(lpparam);
    }

    private static void hookAccessibility(LoadPackageParam lpparam) {
        try {
            Class<?> amClass = XposedHelpers.findClass("android.view.accessibility.AccessibilityManager", lpparam.classLoader);

            // 1. Force isEnabled() and isTouchExplorationEnabled() to always return false
            try {
                XposedHelpers.findAndHookMethod(amClass, "isEnabled", XC_MethodReplacement.returnConstant(false));
                XposedHelpers.findAndHookMethod(amClass, "isTouchExplorationEnabled", XC_MethodReplacement.returnConstant(false));
                XposedBridge.log(TAG + " Hooked AccessibilityManager.isEnabled & isTouchExplorationEnabled");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Error hooking isEnabled: " + t.getMessage());
            }

            // 2. Return empty lists for any query of active/installed accessibility services
            try {
                XC_MethodReplacement emptyListReplacement = XC_MethodReplacement.returnConstant(Collections.emptyList());
                for (Method m : amClass.getDeclaredMethods()) {
                    String name = m.getName();
                    if ("getEnabledAccessibilityServiceList".equals(name)
                            || "getInstalledAccessibilityServiceList".equals(name)
                            || "getAccessibilityServiceList".equals(name)) {
                        XposedBridge.hookMethod(m, emptyListReplacement);
                        XposedBridge.log(TAG + " Hooked AccessibilityManager." + name);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Error hooking getEnabledAccessibilityServiceList: " + t.getMessage());
            }

            // 3. Keep internal fields mIsEnabled & mIsTouchExplorationEnabled permanently false
            try {
                XposedBridge.hookAllConstructors(amClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            XposedHelpers.setBooleanField(param.thisObject, "mIsEnabled", false);
                            XposedHelpers.setBooleanField(param.thisObject, "mIsTouchExplorationEnabled", false);
                        } catch (Throwable ignored) {}
                    }
                });

                for (Method m : amClass.getDeclaredMethods()) {
                    String name = m.getName();
                    if ("setStateLocked".equals(name) || "setAccessibilityState".equals(name)) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args.length > 0 && param.args[0] instanceof Integer) {
                                    int flags = ((Integer) param.args[0]).intValue();
                                    // Clear STATE_FLAG_ACCESSIBILITY_ENABLED (0x01) and STATE_FLAG_TOUCH_EXPLORATION_ENABLED (0x02)
                                    flags &= ~0x00000003;
                                    param.args[0] = flags;
                                }
                            }
                        });
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Error hooking AccessibilityManager state updates: " + t.getMessage());
            }

            // 4. Dummy listeners
            try {
                for (Method m : amClass.getDeclaredMethods()) {
                    String name = m.getName();
                    if ("addAccessibilityStateChangeListener".equals(name)
                            || "addTouchExplorationStateChangeListener".equals(name)
                            || "addHighTextContrastStateChangeListener".equals(name)) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true));
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            XposedBridge.log(TAG + " AccessibilityManager class not found or error: " + t.getMessage());
        }
    }

    private static void hookSettings(LoadPackageParam lpparam) {
        // A. NameValueCache.getStringForUser (Central Settings cache used by Global, Secure, and System)
        try {
            Class<?> nvcClass = XposedHelpers.findClass("android.provider.Settings$NameValueCache", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                nvcClass,
                "getStringForUser",
                ContentResolver.class,
                String.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String name = (String) param.args[1];
                        if (name != null) {
                            if (ZERO_SETTINGS.contains(name)) {
                                param.setResult("0");
                            } else if (EMPTY_SETTINGS.contains(name)) {
                                param.setResult("");
                            }
                        }
                    }
                }
            );
            XposedBridge.log(TAG + " Hooked Settings$NameValueCache.getStringForUser");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Could not hook NameValueCache: " + t.getMessage());
        }

        // B. Direct Settings.Global & Settings.Secure getStringForUser fallback
        XC_MethodHook settingsStringHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String name = (String) param.args[1];
                if (name != null) {
                    if (ZERO_SETTINGS.contains(name)) {
                        param.setResult("0");
                    } else if (EMPTY_SETTINGS.contains(name)) {
                        param.setResult("");
                    }
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings$Global",
                lpparam.classLoader,
                "getStringForUser",
                ContentResolver.class,
                String.class,
                int.class,
                settingsStringHook
            );
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings$Secure",
                lpparam.classLoader,
                "getStringForUser",
                ContentResolver.class,
                String.class,
                int.class,
                settingsStringHook
            );
        } catch (Throwable ignored) {}

        // C. Direct Settings.Global & Settings.Secure getInt hooks
        XC_MethodHook getIntHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String name = (String) param.args[1];
                if (name != null && ZERO_SETTINGS.contains(name)) {
                    param.setResult(0);
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod("android.provider.Settings$Global", lpparam.classLoader, "getInt", ContentResolver.class, String.class, int.class, getIntHook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod("android.provider.Settings$Global", lpparam.classLoader, "getInt", ContentResolver.class, String.class, getIntHook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod("android.provider.Settings$Secure", lpparam.classLoader, "getInt", ContentResolver.class, String.class, int.class, getIntHook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod("android.provider.Settings$Secure", lpparam.classLoader, "getInt", ContentResolver.class, String.class, getIntHook);
        } catch (Throwable ignored) {}

        // D. ContentResolver.call interception for Settings IPC
        try {
            XposedHelpers.findAndHookMethod(
                ContentResolver.class,
                "call",
                Uri.class,
                String.class,
                String.class,
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String arg = (String) param.args[2];
                        if (arg != null) {
                            if (ZERO_SETTINGS.contains(arg)) {
                                Bundle b = (Bundle) param.getResult();
                                if (b == null) b = new Bundle();
                                b.putString("value", "0");
                                param.setResult(b);
                            } else if (EMPTY_SETTINGS.contains(arg)) {
                                Bundle b = (Bundle) param.getResult();
                                if (b == null) b = new Bundle();
                                b.putString("value", "");
                                param.setResult(b);
                            }
                        }
                    }
                }
            );
            XposedBridge.log(TAG + " Hooked ContentResolver.call for settings");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Could not hook ContentResolver.call: " + t.getMessage());
        }
    }

    private static void hookSystemProperties(LoadPackageParam lpparam) {
        try {
            Class<?> spClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader);

            XC_MethodHook propGetHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if (key == null) return;
                    if ("ro.debuggable".equals(key)) {
                        param.setResult("0");
                    } else if ("ro.secure".equals(key)) {
                        param.setResult("1");
                    } else if ("init.svc.adbd".equals(key)) {
                        param.setResult("stopped");
                    } else if ("service.adb.tcp.port".equals(key)) {
                        param.setResult("-1");
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if (key == null) return;
                    if ("sys.usb.config".equals(key) || "sys.usb.state".equals(key) || "persist.sys.usb.config".equals(key)) {
                        String val = (String) param.getResult();
                        if (val != null && val.contains("adb")) {
                            param.setResult(stripAdbFromUsbConfig(val));
                        }
                    }
                }
            };

            XC_MethodHook propGetIntHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if ("ro.debuggable".equals(key)) {
                        param.setResult(0);
                    } else if ("ro.secure".equals(key)) {
                        param.setResult(1);
                    } else if ("service.adb.tcp.port".equals(key)) {
                        param.setResult(-1);
                    }
                }
            };

            XC_MethodHook propGetBooleanHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if ("ro.debuggable".equals(key)) {
                        param.setResult(false);
                    } else if ("ro.secure".equals(key)) {
                        param.setResult(true);
                    }
                }
            };

            for (Method m : spClass.getDeclaredMethods()) {
                String name = m.getName();
                Class<?>[] params = m.getParameterTypes();
                if (params.length > 0 && params[0] == String.class) {
                    if ("get".equals(name) || "native_get".equals(name)) {
                        XposedBridge.hookMethod(m, propGetHook);
                    } else if ("getInt".equals(name) || "native_get_int".equals(name)) {
                        XposedBridge.hookMethod(m, propGetIntHook);
                    } else if ("getBoolean".equals(name) || "native_get_boolean".equals(name)) {
                        XposedBridge.hookMethod(m, propGetBooleanHook);
                    }
                }
            }
            XposedBridge.log(TAG + " Hooked SystemProperties for adb/debug properties");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking SystemProperties: " + t.getMessage());
        }
    }

    private static void hookPackageManager(LoadPackageParam lpparam) {
        try {
            Class<?> appPmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", lpparam.classLoader);
            XC_MethodHook queryHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Intent intent = (Intent) param.args[0];
                    if (intent != null) {
                        String action = intent.getAction();
                        if ("android.accessibilityservice.AccessibilityService".equals(action)) {
                            param.setResult(Collections.emptyList());
                        }
                    }
                }
            };

            for (Method m : appPmClass.getDeclaredMethods()) {
                String name = m.getName();
                if ("queryIntentServices".equals(name) || "queryIntentServicesAsUser".equals(name)) {
                    XposedBridge.hookMethod(m, queryHook);
                }
            }
            XposedBridge.log(TAG + " Hooked ApplicationPackageManager.queryIntentServices for AccessibilityService");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ApplicationPackageManager: " + t.getMessage());
        }
    }

    private static String stripAdbFromUsbConfig(String config) {
        if (config == null || config.isEmpty()) return config;
        List<String> parts = new ArrayList<String>(Arrays.asList(config.split(",")));
        parts.remove("adb");
        if (parts.isEmpty()) {
            return "none";
        }
        return String.join(",", parts);
    }
}
