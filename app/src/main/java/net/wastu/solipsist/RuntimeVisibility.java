package net.wastu.solipsist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;

/** Grants URI access so Android makes the status provider visible to scoped apps. */
public final class RuntimeVisibility extends BroadcastReceiver {
    private static final String MODULE_PACKAGE = "net.wastu.solipsist";

    static void grantAllAsync(Context context) {
        Context appContext = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            for (PackageInfo info : appContext.getPackageManager().getInstalledPackages(0)) {
                grant(appContext, info.packageName);
            }
        }, "Solipsist-provider-visibility");
        worker.setDaemon(true);
        worker.start();
    }

    private static void grant(Context context, String packageName) {
        if (packageName == null || MODULE_PACKAGE.equals(packageName)) return;
        try {
            context.grantUriPermission(packageName, RuntimeProvider.CONFIG_URI,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.grantUriPermission(packageName, RuntimeProvider.EVENTS_URI,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {
            // A package may disappear during enumeration or the ROM may reject a grant.
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction()) && intent.getData() != null) {
            grant(context, intent.getData().getSchemeSpecificPart());
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            grantAllAsync(context);
        }
    }
}
