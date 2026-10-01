package net.wastu.solipsist;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Single global control and local, redacted 24-hour status. */
public final class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Solipsist");
        title.setTextSize(24);
        root.addView(title);

        Switch enabled = new Switch(this);
        enabled.setText("Enable privacy protection and query counts");
        enabled.setChecked(RuntimeProvider.prefs(this).getBoolean(RuntimeProvider.ENABLED, true));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            boolean saved = RuntimeProvider.prefs(this).edit().putBoolean(RuntimeProvider.ENABLED, checked).commit();
            if (saved) {
                getContentResolver().notifyChange(RuntimeProvider.CONFIG_URI, null);
                sendBroadcast(new Intent(RuntimeBridgeReceiver.ACTION_CHANGED));
            }
            refresh();
        });
        root.addView(enabled);

        TextView note = new TextView(this);
        note.setText("Changes reach running hooked processes automatically. App API counts require the app to be in LSPosed scope. No queried values are saved. Data expires after 24 hours.");
        note.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(note);

        Button refresh = new Button(this);
        refresh.setText("Refresh status");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setTextSize(14);
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refresh();
    }

    private void refresh() {
        if (status == null) return;
        boolean enabled = RuntimeProvider.prefs(this).getBoolean(RuntimeProvider.ENABLED, true);
        RuntimeStore store = new RuntimeStore(this);
        try {
            status.setText("Configured: " + (enabled ? "enabled" : "disabled")
                    + "\nHook reports confirm installation separately.\n\nLast 24 hours\n"
                    + store.summary());
        } finally {
            store.close();
        }
    }
}
