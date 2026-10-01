package net.wastu.solipsist;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Observation only. These hooks never inspect arguments except a VPN transport constant. */
final class AdvancedReadObserver {
    private AdvancedReadObserver() {}

    static void install(LoadPackageParam lpparam) {
        watch(lpparam, "android.content.ClipboardManager", "getPrimaryClip", "clipboard");
        watch(lpparam, "android.content.ClipboardManager", "getText", "clipboard");
        watch(lpparam, "android.content.ClipboardManager", "hasPrimaryClip", "clipboard");
        watch(lpparam, "android.content.ClipboardManager", "getPrimaryClipDescription", "clipboard");
        watch(lpparam, "android.accounts.AccountManager", "getAccounts", "accounts");
        watch(lpparam, "android.accounts.AccountManager", "getAccountsByType", "accounts");
        watch(lpparam, "android.webkit.WebSettings", "getUserAgentString", "webview");
        watch(lpparam, "android.webkit.WebSettings", "getDefaultUserAgent", "webview");
        watch(lpparam, "android.location.LocationManager", "getLastKnownLocation", "location");
        watch(lpparam, "android.location.LocationManager", "requestLocationUpdates", "location");
        watch(lpparam, "android.net.NetworkCapabilities", "hasTransport", "vpn");
        watch(lpparam, "java.net.NetworkInterface", "getNetworkInterfaces", "vpn");
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
