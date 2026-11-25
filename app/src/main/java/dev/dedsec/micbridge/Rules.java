package dev.dedsec.micbridge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which apps get the Jio remote's microphone key.
 *
 * The default is "all apps": everything is routed except the Jio presets,
 * the platform, the launcher and the system UI. The settings screen can
 * switch to a pure allowlist ("selected apps") and can explicitly turn any
 * individual app on, including Zee5/Hotstar/Netflix, which are off by
 * default because their own voice button already works.
 *
 * These choices are stored in SharedPreferences and read on every mic press,
 * so changes apply immediately without restarting the service.
 */
final class Rules {

    static final String PREFS = "micbridge";

    static final String PREF_MODE = "mode";
    static final String PREF_ENABLED = "enabled";
    static final String PREF_DISABLED = "disabled";

    static final String MODE_ALL = "all";
    static final String MODE_SELECTED = "selected";

    /** Never a voice-search target, even when explicitly enabled. */
    private static final String[] CORE_EXACT = {
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.tv.settings",
            "com.android.tv.settings.local",
            "com.klevico.monet",
            "com.gathin.tvhome",
    };
    private static final String[] CORE_PREFIX = {
            "com.iwedia.",
    };

    /** Off by default, but the settings screen can turn them on. */
    private static final String[] PRESET_EXACT = {
            "in.startv.hotstar",
            "com.netflix.ninja",
            "com.zee5.aosp",
            "com.suntv.sunnxt",
            "com.hoichoi.mitv",
            "com.epicchannel.epicon",
            "com.ultrajhakaas.android",
            "com.etvwin.androidtv",
    };
    private static final String[] PRESET_PREFIX = {
            "com.jio.",
            "com.jiostb.",
            "com.jiotv.",
    };

    private Rules() {}

    // ---- persisted selection -------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String mode(Context c) {
        String m = prefs(c).getString(PREF_MODE, MODE_ALL);
        return MODE_SELECTED.equals(m) ? MODE_SELECTED : MODE_ALL;
    }

    static void setMode(Context c, String mode) {
        prefs(c).edit().putString(PREF_MODE, mode).apply();
    }

    static Set<String> enabled(Context c) {
        return new HashSet<>(prefs(c).getStringSet(PREF_ENABLED,
                Collections.<String>emptySet()));
    }

    static Set<String> disabled(Context c) {
        return new HashSet<>(prefs(c).getStringSet(PREF_DISABLED,
                Collections.<String>emptySet()));
    }

    /**
     * Turns one app on or off. "On" always wins over the built-in preset
     * list; "off" is remembered in both modes so switching back to "all"
     * does not silently re-enable it.
     */
    static void setEnabled(Context c, String pkg, boolean on) {
        Set<String> en = enabled(c);
        Set<String> dis = disabled(c);
        if (on) {
            en.add(pkg);
            dis.remove(pkg);
        } else {
            en.remove(pkg);
            dis.add(pkg);
        }
        prefs(c).edit()
                .putStringSet(PREF_ENABLED, en)
                .putStringSet(PREF_DISABLED, dis)
                .apply();
    }

    /**
     * Switching from "all apps" to "selected apps" starts from what is
     * currently active, so the list does not suddenly go empty.
     */
    static void seedSelectionFromAll(Context c) {
        Set<String> en = enabled(c);
        for (App app : apps(c)) {
            if (!app.core && isEnabled(c, app.pkg)) en.add(app.pkg);
        }
        prefs(c).edit().putStringSet(PREF_ENABLED, en).apply();
    }

    // ---- rules ---------------------------------------------------------

    static boolean isEnabled(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty() || isCore(c, pkg)) return false;
        if (enabled(c).contains(pkg)) return true;
        if (MODE_SELECTED.equals(mode(c))) return false;
        if (disabled(c).contains(pkg)) return false;
        return !isPreset(pkg);
    }

    static boolean isCore(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(c.getPackageName())) return true;
        if (MicA11yService.JIO_VA.equals(pkg)) return true;
        if (pkg.equals(homePackage(c))) return true;
        for (String s : CORE_EXACT) {
            if (s.equals(pkg)) return true;
        }
        for (String s : CORE_PREFIX) {
            if (pkg.startsWith(s)) return true;
        }
        return false;
    }

    static boolean isPreset(String pkg) {
        if (pkg == null) return false;
        for (String s : PRESET_EXACT) {
            if (s.equals(pkg)) return true;
        }
        for (String s : PRESET_PREFIX) {
            if (pkg.startsWith(s)) return true;
        }
        return false;
    }

    static String homePackage(Context c) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo r = c.getPackageManager()
                    .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (r != null && r.activityInfo != null) return r.activityInfo.packageName;
        } catch (Throwable ignored) {}
        return "";
    }

    // ---- settings screen app list ---------------------------------------

    static final class App {
        final String pkg;
        final String label;
        final Drawable icon;
        final boolean core;

        App(String pkg, String label, Drawable icon, boolean core) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.core = core;
        }
    }

    /**
     * Apps that can appear on screen and therefore can be a mic target.
     * Both TV and phone launcher categories are queried, because sideloaded
     * TV apps sometimes ship only one of them.
     */
    static List<App> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String self = c.getPackageName();
        String[] cats = {
                Intent.CATEGORY_LEANBACK_LAUNCHER,
                Intent.CATEGORY_LAUNCHER,
        };
        for (String cat : cats) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            List<ResolveInfo> ris;
            try {
                ris = pm.queryIntentActivities(i, 0);
            } catch (Throwable t) {
                continue;
            }
            if (ris == null) continue;
            for (ResolveInfo ri : ris) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(self) || MicA11yService.JIO_VA.equals(pkg)) continue;
                if (!seen.add(pkg)) continue;
                CharSequence lab = ri.loadLabel(pm);
                String label = lab == null ? pkg : lab.toString().trim();
                if (label.isEmpty()) label = pkg;
                Drawable icon = null;
                try { icon = ri.loadIcon(pm); } catch (Throwable ignored) {}
                out.add(new App(pkg, label, icon, isCore(c, pkg)));
            }
        }
        Collections.sort(out, new java.util.Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                return a.label.toLowerCase(Locale.US)
                        .compareTo(b.label.toLowerCase(Locale.US));
            }
        });
        return out;
    }
}
