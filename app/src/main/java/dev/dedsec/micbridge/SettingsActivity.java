package dev.dedsec.micbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Remote-friendly picker for the apps that should receive the mic key.
 * Everything is built in code so the app needs no AppCompat or Leanback
 * library (the APK is built with build-tools only).
 */
public class SettingsActivity extends Activity {

    private static final int ID_MODE_ALL = 1001;
    private static final int ID_MODE_SELECTED = 1002;

    private static final int BG = 0xFF0B0F14;
    private static final int CARD = 0xFF141A22;
    private static final int TEXT = 0xFFE6E8EB;
    private static final int MUTED = 0xFF9AA4B2;
    private static final int ACCENT = 0xFF7FB2FF;

    private RadioGroup modes;
    private TextView summary;
    private TextView status;
    private ListView list;
    private Adapter adapter;
    private boolean loading = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(48), dp(24), dp(48), dp(16));

        TextView title = text("MicBridge", 30, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        root.addView(text("Choose which apps receive the remote's microphone key.",
                16, MUTED), lp(0, 0, 0, 10));

        status = text("", 14, MUTED);
        root.addView(status);
        root.addView(statusButton(), lp(0, 6, 0, 18));

        root.addView(text("Use the mic in", 15, MUTED), lp(0, 0, 0, 4));

        modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton all = radio("All apps", ID_MODE_ALL);
        RadioButton sel = radio("Only selected apps", ID_MODE_SELECTED);
        modes.addView(all);
        modes.addView(sel, lp(24, 0, 0, 0));
        root.addView(modes, lp(0, 0, 0, 4));

        summary = text("", 14, MUTED);
        root.addView(summary, lp(0, 0, 0, 10));

        adapter = new Adapter();
        list = new ListView(this);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setDivider(new ColorDrawable(0xFF1E2630));
        list.setDividerHeight(dp(1));
        list.setItemsCanFocus(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        StateListDrawable selector = new StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(CARD));
        selector.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(CARD));
        selector.addState(new int[]{android.R.attr.state_selected}, new ColorDrawable(CARD));
        selector.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        list.setSelector(selector);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                toggle(position);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(text("Press OK on an app to toggle it. Changes apply immediately.",
                13, MUTED), lp(0, 8, 0, 0));

        setContentView(root);

        loading = true;
        if (Rules.MODE_SELECTED.equals(Rules.mode(this))) {
            sel.setChecked(true);
        } else {
            all.setChecked(true);
        }
        loading = false;
        modes.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (loading) return;
                String mode = checkedId == ID_MODE_SELECTED
                        ? Rules.MODE_SELECTED : Rules.MODE_ALL;
                if (Rules.MODE_SELECTED.equals(mode)
                        && !Rules.MODE_SELECTED.equals(Rules.mode(SettingsActivity.this))) {
                    // Start the allowlist from what is active right now.
                    Rules.seedSelectionFromAll(SettingsActivity.this);
                }
                Rules.setMode(SettingsActivity.this, mode);
                refresh();
            }
        });

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (adapter != null) reload();
    }

    private void reload() {
        adapter.setApps(Rules.apps(this));
        refresh();
    }

    private void refresh() {
        adapter.notifyDataSetChanged();
        List<Rules.App> apps = adapter.getApps();
        int on = 0;
        for (Rules.App app : apps) {
            if (!app.core && Rules.isEnabled(this, app.pkg)) on++;
        }
        summary.setText(on + " of " + apps.size() + " apps enabled");
    }

    private void toggle(int position) {
        Rules.App app = adapter.getItem(position);
        if (app == null) return;
        if (app.core) {
            Toast.makeText(this, app.label + " is a system app and is never used",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = !Rules.isEnabled(this, app.pkg);
        Rules.setEnabled(this, app.pkg, on);
        adapter.notifyDataSetChanged();
        refresh();
        Toast.makeText(this, (on ? "Enabled " : "Disabled ") + app.label,
                Toast.LENGTH_SHORT).show();
    }

    private void updateStatus() {
        boolean on = serviceRunning();
        status.setText(on ? "Accessibility service: running"
                : "Accessibility service: off \u2014 MicBridge cannot receive the mic key");
        status.setTextColor(on ? 0xFF6EE7A8 : 0xFFF59E0B);
    }

    private boolean serviceRunning() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private Button statusButton() {
        Button b = new Button(this);
        b.setText("Open accessibility settings");
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // This firmware has no standalone accessibility activity; its
                // Jio Settings main page has Accessibility under "General".
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                if (i.resolveActivity(getPackageManager()) == null) {
                    i = new Intent(Settings.ACTION_SETTINGS);
                }
                try {
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this,
                            "No settings screen found", Toast.LENGTH_SHORT).show();
                }
            }
        });
        return b;
    }

    private RadioButton radio(String label, int id) {
        RadioButton b = new RadioButton(this);
        b.setId(id);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(TEXT);
        b.setButtonTintList(ColorStateList.valueOf(ACCENT));
        b.setPadding(0, dp(4), dp(8), dp(4));
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams lp(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                v, getResources().getDisplayMetrics()));
    }

    // ---- list ----------------------------------------------------------

    private final class Adapter extends BaseAdapter {

        private List<Rules.App> apps = new java.util.ArrayList<>();

        void setApps(List<Rules.App> a) {
            apps = a;
        }

        List<Rules.App> getApps() {
            return apps;
        }

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Rules.App getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            Holder hold;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof Holder) {
                row = (LinearLayout) convertView;
                hold = (Holder) convertView.getTag();
            } else {
                row = new LinearLayout(SettingsActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));

                ImageView icon = new ImageView(SettingsActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

                LinearLayout col = new LinearLayout(SettingsActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(18), 0, dp(16), 0);
                TextView label = text("", 18, TEXT);
                TextView pkg = text("", 12, MUTED);
                col.addView(label);
                col.addView(pkg);
                row.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                CheckBox check = new CheckBox(SettingsActivity.this);
                check.setFocusable(false);
                check.setClickable(false);
                check.setButtonTintList(ColorStateList.valueOf(ACCENT));
                row.addView(check);

                hold = new Holder();
                hold.icon = icon;
                hold.label = label;
                hold.pkg = pkg;
                hold.check = check;
                row.setTag(hold);
            }

            Rules.App app = apps.get(position);
            hold.label.setText(app.label);
            hold.pkg.setText(app.core ? app.pkg + "  \u00b7  system"
                    : app.pkg);
            hold.label.setTextColor(app.core ? MUTED : TEXT);
            hold.check.setChecked(app.core ? false : Rules.isEnabled(SettingsActivity.this, app.pkg));
            hold.check.setEnabled(!app.core);
            if (app.icon != null) {
                hold.icon.setImageDrawable(app.icon);
            } else {
                hold.icon.setImageDrawable(null);
            }
            return row;
        }
    }

    private static final class Holder {
        ImageView icon;
        TextView label;
        TextView pkg;
        CheckBox check;
    }
}
