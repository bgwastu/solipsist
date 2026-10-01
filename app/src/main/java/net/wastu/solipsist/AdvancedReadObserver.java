package net.wastu.solipsist;

import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Observation only. These hooks never inspect arguments except a VPN transport constant. */
final class AdvancedReadObserver {
    private static final Set<Class<?>> WEB_SETTINGS_CLASSES = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private AdvancedReadObserver() {}

    static void install(LoadPackageParam lpparam) {
        watch(lpparam, "android.content.ClipboardManager", "getPrimaryClip", "clipboard");
        watch(lpparam, "android.content.ClipboardManager", "getText", "clipboard");
        watch(lpparam, "android.content.ClipboardManager", "hasPrimaryClip", "clipboard");
        watch(lpparam, "android.content.ClipboardManager", "getPrimaryClipDescription", "clipboard");
        watch(lpparam, "android.accounts.AccountManager", "getAccounts", "accounts");
        watch(lpparam, "android.accounts.AccountManager", "getAccountsByType", "accounts");
        watchWebSettings(lpparam);
        watch(lpparam, "android.webkit.WebSettings", "getDefaultUserAgent", "webview");
        watch(lpparam, "android.location.LocationManager", "getLastKnownLocation", "location");
        watch(lpparam, "android.location.LocationManager", "requestLocationUpdates", "location");
        watch(lpparam, "android.net.NetworkCapabilities", "hasTransport", "vpn");
        watch(lpparam, "java.net.NetworkInterface", "getNetworkInterfaces", "vpn");
    }

    private static void watchWebSettings(LoadPackageParam lpparam) {
        String hook = "Observe.WebSettings.getUserAgentString";
        try {
            Class<?> webView = XposedHelpers.findClass("android.webkit.WebView", lpparam.classLoader);
            Set<XC_MethodHook.Unhook> getters = XposedBridge.hookAllMethods(webView, "getSettings", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object settings = param.getResult();
                    if (settings == null || !WEB_SETTINGS_CLASSES.add(settings.getClass())) return;
                    try {
                        Set<XC_MethodHook.Unhook> installed = XposedBridge.hookAllMethods(settings.getClass(),
                                "getUserAgentString", new XC_MethodHook() {
                                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                                        RuntimeState.count("webview");
                                    }
                                });
                        if (installed.isEmpty()) RuntimeState.reportHook(hook, new NoSuchMethodException("getUserAgentString"));
                        else RuntimeState.reportInstalled(hook);
                    } catch (Throwable t) {
                        RuntimeState.reportHook(hook, t);
                    }
                }
            });
            if (getters.isEmpty()) RuntimeState.reportHook("Observe.WebView.getSettings", new NoSuchMethodException("getSettings"));
            else RuntimeState.reportInstalled("Observe.WebView.getSettings");
        } catch (Throwable t) {
            RuntimeState.reportHook("Observe.WebView.getSettings", t);
        }
    }

    private static void watch(LoadPackageParam lpparam, String className, String method, String category) {
        String hook = "Observe." + className.substring(className.lastIndexOf('.') + 1) + "." + method;
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                RuntimeState.reportHook(hook, new ClassNotFoundException(className));
                return;
            }
            Set<XC_MethodHook.Unhook> installed = XposedBridge.hookAllMethods(clazz, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if ("vpn".equals(category) && "hasTransport".equals(method)
                            && (param.args.length == 0 || !(param.args[0] instanceof Integer)
                            || ((Integer) param.args[0]) != 4)) return;
                    RuntimeState.count(category);
                }
            });
            if (installed.isEmpty()) RuntimeState.reportHook(hook, new NoSuchMethodException(method));
            else RuntimeState.reportInstalled(hook);
        } catch (Throwable t) {
            RuntimeState.reportHook(hook, t);
        }
    }
}
