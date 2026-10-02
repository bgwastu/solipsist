package net.wastu.solipsist;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A short overview and a searchable view of the local, redacted activity log. */
public final class MainActivity extends AppCompatActivity {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final List<RuntimeStore.Event> allEvents = new ArrayList<>();
    private ActivityAdapter adapter;
    private TabLayout tabs;
    private View overview;
    private View activityPage;
    private TextView emptyState;
    private TextInputEditText search;
    private ChipGroup filters;
    private MaterialSwitch protectionSwitch;
    private TextView protectionTitle;
    private boolean updatingSwitch;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.action_refresh) return false;
            refresh();
            return true;
        });

        overview = findViewById(R.id.overview);
        activityPage = findViewById(R.id.activity_page);
        tabs = findViewById(R.id.tabs);
        tabs.addTab(tabs.newTab().setText(R.string.tab_overview));
        tabs.addTab(tabs.newTab().setText(R.string.tab_activity));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showPage(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        protectionTitle = findViewById(R.id.protection_title);
        protectionSwitch = findViewById(R.id.protection_switch);
        protectionSwitch.setOnCheckedChangeListener((button, checked) -> saveProtection(checked));
        findViewById(R.id.protection_row).setOnClickListener(
                view -> protectionSwitch.setChecked(!protectionSwitch.isChecked()));
        findViewById(R.id.coverage_info).setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.coverage_dialog_title)
                .setMessage(R.string.coverage_dialog_body)
                .setPositiveButton(R.string.got_it, null)
                .show());

        setStat(R.id.stat_apps, R.string.stat_apps);
        setStat(R.id.stat_checks, R.string.stat_checks);
        setStat(R.id.stat_issues, R.string.stat_issues);

        adapter = new ActivityAdapter(this, this::showEvent);
        RecyclerView list = findViewById(R.id.activity_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        emptyState = findViewById(R.id.empty_state);
        filters = findViewById(R.id.filters);
        filters.check(R.id.filter_checks);
        filters.setOnCheckedStateChangeListener((group, checkedIds) -> applyFilter());
        search = findViewById(R.id.search_input);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        if (state != null && state.getInt("tab", 0) == 1) {
            TabLayout.Tab activity = tabs.getTabAt(1);
            if (activity != null) activity.select();
        }
        updateProtection();
    }

    @Override protected void onResume() {
        super.onResume();
        updateProtection();
        refresh();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        out.putInt("tab", tabs.getSelectedTabPosition());
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void showPage(int position) {
        overview.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        activityPage.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
    }

    private void updateProtection() {
        boolean enabled = RuntimeProvider.prefs(this).getBoolean(RuntimeProvider.ENABLED, true);
        updatingSwitch = true;
        protectionSwitch.setChecked(enabled);
        updatingSwitch = false;
        protectionTitle.setText(enabled ? R.string.protection_on : R.string.protection_off);
    }

    private void saveProtection(boolean enabled) {
        if (updatingSwitch) return;
        boolean saved = RuntimeProvider.prefs(this).edit()
                .putBoolean(RuntimeProvider.ENABLED, enabled).commit();
        if (!saved) {
            updateProtection();
            Toast.makeText(this, R.string.setting_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        protectionTitle.setText(enabled ? R.string.protection_on : R.string.protection_off);
        getContentResolver().notifyChange(RuntimeProvider.CONFIG_URI, null);
        sendBroadcast(new Intent(RuntimeBridgeReceiver.ACTION_CHANGED));
    }

    private void setStat(int cardId, int label) {
        View card = findViewById(cardId);
        ((TextView) card.findViewById(R.id.stat_label)).setText(label);
    }

    private void setStatValue(int cardId, long value) {
        View card = findViewById(cardId);
        ((TextView) card.findViewById(R.id.stat_value)).setText(
                String.format(Locale.getDefault(), "%,d", value));
    }

    private void refresh() {
        loader.execute(() -> {
            RuntimeStore.Snapshot snapshot;
            try (RuntimeStore store = new RuntimeStore(this)) {
                snapshot = store.snapshot();
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.log_refresh_error, Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setStatValue(R.id.stat_apps, snapshot.apps);
                setStatValue(R.id.stat_checks, snapshot.checks);
                setStatValue(R.id.stat_issues, snapshot.issues);
                allEvents.clear();
                allEvents.addAll(snapshot.events);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        if (adapter == null || filters == null || search == null) return;
        String term = search.getText() == null ? "" : search.getText().toString()
                .trim().toLowerCase(Locale.ROOT);
        int selected = filters.getCheckedChipId();
        ArrayList<RuntimeStore.Event> visible = new ArrayList<>();
        for (RuntimeStore.Event event : allEvents) {
            boolean typeMatches = selected == R.id.filter_checks ? event.check
                    : selected == R.id.filter_issues ? !event.check && "failed".equals(event.state)
                    : !event.check && "installed".equals(event.state);
            if (!typeMatches || !adapter.matches(event, term)) continue;
            visible.add(event);
        }
        adapter.submit(visible);
        int emptyMessage = selected == R.id.filter_issues ? R.string.no_issues
                : selected == R.id.filter_hooks ? R.string.no_hooks : R.string.no_checks;
        emptyState.setText(term.isEmpty() ? emptyMessage : R.string.no_matches);
        emptyState.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showEvent(RuntimeStore.Event event) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.event_detail_title)
                .setMessage(adapter.details(event))
                .setPositiveButton(R.string.got_it, null)
                .show();
    }
}
