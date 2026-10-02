package net.wastu.solipsist;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Removes direct VPN metadata from network information delivered to ordinary apps. */
final class VpnPrivacyHook {
    private static final String VPN_SERVICE_PERMISSION = "android.permission.BIND_VPN_SERVICE";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private VpnPrivacyHook() {}

    static void install(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (INSTALLED.get()) return;
                    Context context = (Context) param.args[0];
                    if (context == null || isVpnProvider(context, lpparam.packageName)) return;
                    if (INSTALLED.compareAndSet(false, true)) installNetworkHooks(lpparam);
                }
            });
            RuntimeState.reportInstalled("Vpn.Application.attach");
        } catch (Throwable t) {
            RuntimeState.reportHook("Vpn.Application.attach", t);
        }
    }

    private static boolean isVpnProvider(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_SERVICES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (info.services != null) {
                for (ServiceInfo service : info.services) {
                    if (VPN_SERVICE_PERMISSION.equals(service.permission)) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            // An unknown package must not have its VPN control path changed.
            RuntimeState.reportHook("Vpn.providerCheck", t);
            return true;
        }
    }

    private static void installNetworkHooks(LoadPackageParam lpparam) {
        hookResult(ConnectivityManager.class, "getNetworkCapabilities", "Vpn.getNetworkCapabilities");
        hookResult(ConnectivityManager.class, "getLinkProperties", "Vpn.getLinkProperties");
        hookLegacyNetworkInfo();
        hookInterfaces();
        try {
            Class<?> callbackHandler = XposedHelpers.findClass(
                    "android.net.ConnectivityManager$CallbackHandler", lpparam.classLoader);
            hookResult(callbackHandler, "getObject", "Vpn.NetworkCallback");
        } catch (Throwable t) {
            RuntimeState.reportHook("Vpn.NetworkCallback", t);
        }
    }

    @SuppressLint("MissingPermission")
    private static void hookLegacyNetworkInfo() {
        for (String method : new String[] {
                "getNetworkInfo", "getActiveNetworkInfo", "getAllNetworkInfo"}) {
            String name = "Vpn." + method;
            try {
                Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                        ConnectivityManager.class, method, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                if (!RuntimeState.isEnabled() || param.hasThrowable()) return;
                                Object result = param.getResult();
                                if (!(result instanceof NetworkInfo)
                                        && !(result instanceof NetworkInfo[])) return;
                                try {
                                    // This executes in the caller app after its network-info query succeeds.
                                    ConnectivityManager manager = (ConnectivityManager) param.thisObject;
                                    Network network = param.args.length > 0 && param.args[0] instanceof Network
                                            ? (Network) param.args[0] : manager.getActiveNetwork();
                                    NetworkCapabilities capabilities = network == null ? null
                                            : manager.getNetworkCapabilities(network);
                                    if (result instanceof NetworkInfo) {
                                        NetworkInfo sanitized = sanitizeNetworkInfo(
                                                (NetworkInfo) result, capabilities);
                                        if (sanitized != result) param.setResult(sanitized);
                                    } else {
                                        NetworkInfo[] original = (NetworkInfo[]) result;
                                        NetworkInfo[] sanitized = original.clone();
                                        boolean changed = false;
                                        for (int i = 0; i < sanitized.length; i++) {
                                            sanitized[i] = sanitizeNetworkInfo(sanitized[i], capabilities);
                                            if (sanitized[i] != original[i]) changed = true;
                                        }
                                        if (changed) param.setResult(sanitized);
                                    }
                                } catch (Throwable t) {
                                    RuntimeState.reportHook("Vpn.legacyNetworkInfo", t);
                                }
                            }
                        });
                if (hooks.isEmpty()) RuntimeState.reportHook(name, new NoSuchMethodException(method));
                else RuntimeState.reportInstalled(name);
            } catch (Throwable t) {
                RuntimeState.reportHook(name, t);
            }
        }
    }

    private static NetworkInfo sanitizeNetworkInfo(NetworkInfo original,
            NetworkCapabilities capabilities) {
        if (original == null || original.getType() != ConnectivityManager.TYPE_VPN
                || capabilities == null) return original;
        int type;
        String typeName;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            type = ConnectivityManager.TYPE_WIFI;
            typeName = "WIFI";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            type = ConnectivityManager.TYPE_MOBILE;
            typeName = "MOBILE";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            type = ConnectivityManager.TYPE_ETHERNET;
            typeName = "ETHERNET";
        } else {
            return original;
        }
        try {
            NetworkInfo copy = (NetworkInfo) XposedHelpers.newInstance(NetworkInfo.class, original);
            XposedHelpers.callMethod(copy, "setType", type);
            XposedHelpers.setObjectField(copy, "mTypeName", typeName);
            XposedHelpers.callMethod(copy, "setExtraInfo", (Object) null);
            RuntimeState.count("vpn");
            return copy;
        } catch (Throwable t) {
            RuntimeState.reportHook("Vpn.sanitizeNetworkInfo", t);
            return original;
        }
    }

    private static void hookInterfaces() {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    NetworkInterface.class, "getNetworkInterfaces", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!RuntimeState.isEnabled() || param.hasThrowable()) return;
                            Object result = param.getResult();
                            if (!(result instanceof Enumeration)) return;
                            @SuppressWarnings("unchecked")
                            Enumeration<NetworkInterface> interfaces = (Enumeration<NetworkInterface>) result;
                            ArrayList<NetworkInterface> visible = new ArrayList<>();
                            while (interfaces.hasMoreElements()) {
                                NetworkInterface iface = interfaces.nextElement();
                                if (!isVpnInterface(iface)) visible.add(iface);
                            }
                            param.setResult(Collections.enumeration(visible));
                        }
                    });
            if (hooks.isEmpty()) RuntimeState.reportHook("Vpn.getNetworkInterfaces",
                    new NoSuchMethodException("getNetworkInterfaces"));
            else RuntimeState.reportInstalled("Vpn.getNetworkInterfaces");
        } catch (Throwable t) {
            RuntimeState.reportHook("Vpn.getNetworkInterfaces", t);
        }
        for (String method : new String[] {"getByName", "getByIndex", "getByInetAddress"}) {
            String name = "Vpn.NetworkInterface." + method;
            try {
                Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                        NetworkInterface.class, method, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                if (RuntimeState.isEnabled() && !param.hasThrowable()
                                        && param.getResult() instanceof NetworkInterface
                                        && isVpnInterface((NetworkInterface) param.getResult())) {
                                    param.setResult(null);
                                }
                            }
                        });
                if (hooks.isEmpty()) RuntimeState.reportHook(name, new NoSuchMethodException(method));
                else RuntimeState.reportInstalled(name);
            } catch (Throwable t) {
                RuntimeState.reportHook(name, t);
            }
        }
    }

    private static boolean isVpnInterface(NetworkInterface iface) {
        return iface != null && isVpnInterfaceName(iface.getName());
    }

    private static boolean isVpnInterfaceName(String name) {
        return name != null && name.matches("^(tun|tap|wg|ppp|ipsec)[0-9].*");
    }

    private static void hookResult(Class<?> owner, String method, String name) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(owner, method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!RuntimeState.isEnabled() || param.hasThrowable()) return;
                    Object original = param.getResult();
                    Object sanitized = original;
                    if (original instanceof NetworkCapabilities) {
                        sanitized = sanitizeCapabilities((NetworkCapabilities) original);
                    } else if (original instanceof LinkProperties) {
                        sanitized = sanitizeLinks((LinkProperties) original);
                    }
                    if (sanitized != original) param.setResult(sanitized);
                }
            });
            if (hooks.isEmpty()) RuntimeState.reportHook(name, new NoSuchMethodException(method));
            else RuntimeState.reportInstalled(name);
        } catch (Throwable t) {
            RuntimeState.reportHook(name, t);
        }
    }

    private static NetworkCapabilities sanitizeCapabilities(NetworkCapabilities original) {
        if (!original.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return original;
        try {
            NetworkCapabilities copy = (NetworkCapabilities) XposedHelpers.newInstance(
                    NetworkCapabilities.class, original);
            XposedHelpers.callMethod(copy, "removeTransportType", NetworkCapabilities.TRANSPORT_VPN);
            XposedHelpers.callMethod(copy, "setCapability", NetworkCapabilities.NET_CAPABILITY_NOT_VPN, true);
            XposedHelpers.callMethod(copy, "setTransportInfo", (Object) null);
            XposedHelpers.callMethod(copy, "setOwnerUid", -1);
            XposedHelpers.callMethod(copy, "setAdministratorUids", new int[0]);
            XposedHelpers.callMethod(copy, "setUnderlyingNetworks", (Object) null);
            XposedHelpers.callMethod(copy, "setUids", (Object) null);
            RuntimeState.count("vpn");
            return copy;
        } catch (Throwable t) {
            RuntimeState.reportHook("Vpn.sanitizeCapabilities", t);
            return original;
        }
    }

    private static LinkProperties sanitizeLinks(LinkProperties original) {
        String interfaceName = original.getInterfaceName();
        if (!isVpnInterfaceName(interfaceName)) {
            return original;
        }
        try {
            LinkProperties copy = (LinkProperties) XposedHelpers.newInstance(LinkProperties.class, original);
            XposedHelpers.callMethod(copy, "setInterfaceName", (Object) null);
            RuntimeState.count("vpn");
            return copy;
        } catch (Throwable t) {
            RuntimeState.reportHook("Vpn.sanitizeLinks", t);
            return original;
        }
    }
}
