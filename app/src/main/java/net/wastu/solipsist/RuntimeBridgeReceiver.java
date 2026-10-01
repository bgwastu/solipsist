package net.wastu.solipsist;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Explicit broadcast fallback when Android hides the module provider from a scoped app. */
public final class RuntimeBridgeReceiver extends BroadcastReceiver {
    static final String ACTION_CONFIG = "net.wastu.solipsist.CONFIG";
    static final String ACTION_EVENTS = "net.wastu.solipsist.EVENTS";
    static final String ACTION_CHANGED = "net.wastu.solipsist.CHANGED";
    static final String EXTRA_VALUES = "values";
    static final int RESULT_ENABLED = 1;
    static final int RESULT_DISABLED = 2;
    static final int RESULT_ACCEPTED = 3;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CONFIG.equals(action)) {
            setResultCode(RuntimeProvider.prefs(context).getBoolean(RuntimeProvider.ENABLED, true)
                    ? RESULT_ENABLED : RESULT_DISABLED);
        } else if (ACTION_EVENTS.equals(action)) {
            if (Build.VERSION.SDK_INT < 34) return;
            int uid = getSentFromUid();
            ContentValues[] values = intent.getParcelableArrayExtra(EXTRA_VALUES, ContentValues.class);
            if (values == null || values.length == 0 || values.length > 64) return;
            try {
                if (RuntimeProvider.acceptEvents(context, values, uid) == values.length) {
                    setResultCode(RESULT_ACCEPTED);
                }
            } catch (Throwable ignored) {
                // The sender retains the bounded batch and retries later.
            }
        }
    }
}
