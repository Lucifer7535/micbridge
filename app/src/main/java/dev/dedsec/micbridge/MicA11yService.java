package dev.dedsec.micbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;

/**
 * Routes the Jio remote's microphone key to sideloaded apps.
 *
 * The firmware always launches com.android.app.jio.voiceassist for the mic
 * key and that app is the only client the speech service accepts, so this
 * service lets it do the recognition, reads the recognised query from
 * voiceStatesView, closes the overlay and types the text into the app that
 * was in the foreground.
 *
 * Routing is global. Only Jio/preset apps, the current launcher, Settings and
 * SystemUI keep their stock behaviour. A package can always be forced back
 * into the generic flow with:
 *
 *   adb shell settings put global micbridge_allow "com.example.app,com.other.*"
 */
public class MicA11yService extends AccessibilityService {

    static final String JIO_VA = "com.android.app.jio.voiceassist";
    static final String ID_VOICE_STATE = JIO_VA + ":id/voiceStatesView";

    /**
     * View IDs that carry the recognised query on different Jio Voice Assist
     * builds. Result titles are deliberately absent: an unknown id is better
     * covered by the accessibility-event path than by grabbing a title.
     */
    private static final String[] ID_QUERY = {
            ID_VOICE_STATE,
            JIO_VA + ":id/voice_query",
            JIO_VA + ":id/query",
            JIO_VA + ":id/search_query",
            JIO_VA + ":id/tv_speech_result",
            JIO_VA + ":id/text_result",
            JIO_VA + ":id/tv_voice_state",
    };

    /** Comma/space separated allowlist, supports a trailing "*" wildcard. */
    static final String SETTING_ALLOW = "micbridge_allow";

    /** Comma/space separated force-block list, supports a trailing "*" wildcard. */
    static final String SETTING_BLOCK = "micbridge_block";

    /**
     * Semicolon/newline separated search URL overrides:
     *   com.example.app=https://example.com/search?q=%s
     * "%s" is replaced with the URL-encoded query.
     */
    static final String SETTING_SEARCH = "micbridge_search";

    /** Packages that always keep the stock Jio mic behaviour. */
    private static final String[] BLOCKED_EXACT = {
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.tv.settings",
            "com.android.tv.settings.local",
            "com.klevico.monet",
            "com.gathin.tvhome",
            "in.startv.hotstar",
            "com.netflix.ninja",
            "com.zee5.aosp",
            "com.suntv.sunnxt",
            "com.hoichoi.mitv",
            "com.epicchannel.epicon",
            "com.ultrajhakaas.android",
            "com.etvwin.androidtv",
    };
    private static final String[] BLOCKED_PREFIX = {
            "com.iwedia.",
            "com.jio.",
            "com.jiostb.",
            "com.jiotv.",
    };

    private final Handler h = new Handler(Looper.getMainLooper());

    /** last non-Jio, non-self application package */
    private String lastApp = "";

    /** package that was in front when the mic key went down */
    private String keyApp = "";
    private long keyAppAt;

    private boolean busy;
    private long lastTrigger;
    private long lastBlockedLog;
    private String lastQuery;
    private int stableCount;
    private boolean overlaySeen;
    private int overlayAbsentCount;

    /** set once we clicked a search entry point for the current capture */
    private boolean searchClicked;

    /** set once we tried the search-URL fallback for the current capture */
    private boolean searchTried;

    private String homePkg;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            // TYPE_WINDOWS_CHANGED catches the overlay window appearing on
            // firmware that drops the state-change event.
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            setServiceInfo(info);
        }
        L.f(this, "service connected; flags=" + (info == null ? -1 : info.flags)
                + " home=" + homePackage());
        L.f(this, "blocklist: Jio/preset + launcher + Settings/SystemUI; allow="
                + allowSetting() + " block=" + blockSetting()
                + " search=" + searchSetting());

        // Debug hook: adb shell am broadcast -a dev.dedsec.micbridge.TEST
        //   --es query "some text"   (deliver directly)
        //   --es target <pkg>        (override the foreground app)
        //   (no extra)               (capture from the open Jio overlay)
        try {
            registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String t = i.getStringExtra("target");
                    if (t == null || t.isEmpty()) t = lastApp;
                    String q = i.getStringExtra("query");
                    L.f(MicA11yService.this, "TEST target=" + t
                            + " query=" + q + " blocked=" + isBlocked(t));
                    if (q != null && !q.isEmpty()) deliver(t, q);
                    else startCapture(t);
                }
            }, new IntentFilter("dev.dedsec.micbridge.TEST"));
        } catch (Throwable t) {
            L.f(this, "debug receiver failed: " + t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();

        CharSequence ep = event.getPackageName();
        String eventPkg = ep == null ? null : ep.toString();

        if (eventPkg != null && !JIO_VA.equals(eventPkg)
                && !getPackageName().equals(eventPkg)
                && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_FOCUSED)) {
            lastApp = eventPkg;
        }

        boolean jioEvent = JIO_VA.equals(eventPkg);

        // While capturing, feed Jio text events into the query state machine.
        if (busy) {
            if (jioEvent) {
                overlaySeen = true;
                overlayAbsentCount = 0;
                checkJioEventForQuery(event);
            }
            return;
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && !jioEvent) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastTrigger <= 1000 || !jioOverlayPresent()) return;

        // Prefer the app that was in front when the mic key went down: some
        // launchers grab focus again while the overlay is opening, which
        // would otherwise look like a mic press on the launcher.
        String target = resolveTarget(now);
        if (target.isEmpty()) return;

        boolean blocked = isBlocked(target);
        if (blocked) {
            if (now - lastBlockedLog > 3000) {
                lastBlockedLog = now;
                L.f(this, "voice overlay over " + target
                        + " -> blocked, keeping stock behaviour");
            }
            return;
        }
        L.f(this, "voice overlay over " + target + " -> capturing");
        startCapture(target);
    }

    private String resolveTarget(long now) {
        String target = lastApp;
        if (!keyApp.isEmpty() && now - keyAppAt <= 15000) {
            target = keyApp;
            keyApp = "";            // one press, one target
        }
        if (target.isEmpty() || JIO_VA.equals(target) || getPackageName().equals(target)) {
            String active = activeAppPackage();
            if (active != null && !active.isEmpty()) target = active;
        }
        return target;
    }

    private void checkJioEventForQuery(AccessibilityEvent event) {
        try {
            List<CharSequence> texts = event.getText();
            if (texts != null) {
                for (CharSequence cs : texts) {
                    if (cs == null) continue;
                    String s = cs.toString().trim();
                    if (plausibleQuery(s)) {
                        updateQueryFromEvent(s);
                        return;
                    }
                }
            }
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) {
                String s = extractNodeText(src);
                if (s != null && plausibleQuery(s)) updateQueryFromEvent(s);
            }
        } catch (Throwable ignored) {}
    }

    private void updateQueryFromEvent(String q) {
        if (q == null) return;
        if (q.equals(lastQuery)) {
            stableCount++;
        } else {
            lastQuery = q;
            stableCount = 0;
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        if ((kc == KeyEvent.KEYCODE_VOICE_ASSIST || kc == KeyEvent.KEYCODE_SEARCH)
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            // Ask for the focused app right now instead of trusting the last
            // window event: some boxes/apps do not emit state changes.
            String front = activeAppPackage();
            keyApp = (front != null && !JIO_VA.equals(front)
                    && !getPackageName().equals(front)) ? front : lastApp;
            keyAppAt = System.currentTimeMillis();
            L.f(this, "mic key kc=" + kc + " scan=" + event.getScanCode()
                    + " app=" + keyApp);

            // Do not depend on the system delivering a window-state change.
            watchForOverlay(keyApp, 0);
        }
        return false;
    }

    /**
     * Polls briefly after the mic key in case the window-state event for the
     * Jio overlay is dropped (seen on some launchers/firmware).
     */
    private void watchForOverlay(final String keyTarget, final int attempt) {
        if (attempt > 8) return;
        h.postDelayed(() -> {
            if (busy) return;
            if (jioOverlayPresent()) {
                long now = System.currentTimeMillis();
                if (now - lastTrigger <= 1000) return;

                String target = keyTarget;
                if (target == null || target.isEmpty()
                        || JIO_VA.equals(target) || getPackageName().equals(target)) {
                    target = lastApp;
                }
                if (target == null || target.isEmpty()) target = activeAppPackage();
                if (target == null || target.isEmpty()) return;

                if (isBlocked(target)) {
                    if (now - lastBlockedLog > 3000) {
                        lastBlockedLog = now;
                        L.f(this, "voice overlay over " + target
                                + " (watched) -> blocked");
                    }
                } else {
                    L.f(this, "voice overlay over " + target + " (watched) -> capturing");
                    startCapture(target);
                }
            } else {
                watchForOverlay(keyTarget, attempt + 1);
            }
        }, attempt == 0 ? 150 : 250);
    }

    @Override
    public void onInterrupt() {}

    // ---- blocklist -----------------------------------------------------

    private String allowSetting() {
        try {
            String s = Settings.Global.getString(getContentResolver(), SETTING_ALLOW);
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private String blockSetting() {
        try {
            String s = Settings.Global.getString(getContentResolver(), SETTING_BLOCK);
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private String searchSetting() {
        try {
            String s = Settings.Global.getString(getContentResolver(), SETTING_SEARCH);
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean isAllowed(String pkg) {
        return matchesList(pkg, allowSetting());
    }

    /** Comma/space list of packages, trailing "*" = prefix match. */
    private boolean matchesList(String pkg, String raw) {
        if (raw == null || raw.isEmpty()) return false;
        for (String part : raw.split("[,\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.endsWith("*")) {
                if (pkg.startsWith(p.substring(0, p.length() - 1))) return true;
            } else if (pkg.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the package keeps its stock mic behaviour: Jio/preset apps,
     * the launcher, Settings, SystemUI, the voice app and ourselves.
     * micbridge_allow always wins; micbridge_block forces a package back to
     * the stock behaviour.
     */
    private boolean isBlocked(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (JIO_VA.equals(pkg) || getPackageName().equals(pkg)) return true;
        if (isAllowed(pkg)) return false;
        if (matchesList(pkg, blockSetting())) return true;
        if (pkg.equals(homePackage())) return true;

        for (String s : BLOCKED_EXACT) {
            if (s.equals(pkg)) return true;
        }
        for (String s : BLOCKED_PREFIX) {
            if (pkg.startsWith(s)) return true;
        }
        return false;
    }

    private String homePackage() {
        if (homePkg != null) return homePkg;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo r = getPackageManager()
                    .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (r != null && r.activityInfo != null) {
                homePkg = r.activityInfo.packageName;
            }
        } catch (Throwable ignored) {}
        if (homePkg == null) homePkg = "";
        return homePkg;
    }

    /** True when the Jio voice overlay window is on screen. */
    private boolean jioOverlayPresent() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return false;
            for (AccessibilityWindowInfo w : ws) {
                if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (JIO_VA.contentEquals(String.valueOf(r.getPackageName()))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ---- query capture -------------------------------------------------

    private void startCapture(String target) {
        busy = true;
        lastTrigger = System.currentTimeMillis();
        lastQuery = null;
        stableCount = 0;
        searchClicked = false;
        searchTried = false;
        overlaySeen = false;
        overlayAbsentCount = 0;
        poll(target, 0);
    }

    private void poll(final String target, final int tick) {
        if (tick > 90) {          // ~27 s
            L.f(this, "capture timeout");
            busy = false;
            return;
        }

        String top = activeAppPackage();
        boolean overlayUp = JIO_VA.equals(top);
        if (overlayUp) {
            overlaySeen = true;
            overlayAbsentCount = 0;
        } else if (overlaySeen) {
            overlayAbsentCount++;
        }

        if (overlayUp) {
            String q = readQuery();
            if (q != null) {
                updateQueryFromEvent(q);
            }
            if (lastQuery != null) {
                // The result screen carries a banner/title; prefer that, and
                // fall back to a text that stayed unchanged for ~600 ms.
                boolean finalUi = hasId(JIO_VA + ":id/banner_layout")
                        || hasId(JIO_VA + ":id/title");
                if ((finalUi && stableCount >= 1) || stableCount >= 2) {
                    L.f(this, "captured query: \"" + lastQuery + "\" (finalUi=" + finalUi + ")");
                    deliver(target, lastQuery);
                    return;
                }
            }
        } else if (overlaySeen) {
            // Short utterances close the overlay right after the result, so
            // deliver whatever the event path already captured.
            if (overlayAbsentCount >= 2) {
                if (lastQuery != null && plausibleQuery(lastQuery)) {
                    L.f(this, "overlay closed with query: \"" + lastQuery + "\" -> delivering");
                    deliver(target, lastQuery);
                } else if (tick > 6) {   // ~900 ms, allow a late event
                    L.f(this, "overlay closed before a query appeared (top=" + top + ")");
                    busy = false;
                } else {
                    h.postDelayed(() -> poll(target, tick + 1), 300);
                }
                return;
            }
        } else if (tick > 26) {      // ~4 s, overlay never showed up
            L.f(this, "overlay never appeared after mic press");
            busy = false;
            return;
        }
        h.postDelayed(() -> poll(target, tick + 1), 300);
    }

    /** Reads the recognised query out of the Jio overlay, or null. */
    private String readQuery() {
        try {
            AccessibilityNodeInfo root = jioRoot();
            if (root == null) return null;
            for (String id : ID_QUERY) {
                List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
                if (l == null || l.isEmpty()) continue;
                for (AccessibilityNodeInfo n : l) {
                    String s = extractNodeText(n);
                    if (s != null && plausibleQuery(s)) return s;
                }
            }
        } catch (Throwable t) {
            L.e("readQuery: " + t);
        }
        return null;
    }

    /** First non-empty text or content description of a node (depth-first). */
    private String extractNodeText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence t = node.getText();
        if (t != null) {
            String s = t.toString().trim();
            if (!s.isEmpty()) return s;
        }
        CharSequence d = node.getContentDescription();
        if (d != null) {
            String s = d.toString().trim();
            if (!s.isEmpty()) return s;
        }
        try {
            for (int i = 0; i < node.getChildCount(); i++) {
                String c = extractNodeText(node.getChild(i));
                if (c != null) return c;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private AccessibilityNodeInfo jioRoot() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null || root.getPackageName() == null) continue;
                    if (JIO_VA.contentEquals(root.getPackageName())) return root;
                }
            }
        } catch (Throwable ignored) {}
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null && active.getPackageName() != null
                    && JIO_VA.contentEquals(active.getPackageName())) {
                return active;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean hasId(String id) {
        try {
            AccessibilityNodeInfo root = jioRoot();
            if (root == null) return false;
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
            return l != null && !l.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean plausibleQuery(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 2) return false;
        String l = t.toLowerCase(Locale.US);

        // Placeholders and dots that appear while the recogniser is starting.
        if (l.equals("…") || l.equals("...") || l.equals("..")
                || l.equals(".") || l.equals("-")) {
            return false;
        }
        if (l.contains("listening")) return false;

        // Jio's overlay prints spoken instructions in English and Hindi; they
        // must never be mistaken for the query.
        boolean promptVerb = l.contains("press") || l.contains("hold")
                || l.contains("tap") || l.contains("speak") || l.contains("say")
                || l.contains("बोलने") || l.contains("बोलें")
                || l.contains("दबा") || l.contains("प्रेस") || l.contains("कहें");
        boolean promptNoun = l.contains("mic") || l.contains("voice")
                || l.contains("search") || l.contains("माइक")
                || l.contains("माईक") || l.contains("आवाज़");
        if (promptVerb && promptNoun) return false;

        if (l.equals("on") || l.equals("off") || l.equals("try again")) return false;
        String[] bad = {"sorry", "didn't", "did not", "not able", "no result",
                "something went wrong", "could not", "couldn't", "try saying",
                "network", "please try"};
        for (String b : bad) {
            if (l.contains(b)) return false;
        }
        return true;
    }

    // ---- delivery ------------------------------------------------------

    private void deliver(final String target, final String text) {
        h.postDelayed(() -> deliverToApp(target, text, 0), 100);
    }

    private void deliverToApp(final String target, final String text, final int tries) {
        String top = activeAppPackage();
        L.i("deliverToApp try=" + tries + " top=" + top + " target=" + target);

        if (JIO_VA.equals(top)) {
            if (tries < 4) {
                L.f(this, "dismissing Jio overlay");
                performGlobalAction(GLOBAL_ACTION_BACK);
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 500);
            } else {
                L.f(this, "could not dismiss Jio overlay");
                busy = false;
            }
            return;
        }

        if (top == null || !top.equals(target)) {
            if (tries < 5) {
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 500);
            } else {
                L.f(this, "target never came to front (" + target + ")");
                busy = false;
            }
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (tries < 5) h.postDelayed(() -> deliverToApp(target, text, tries + 1), 500);
            else busy = false;
            return;
        }

        AccessibilityNodeInfo field = focusedEditable(root);
        if (field == null) field = firstEditable(root);
        if (field == null) field = findLeanbackField(root, 0);

        if (field != null) {
            if (typeInto(field, text)) {
                L.f(this, "typed into " + target + " (" + nodeName(field) + "): " + text);
                busy = false;
            } else if (tries < 5) {
                L.f(this, "could not type into " + nodeName(field) + "; retrying");
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 450);
            } else {
                L.f(this, "could not type into " + nodeName(field));
                busy = false;
            }
            return;
        }

        if (!searchClicked && tries < 4) {
            AccessibilityNodeInfo s = findSearch(root);
            if (s != null) {
                searchClicked = true;
                L.f(this, "opening search in " + target + " via " + nodeName(s));
                click(s);
                h.postDelayed(() -> deliverToApp(target, text, tries + 1), 900);
                return;
            }
        }

        if (!searchTried && tries >= 2) {
            searchTried = true;
            if (launchSearch(target, text)) {
                busy = false;
                return;
            }
        }

        if (tries < 5) {
            h.postDelayed(() -> deliverToApp(target, text, tries + 1), 600);
        } else {
            AccessibilityNodeInfo focus = null;
            try { focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); }
            catch (Throwable ignored) {}
            L.f(this, "no text field in " + target
                    + " (focus=" + nodeName(focus) + "); nothing typed");
            busy = false;
        }
    }

    // ---- search by URL / intent ----------------------------------------

    private static final String PRIME_PKG = "com.amazon.amazonvideo.livingroom";
    private static final String YOUTUBE_SEARCH =
            "https://www.youtube.com/results?search_query=%s";
    private static final String PRIME_SEARCH =
            "https://app.primevideo.com/search?phrase=%s";
    private static final String WEB_SEARCH =
            "https://www.google.com/search?q=%s";
    private static final String[] BROWSER_PKGS = {
            "com.phlox.tvwebbrowser",
            "com.hisense.odinbrowser",
    };

    /**
     * URL template for a package, or null. Apps whose UI exposes no
     * accessibility nodes at all (Cobalt-based YouTube builds, Amazon
     * Ignition) can only be searched through their own link handler.
     * A micbridge_search setting always wins:
     *
     *   adb shell settings put global micbridge_search \
     *     "com.example.app=https://example.com/search?q=%s"
     */
    private String searchTemplate(String pkg) {
        String raw = searchSetting();
        if (!raw.isEmpty()) {
            for (String line : raw.split("[;\\n]+")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String p = line.substring(0, eq).trim();
                String tpl = line.substring(eq + 1).trim();
                if (p.isEmpty() || tpl.isEmpty()) continue;
                boolean match = p.endsWith("*")
                        ? pkg.startsWith(p.substring(0, p.length() - 1))
                        : pkg.equals(p);
                if (match) return tpl;
            }
        }
        if (PRIME_PKG.equals(pkg)) return PRIME_SEARCH;
        for (String b : BROWSER_PKGS) {
            if (b.equals(pkg)) return WEB_SEARCH;
        }
        if (canOpen(YOUTUBE_SEARCH, pkg)) return YOUTUBE_SEARCH;
        return null;
    }

    private boolean canOpen(String url, String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.setPackage(pkg);
            return getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean launchSearch(String pkg, String text) {
        String tpl = searchTemplate(pkg);
        if (tpl == null) {
            L.f(this, "no search url for " + pkg);
            return false;
        }
        String q;
        try {
            q = URLEncoder.encode(text, "UTF-8");
        } catch (Throwable t) {
            q = text;
        }
        String url = tpl.replace("%s", q);
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage(pkg);
        try {
            if (getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                L.f(this, "search url not handled by " + pkg + ": " + url);
                return false;
            }
            startActivity(i);
            L.f(this, "search via url in " + pkg + ": " + url);
            return true;
        } catch (Throwable t) {
            L.f(this, "search url failed for " + pkg + ": " + t);
            return false;
        }
    }

    // ---- helpers -------------------------------------------------------

    private String activeAppPackage() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return null;
            String best = null;
            for (AccessibilityWindowInfo w : ws) {
                if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null) continue;
                String p = r.getPackageName().toString();
                if (w.isFocused() || w.isActive()) return p;
                if (best == null) best = p;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private AccessibilityNodeInfo byId(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
        return (l != null && !l.isEmpty()) ? l.get(0) : null;
    }

    private AccessibilityNodeInfo byDesc(AccessibilityNodeInfo root, String desc) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(desc);
        if (l != null) {
            for (AccessibilityNodeInfo n : l) {
                if (desc.contentEquals(String.valueOf(n.getContentDescription()))) return n;
            }
        }
        return null;
    }

    /** Editable node that currently holds input focus, or null. */
    private AccessibilityNodeInfo focusedEditable(AccessibilityNodeInfo root) {
        try {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null) return null;
            if (isEditable(f)) return f;
            return firstEditable(f);
        } catch (Throwable t) {
            return null;
        }
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (isEditable(root)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo c = root.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo r = firstEditable(c);
            if (r != null) return r;
        }
        return null;
    }

    private boolean isEditable(AccessibilityNodeInfo n) {
        if (n == null) return false;
        try {
            if (n.isEditable()) return true;
        } catch (Throwable ignored) {}
        CharSequence c = n.getClassName();
        return c != null
                && c.toString().toLowerCase(Locale.US).contains("edittext");
    }

    /**
     * Leanback search boxes (SmartTube, TizenTube, ...) are streaming text
     * views rather than editable fields, so they need their own lookup.
     */
    private AccessibilityNodeInfo findLeanbackField(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        try {
            CharSequence cls = n.getClassName();
            String c = cls == null ? "" : cls.toString();
            if (c.contains("StreamingTextView") || c.contains("SearchEditText")) return n;

            String id = null;
            try { id = n.getViewIdResourceName(); } catch (Throwable ignored) {}
            if (id != null) {
                String low = id.toLowerCase(Locale.US);
                if (low.contains("search_text") || low.endsWith("search_editor")
                        || low.endsWith("search_box") || low.endsWith("search_field")) {
                    return n;
                }
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo r = findLeanbackField(n.getChild(i), depth + 1);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Node that looks like the app's search entry point, or null. */
    private AccessibilityNodeInfo findSearch(AccessibilityNodeInfo root) {
        return findSearch(root, 0);
    }

    private AccessibilityNodeInfo findSearch(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 25) return null;
        try {
            // Never click around inside web page content (TV Bro & co): only
            // the app's own chrome can be a search entry point.
            CharSequence cls = n.getClassName();
            if (cls != null && cls.toString().contains("WebView")) return null;
            if (mentionsSearch(n.getText()) || mentionsSearch(n.getContentDescription())) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo r = findSearch(n.getChild(i), depth + 1);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean mentionsSearch(CharSequence cs) {
        return cs != null && cs.toString().toLowerCase(Locale.US).contains("search");
    }

    private void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo t = n;
        while (t != null && !t.isClickable()) t = t.getParent();
        if (t != null) t.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /** Focus/click the field first, then set the text. */
    private boolean typeInto(AccessibilityNodeInfo node, String text) {
        if (setText(node, text)) return true;
        try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); } catch (Throwable ignored) {}
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return setText(node, text);
    }

    private boolean setText(AccessibilityNodeInfo node, String text) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        if (!ok) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        }
        L.f(this, "ACTION_SET_TEXT ok=" + ok);
        return ok;
    }

    /** Short "Class#id" form for logs. */
    private String nodeName(AccessibilityNodeInfo n) {
        if (n == null) return "null";
        String cls = String.valueOf(n.getClassName());
        int dot = cls.lastIndexOf('.');
        String id = null;
        try { id = n.getViewIdResourceName(); } catch (Throwable ignored) {}
        if (id != null) {
            int s = id.indexOf(":id/");
            if (s >= 0) id = id.substring(s + 4);
        }
        return (dot >= 0 ? cls.substring(dot + 1) : cls) + (id == null ? "" : "#" + id);
    }
}
