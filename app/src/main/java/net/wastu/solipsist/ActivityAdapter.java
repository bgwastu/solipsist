package net.wastu.solipsist;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

final class ActivityAdapter extends RecyclerView.Adapter<ActivityAdapter.Holder> {
    private final Context context;
    private final Consumer<RuntimeStore.Event> onClick;
    private final Map<String, String> labels = new HashMap<>();
    private final ArrayList<RuntimeStore.Event> events = new ArrayList<>();

    ActivityAdapter(Context context, Consumer<RuntimeStore.Event> onClick) {
        this.context = context;
        this.onClick = onClick;
    }

    void submit(List<RuntimeStore.Event> incoming) {
        events.clear();
        events.addAll(incoming);
        notifyDataSetChanged();
    }

    boolean matches(RuntimeStore.Event event, String term) {
        if (term.isEmpty()) return true;
        return event.packageName.toLowerCase(Locale.ROOT).contains(term)
                || label(event.packageName).toLowerCase(Locale.ROOT).contains(term)
                || event.name.toLowerCase(Locale.ROOT).contains(term)
                || event.process.toLowerCase(Locale.ROOT).contains(term)
                || categoryLabel(event.name).toLowerCase(Locale.ROOT).contains(term);
    }

    String details(RuntimeStore.Event event) {
        String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(event.time));
        if (event.check) {
            return categoryLabel(event.name) + " · " + event.count + " checks in 5 minutes\n"
                    + label(event.packageName) + " (" + event.packageName + ")\n" + date;
        }
        StringBuilder detail = new StringBuilder();
        String app = label(event.packageName);
        detail.append("failed".equals(event.state) ? "Issue" : "Hook ready")
                .append(" · ").append(app);
        if (!app.equals(event.packageName)) detail.append("\n").append(event.packageName);
        detail.append("\nHook: ").append(event.name);
        if (!event.process.isEmpty() && !event.process.equals(event.packageName)) {
            detail.append("\nProcess: ").append(event.process);
        }
        if (!event.error.isEmpty()) detail.append("\nError: ").append(event.error);
        return detail.append("\n").append(date).toString();
    }

    private String label(String packageName) {
        String known = labels.get(packageName);
        if (known != null) return known;
        String value = packageName;
        try {
            PackageManager manager = context.getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
            value = manager.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            // Work-profile apps may not be visible in the owner's package manager.
        }
        labels.put(packageName, value);
        return value;
    }

    private static String categoryLabel(String name) {
        switch (name) {
            case "vpn": return "VPN";
            case "media_query": return "Media access";
            case "media_redaction": return "Media protection";
            case "settings": return "Settings";
            case "accessibility": return "Accessibility";
            case "system_property": return "Device properties";
            case "clipboard": return "Clipboard";
            case "accounts": return "Accounts";
            case "webview": return "Web content";
            case "location": return "Location";
            default: return name;
        }
    }

    private static String hookLabel(String name) {
        if (name.startsWith("Vpn.")) return "VPN privacy";
        if (name.startsWith("Media.")) return "Media protection";
        if (name.startsWith("Camera.")) return "Camera protection";
        if (name.startsWith("Observe.")) return "Activity observation";
        if (name.startsWith("Runtime.")) return "Runtime connection";
        if (name.startsWith("Shield.") || name.startsWith("PrivacyShield.")) {
            return "Device protection";
        }
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) + " hook" : name;
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_activity, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        RuntimeStore.Event event = events.get(position);
        boolean issue = "failed".equals(event.state);
        holder.title.setText(event.check ? categoryLabel(event.name)
                : hookLabel(event.name));
        CharSequence relative = DateUtils.getRelativeTimeSpanString(event.time,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        holder.subtitle.setText(label(event.packageName) + " · " + relative);
        holder.trailing.setVisibility(event.check ? View.VISIBLE : View.GONE);
        if (event.check) holder.trailing.setText(
                String.format(Locale.getDefault(), "%,d", event.count));
        holder.mark.setImageResource(event.check ? iconForCategory(event.name)
                : issue ? R.drawable.mi_issue : R.drawable.mi_ready);
        int background = MaterialColors.getColor(holder.mark, issue
                ? com.google.android.material.R.attr.colorErrorContainer
                : com.google.android.material.R.attr.colorPrimaryContainer);
        int foreground = MaterialColors.getColor(holder.mark, issue
                ? com.google.android.material.R.attr.colorOnErrorContainer
                : com.google.android.material.R.attr.colorOnPrimaryContainer);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(background);
        holder.mark.setBackground(circle);
        holder.mark.setImageTintList(ColorStateList.valueOf(foreground));
        holder.itemView.setOnClickListener(view -> onClick.accept(event));
        holder.itemView.setContentDescription(event.check
                ? context.getString(R.string.event_accessibility, holder.title.getText(),
                        holder.subtitle.getText(), holder.trailing.getText())
                : context.getString(R.string.event_accessibility, holder.title.getText(),
                        holder.subtitle.getText(), issue ? context.getString(R.string.event_failed)
                                : context.getString(R.string.event_installed)));
    }

    @Override public int getItemCount() { return events.size(); }

    private static int iconForCategory(String category) {
        switch (category) {
            case "media_query":
            case "media_redaction": return R.drawable.mi_media;
            case "vpn": return R.drawable.mi_vpn;
            case "settings":
            case "system_property": return R.drawable.mi_device;
            case "accessibility": return R.drawable.mi_accessibility;
            case "clipboard": return R.drawable.mi_clipboard;
            case "accounts": return R.drawable.mi_accounts;
            case "webview": return R.drawable.mi_web;
            case "location": return R.drawable.mi_location;
            default: return R.drawable.mi_device;
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView mark;
        final TextView title;
        final TextView subtitle;
        final TextView trailing;

        Holder(View view) {
            super(view);
            mark = view.findViewById(R.id.event_mark);
            title = view.findViewById(R.id.event_title);
            subtitle = view.findViewById(R.id.event_subtitle);
            trailing = view.findViewById(R.id.event_trailing);
        }
    }
}
