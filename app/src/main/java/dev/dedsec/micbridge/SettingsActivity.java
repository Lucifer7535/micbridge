package dev.dedsec.micbridge;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
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
    private static final int REQ_MIC = 2001;

    private static final String PREF_SETUP_SEEN = "setup_seen";
    private static final String PREF_OPENED_A11Y = "opened_a11y";

    private static final int BG = 0xFF0B0F14;
    private static final int CARD = 0xFF141A22;
    private static final int TEXT = 0xFFE6E8EB;
    private static final int MUTED = 0xFF9AA4B2;
    private static final int ACCENT = 0xFF7FB2FF;
    private static final int GREEN = 0xFF6EE7A8;
    private static final int AMBER = 0xFFF59E0B;

    private RadioGroup modes;
    private TextView summary;
    private TextView setupTitle;
    private TextView micState;
    private TextView a11yState;
    private Button micButton;
    private Button a11yButton;
    private ListView list;
    private Adapter adapter;
    private boolean loading = true;
    private boolean micDenied;

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

        root.addView(setupCard(), lp(0, 0, 0, 16));

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
        firstRunSetup();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSetup();
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

    // ---- first-run setup -----------------------------------------------

    private View setupCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(15), dp(20), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(1)), 0xFF1E2630);
        card.setBackground(bg);

        setupTitle = text("SETUP", 12, MUTED);
        setupTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        setupTitle.setLetterSpacing(0.14f);
        card.addView(setupTitle);

        micState = text("", 13, MUTED);
        micButton = smallButton("Grant");
        micButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestMicOrSettings();
            }
        });
        LinearLayout micRow = setupRow("Microphone", micState, micButton);
        card.addView(micRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        a11yState = text("", 13, MUTED);
        a11yButton = smallButton("Turn on");
        a11yButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        LinearLayout a11yRow = setupRow("Accessibility", a11yState, a11yButton);
        card.addView(a11yRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(text("Both are needed. The mic dialog appears automatically; "
                + "accessibility needs its switch turned on once.",
                12, MUTED), lp(0, 7, 0, 0));
        return card;
    }

    private LinearLayout setupRow(String name, TextView state, Button action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        row.addView(text(name, 17, TEXT), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(state, lp(12, 0, 12, 0));
        row.addView(action);
        return row;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setMinWidth(dp(104));
        b.setPadding(dp(16), 0, dp(16), 0);
        return b;
    }

    /** On the very first open after install, start the setup automatically. */
    private void firstRunSetup() {
        if (getSharedPreferences(Rules.PREFS, MODE_PRIVATE)
                .getBoolean(PREF_SETUP_SEEN, false)) {
            return;
        }
        getSharedPreferences(Rules.PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_SETUP_SEEN, true).apply();
        list.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!hasMic()) {
                    requestMic();
                } else if (!a11yEnabled()) {
                    openA11yOnce();
                }
            }
        }, 600);
    }

    private void requestMicOrSettings() {
        if (hasMic()) return;
        if (micDenied && Build.VERSION.SDK_INT >= 23
                && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            openAppSettings();
            return;
        }
        requestMic();
    }

    private void requestMic() {
        if (Build.VERSION.SDK_INT < 23) {
            updateSetup();
            return;
        }
        try {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } catch (Throwable t) {
            Toast.makeText(this, "Could not request the microphone",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        if (hasMic()) {
            micDenied = false;
            updateSetup();
            // Continue the guided setup without another tap.
            openA11yOnce();
        } else {
            micDenied = true;
            updateSetup();
            Toast.makeText(this, "Microphone access is required for the mic key",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasMic() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void updateSetup() {
        boolean mic = hasMic();
        boolean a11y = a11yEnabled();

        if (micState != null) {
            micState.setText(mic ? "Granted" : "Not granted");
            micState.setTextColor(mic ? GREEN : AMBER);
        }
        if (micButton != null) {
            micButton.setVisibility(mic ? View.GONE : View.VISIBLE);
        }
        if (a11yState != null) {
            a11yState.setText(a11y ? "On" : "Off");
            a11yState.setTextColor(a11y ? GREEN : AMBER);
        }
        if (a11yButton != null) {
            a11yButton.setText(a11y ? "Settings" : "Turn on");
        }
        if (setupTitle != null) {
            setupTitle.setText(mic && a11y ? "SETUP COMPLETE" : "SETUP");
            setupTitle.setTextColor(mic && a11y ? GREEN : MUTED);
        }
    }

    private boolean a11yEnabled() {
        try {
            AccessibilityManager am =
                    (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> infos =
                        am.getEnabledAccessibilityServiceList(
                                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (infos != null) {
                    for (AccessibilityServiceInfo i : infos) {
                        ResolveInfo ri = i == null ? null : i.getResolveInfo();
                        if (ri != null && ri.serviceInfo != null
                                && getPackageName().equals(ri.serviceInfo.packageName)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName() + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens accessibility settings once, on the first guided run. */
    private void openA11yOnce() {
        if (a11yEnabled()) return;
        if (getSharedPreferences(Rules.PREFS, MODE_PRIVATE)
                .getBoolean(PREF_OPENED_A11Y, false)) {
            return;
        }
        getSharedPreferences(Rules.PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_OPENED_A11Y, true).apply();
        openAccessibilitySettings();
    }

    private void openAccessibilitySettings() {
        Toast.makeText(this, "Turn on MicBridge, then press BACK",
                Toast.LENGTH_LONG).show();

        // AOSP Settings can open the service detail page directly.
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        details.putExtra("android.intent.extra.COMPONENT_NAME",
                new ComponentName(this, MicA11yService.class));
        if (startResolved(details)) return;

        if (startResolved(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) return;

        // Some Jio firmware hides the accessibility page under a vendor
        // activity. Find it in whichever app handles ACTION_SETTINGS.
        Intent discovered = findAccessibilityActivity();
        if (discovered != null && startResolved(discovered)) return;

        if (startResolved(new Intent(Settings.ACTION_SETTINGS))) return;

        Toast.makeText(this, "No settings screen found", Toast.LENGTH_SHORT).show();
    }

    private boolean startResolved(Intent i) {
        try {
            if (i.resolveActivity(getPackageManager()) == null) return false;
            startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Intent findAccessibilityActivity() {
        try {
            Intent main = new Intent(Settings.ACTION_SETTINGS);
            ResolveInfo ri = getPackageManager().resolveActivity(main, 0);
            if (ri == null || ri.activityInfo == null) return null;
            String pkg = ri.activityInfo.packageName;
            PackageInfo pi = getPackageManager().getPackageInfo(
                    pkg, PackageManager.GET_ACTIVITIES);
            if (pi == null || pi.activities == null) return null;
            for (ActivityInfo ai : pi.activities) {
                if (ai == null || ai.name == null) continue;
                if (ai.name.toLowerCase(java.util.Locale.US).contains("accessib")) {
                    Intent i = new Intent();
                    i.setComponent(new ComponentName(pkg, ai.name));
                    return i;
                }
            }
        } catch (Throwable t) {
            L.f(this, "find accessibility activity: " + t);
        }
        return null;
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Toast.makeText(this, "Open Settings > Apps > MicBridge",
                    Toast.LENGTH_LONG).show();
        }
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
