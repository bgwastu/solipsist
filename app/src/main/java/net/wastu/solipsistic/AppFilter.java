package net.wastu.solipsistic;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Process;

import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Filter to exempt system apps and camera apps from Solipsistic's cloaking and media redaction,
 * ensuring Solipsistic applies ONLY to third-party user apps.
 */
public class AppFilter {
    private static final String TAG = "[Solipsistic-Filter]";

    // Cache UID -> exempt status
    private static final ConcurrentHashMap<Integer, Boolean> UID_EXEMPT_CACHE = new ConcurrentHashMap<>();

    // Cache Package name -> exempt status
    private static final ConcurrentHashMap<String, Boolean> PKG_EXEMPT_CACHE = new ConcurrentHashMap<>();

    private static volatile Object sPackageManager = null;

    /**
     * Checks whether a package is a camera app.
     */
    public static boolean isCameraApp(String packageName) {
        if (packageName == null) return false;
        String lower = packageName.toLowerCase();
        return lower.contains("camera")
                || lower.contains("aperture")
                || lower.contains("gcam")
                || lower.endsWith(".cam")
                || lower.contains(".cam.");
    }

    /**
     * Checks whether an ApplicationInfo represents a system app or updated system app.
     */
    public static boolean isSystemApp(ApplicationInfo appInfo) {
        if (appInfo == null) return false;
        if ((appInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
            return true;
        }
        if (appInfo.sourceDir != null) {
            if (appInfo.sourceDir.startsWith("/system/")
                    || appInfo.sourceDir.startsWith("/product/")
                    || appInfo.sourceDir.startsWith("/vendor/")
                    || appInfo.sourceDir.startsWith("/system_ext/")
                    || appInfo.sourceDir.startsWith("/odm/")
                    || appInfo.sourceDir.startsWith("/apex/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a package (and its ApplicationInfo, if available) should be exempt from Solipsistic.
     * Exempt if:
     * 1. It is a camera app.
     * 2. It is a system app (or framework/system component/OEM preloaded app).
     */
    public static boolean isExemptPackage(String packageName, ApplicationInfo appInfo) {
        if (packageName == null) return false;

        Boolean cached = PKG_EXEMPT_CACHE.get(packageName);
        if (cached != null) {
            return cached.booleanValue();
        }

        // 1. Core system and module packages
        if ("android".equals(packageName)
                || "system".equals(packageName)
                || "net.wastu.solipsistic".equals(packageName)
                || "com.android.systemui".equals(packageName)
                || "com.android.providers.settings".equals(packageName)
                || "com.android.providers.media".equals(packageName)
                || "com.android.providers.media.module".equals(packageName)
                || "com.google.android.providers.media.module".equals(packageName)
                || "com.android.photopicker".equals(packageName)
                || "com.google.android.photopicker".equals(packageName)) {
            PKG_EXEMPT_CACHE.put(packageName, true);
            return true;
        }

        // 2. Camera apps are explicitly exempt per user requirement
        if (isCameraApp(packageName)) {
            PKG_EXEMPT_CACHE.put(packageName, true);
            return true;
        }

        // 3. OEM / MIUI system packages and stock photo/gallery apps
        if (packageName.startsWith("com.miui.")
                || packageName.startsWith("com.xiaomi.")
                || packageName.startsWith("com.mediatek.")
                || packageName.startsWith("com.qualcomm.")
                || "com.google.android.apps.photos".equals(packageName)
                || packageName.toLowerCase().contains("gallery")) {
            PKG_EXEMPT_CACHE.put(packageName, true);
            return true;
        }

        // 3. Check ApplicationInfo flags if provided
        if (appInfo != null) {
            boolean isSys = isSystemApp(appInfo);
            PKG_EXEMPT_CACHE.put(packageName, isSys);
            return isSys;
        }

        // 4. If appInfo is null, try to query PackageManager
        long token = Binder.clearCallingIdentity();
        try {
            ApplicationInfo resolvedAi = getApplicationInfoForPackage(packageName, 0);
            if (resolvedAi != null) {
                boolean isSys = isSystemApp(resolvedAi);
                PKG_EXEMPT_CACHE.put(packageName, isSys);
                return isSys;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // Default to not exempt
        return false;
    }

    /**
     * Checks whether a UID should be exempt from Solipsistic.
     * Exempt if:
     * 1. UID is root (0) or system UIDs (appId < 10000).
     * 2. Any package associated with this UID is a system app or a camera app.
     */
    public static boolean isExemptUid(int uid) {
        if (uid <= 0) return true;
        if (uid == Process.myUid()) return true;

        int appId = uid % 100000;
        if (appId < 10000) {
            return true; // System UIDs (< 10000) are always exempt
        }

        Boolean cached = UID_EXEMPT_CACHE.get(uid);
        if (cached != null) {
            return cached.booleanValue();
        }

        long token = Binder.clearCallingIdentity();
        try {
            Object pm = getIPackageManager();
            if (pm != null) {
                String[] packages = null;
                int userId = uid / 100000;
                try {
                    packages = (String[]) XposedHelpers.callMethod(pm, "getPackagesForUid", uid);
                } catch (Throwable t) {
                    if (userId != 0) {
                        try {
                            packages = (String[]) XposedHelpers.callMethod(pm, "getPackagesForUid", appId);
                        } catch (Throwable ignored) {}
                    }
                }

                if (packages == null && userId != 0) {
                    try {
                        packages = (String[]) XposedHelpers.callMethod(pm, "getPackagesForUid", appId);
                    } catch (Throwable ignored) {}
                }

                if (packages != null && packages.length > 0) {
                    boolean exempt = false;
                    for (String pkg : packages) {
                        if (pkg == null) continue;

                        if (isCameraApp(pkg)) {
                            exempt = true;
                            break;
                        }

                        if (pkg.startsWith("com.miui.")
                                || pkg.startsWith("com.xiaomi.")
                                || pkg.startsWith("com.mediatek.")
                                || pkg.startsWith("com.qualcomm.")
                                || "com.google.android.apps.photos".equals(pkg)
                                || pkg.toLowerCase().contains("gallery")) {
                            exempt = true;
                            break;
                        }

                        ApplicationInfo ai = getApplicationInfo(pm, pkg, userId);
                        if (ai == null && userId != 0) {
                            ai = getApplicationInfo(pm, pkg, 0);
                        }
                        if (ai != null && isSystemApp(ai)) {
                            exempt = true;
                            break;
                        }
                    }
                    UID_EXEMPT_CACHE.put(uid, exempt);
                    return exempt;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error checking UID " + uid + ": " + t.getMessage());
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return false;
    }

    public static ApplicationInfo getApplicationInfoForPackage(String pkg, int userId) {
        try {
            Object pm = getIPackageManager();
            if (pm != null) {
                return getApplicationInfo(pm, pkg, userId);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object getIPackageManager() {
        if (sPackageManager != null) {
            return sPackageManager;
        }
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            sPackageManager = XposedHelpers.callStaticMethod(appGlobals, "getPackageManager");
            if (sPackageManager != null) {
                return sPackageManager;
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = XposedHelpers.callStaticMethod(serviceManager, "getService", "package");
            if (binder != null) {
                Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
                sPackageManager = XposedHelpers.callStaticMethod(stub, "asInterface", binder);
                return sPackageManager;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static ApplicationInfo getApplicationInfo(Object pm, String pkg, int userId) {
        if (pm == null || pkg == null) return null;
        try {
            // Android 13+ (API 33+) uses long flags
            return (ApplicationInfo) XposedHelpers.callMethod(pm, "getApplicationInfo", pkg, 0L, userId);
        } catch (Throwable t1) {
            try {
                // Older Android uses int flags
                return (ApplicationInfo) XposedHelpers.callMethod(pm, "getApplicationInfo", pkg, 0, userId);
            } catch (Throwable t2) {
                return null;
            }
        }
    }
}
