package dev.dedsec.micbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.app.SearchManager;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
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

    /** AccessibilityNodeInfo.ACTION_IME_ENTER, added in API 30. */
    private static final int ACTION_IME_ENTER = 0x01000000;

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

    /** how many search entry points we clicked for the current capture */
    private int searchClicks;

    /** set once we tried the search-URL fallback for the current capture */
    private boolean searchTried;

    /** set once we tried an ACTION_SEARCH intent for the current capture */
    private boolean searchIntentTried;

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
        L.f(this, "rules: mode=" + Rules.mode(this)
                + " enabled=" + Rules.enabled(this).size()
                + " disabled=" + Rules.disabled(this).size()
                + "; allow=" + allowSetting() + " block=" + blockSetting()
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
                    if (i.getBooleanExtra("demo", false)) {
                        // Visual test hook: listening -> searching -> fade,
                        // without needing the Jio overlay or a spoken query.
                        final String ft = t;
                        final String fq = q;
                        showListening(ft);
                        if (q != null && !q.isEmpty()) {
                            showTranscript(q);
                            h.postDelayed(new Runnable() {
                                @Override
                                public void run() { showSearchingCard(ft, fq); }
                            }, 1400);
                            h.postDelayed(new Runnable() {
                                @Override
                                public void run() { hideOverlay(); }
                            }, 4200);
                        }
                        return;
                    }
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
                        showTranscript(s);
                        return;
                    }
                }
            }
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) {
                String s = extractNodeText(src);
                if (s != null && plausibleQuery(s)) {
                    updateQueryFromEvent(s);
                    showTranscript(s);
                }
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
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
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
                            L.f(MicA11yService.this, "voice overlay over " + target
                                    + " (watched) -> blocked");
                        }
                    } else {
                        L.f(MicA11yService.this,
                                "voice overlay over " + target + " (watched) -> capturing");
                        startCapture(target);
                    }
                } else {
                    watchForOverlay(keyTarget, attempt + 1);
                }
            }
        }, attempt == 0 ? 150 : 250);
    }

    @Override
    public void onInterrupt() {
        // Never leave the full-screen card stranded if the binding drops.
        destroyOverlay();
        busy = false;
    }

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
     * True when the package keeps its stock mic behaviour. The settings
     * screen (Rules) decides by default; the adb overrides stay live and win:
     * micbridge_allow forces routing on, micbridge_block forces it off.
     */
    private boolean isBlocked(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (JIO_VA.equals(pkg) || getPackageName().equals(pkg)) return true;
        if (isAllowed(pkg)) return false;
        if (matchesList(pkg, blockSetting())) return true;
        return !Rules.isEnabled(this, pkg);
    }

    private String homePackage() {
        return Rules.homePackage(this);
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
        searchClicks = 0;
        searchTried = false;
        searchIntentTried = false;
        overlaySeen = false;
        overlayAbsentCount = 0;
        currentQuery = "";
        showListening(target);
        poll(target, 0);
    }

    private void poll(final String target, final int tick) {
        if (tick > 90) {          // ~27 s
            L.f(this, "capture timeout");
            overlayMissed();
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
                showTranscript(q);
            }
            if (lastQuery != null) {
                // The result screen carries a banner/title; prefer that, and
                // fall back to a text that stayed unchanged for ~600 ms.
                boolean finalUi = hasId(JIO_VA + ":id/banner_layout")
                        || hasId(JIO_VA + ":id/title");
                if ((finalUi && stableCount >= 1) || stableCount >= 2) {
                    L.f(this, "captured query: \"" + lastQuery + "\" (finalUi=" + finalUi + ")");
                    showSearchingCard(target, lastQuery);
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
                    showSearchingCard(target, lastQuery);
                    deliver(target, lastQuery);
                } else if (tick > 6) {   // ~900 ms, allow a late event
                    L.f(this, "overlay closed before a query appeared (top=" + top + ")");
                    overlayMissed();
                    busy = false;
                } else {
                    h.postDelayed(new Runnable() {
            @Override
            public void run() { poll(target, tick + 1); }
        }, 300);
                }
                return;
            }
        } else if (tick > 26) {      // ~4 s, overlay never showed up
            L.f(this, "overlay never appeared after mic press");
            hideOverlay();
            busy = false;
            return;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() { poll(target, tick + 1); }
        }, 300);
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
        // Each delivery gets its own shot at opening search, including
        // repeated debug broadcasts in one service lifetime.
        currentQuery = text == null ? "" : text;
        searchClicks = 0;
        searchTried = false;
        searchIntentTried = false;
        h.postDelayed(new Runnable() {
            @Override
            public void run() { deliverToApp(target, text, 0); }
        }, 100);
    }

    private void deliverToApp(final String target, final String text, final int tries) {
        String top = activeAppPackage();
        L.i("deliverToApp try=" + tries + " top=" + top + " target=" + target);

        if (JIO_VA.equals(top)) {
            if (tries < 4) {
                L.f(this, "dismissing Jio overlay");
                performGlobalAction(GLOBAL_ACTION_BACK);
                h.postDelayed(new Runnable() {
            @Override
            public void run() { deliverToApp(target, text, tries + 1); }
        }, 500);
            } else {
                L.f(this, "could not dismiss Jio overlay");
                hideOverlay();
                busy = false;
            }
            return;
        }

        if (top == null || !top.equals(target)) {
            if (tries < 9) {
                h.postDelayed(new Runnable() {
            @Override
            public void run() { deliverToApp(target, text, tries + 1); }
        }, 500);
            } else {
                L.f(this, "target never came to front (" + target + ")");
                hideOverlay();
                busy = false;
            }
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo field = findField(target, root);

        if (field != null) {
            boolean lean = isLeanbackField(field);
            if (typeInto(field, text)) {
                L.f(this, "typed into " + target + " (" + nodeName(field) + "): " + text);
                // A plain EditText usually needs its own Search button or an
                // IME action; Leanback fields search as they are filled.
                if (!lean) submitSearch(target, root, field);
                hideOverlay();
                busy = false;
            } else if (tries < 5) {
                L.f(this, "could not type into " + nodeName(field) + "; retrying");
                h.postDelayed(new Runnable() {
            @Override
            public void run() { deliverToApp(target, text, tries + 1); }
        }, 450);
            } else {
                L.f(this, "could not type into " + nodeName(field));
                hideOverlay();
                busy = false;
            }
            return;
        }

        if (searchClicks < 3 && tries < 7) {
            AccessibilityNodeInfo s = findSearchAnywhere(target, root);
            if (s != null) {
                searchClicks++;
                L.f(this, "opening search in " + target + " via " + nodeName(s));
                click(s);
                h.postDelayed(new Runnable() {
            @Override
            public void run() { deliverToApp(target, text, tries + 1); }
        }, 900);
                return;
            }
        }

        if (!searchTried && tries >= 2) {
            searchTried = true;
            if (launchSearch(target, text)) {
                busy = false;
                return;
            }
            if (!searchIntentTried) {
                searchIntentTried = true;
                if (launchSearchIntent(target, text)) {
                    busy = false;
                    return;
                }
            }
        }

        if (tries < 9) {
            h.postDelayed(new Runnable() {
            @Override
            public void run() { deliverToApp(target, text, tries + 1); }
        }, 600);
        } else {
            AccessibilityNodeInfo focus = null;
            if (root != null) {
                try { focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); }
                catch (Throwable ignored) {}
            }
            L.f(this, "no text field in " + target
                    + " (focus=" + nodeName(focus) + "); nothing typed");
            hideOverlay();
            busy = false;
        }
    }

    // ---- finding a text field / search entry ---------------------------

    /** Focused/editable field in the active window, else in any window of pkg. */
    private AccessibilityNodeInfo findField(String pkg, AccessibilityNodeInfo activeRoot) {
        AccessibilityNodeInfo f = fieldIn(activeRoot);
        if (f != null) return f;
        for (AccessibilityNodeInfo r : appRoots(pkg)) {
            if (r.equals(activeRoot)) continue;
            f = fieldIn(r);
            if (f != null) return f;
        }
        return null;
    }

    private AccessibilityNodeInfo fieldIn(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo f = focusedEditable(root);
        if (f == null) f = firstEditable(root);
        if (f == null) f = findLeanbackField(root, 0);
        return f;
    }

    /** Roots of every on-screen window that belongs to pkg. */
    private List<AccessibilityNodeInfo> appRoots(String pkg) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (pkg == null || pkg.isEmpty()) return out;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return out;
            for (AccessibilityWindowInfo w : ws) {
                if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null) continue;
                if (pkg.contentEquals(r.getPackageName())) out.add(r);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private AccessibilityNodeInfo findSearchAnywhere(String pkg, AccessibilityNodeInfo activeRoot) {
        AccessibilityNodeInfo s = findSearch(activeRoot);
        if (s != null) return s;
        for (AccessibilityNodeInfo r : appRoots(pkg)) {
            if (r.equals(activeRoot)) continue;
            s = findSearch(r);
            if (s != null) return s;
        }
        // Last resort: some TV rails (Zee5) only expose icon-only items
        // until they are expanded. Their top item is the search entry.
        return findRailSearch(pkg, activeRoot);
    }

    /**
     * Finds the top icon-only entry of a left-hand nav rail: a clickable
     * view hugging the left edge, in the lower part of the screen, with no
     * text and an icon child. Zee5's collapsed rail (search / upgrade /
     * account) has exactly this shape and no accessible labels.
     */
    private AccessibilityNodeInfo findRailSearch(String pkg, AccessibilityNodeInfo activeRoot) {
        List<AccessibilityNodeInfo> roots = appRoots(pkg);
        if (activeRoot != null) {
            boolean have = false;
            for (AccessibilityNodeInfo r : roots) {
                if (r.equals(activeRoot)) { have = true; break; }
            }
            if (!have) roots.add(0, activeRoot);
        }
        for (AccessibilityNodeInfo r : roots) {
            List<AccessibilityNodeInfo> rail = new ArrayList<>();
            collectRail(r, rail, 0);
            if (rail.isEmpty()) continue;
            Collections.sort(rail, new java.util.Comparator<AccessibilityNodeInfo>() {
                @Override
                public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                    android.graphics.Rect ra = new android.graphics.Rect();
                    android.graphics.Rect rb = new android.graphics.Rect();
                    a.getBoundsInScreen(ra);
                    b.getBoundsInScreen(rb);
                    return ra.top - rb.top;
                }
            });
            L.f(this, "rail: " + rail.size() + " icon-only entries");
            return rail.get(0);
        }
        return null;
    }

    private void collectRail(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int d) {
        if (n == null || d > 25) return;
        try {
            android.graphics.Rect b = new android.graphics.Rect();
            n.getBoundsInScreen(b);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            boolean leftEdge = b.left <= dm.widthPixels * 0.06f;
            boolean railSized = b.width() > 0 && b.width() <= dm.widthPixels * 0.35f
                    && b.height() >= 40 && b.height() <= 170;
            boolean lowerHalf = b.top >= dm.heightPixels * 0.33f;
            if (n.isClickable() && leftEdge && railSized && lowerHalf
                    && !hasText(n) && hasIconChild(n)) {
                out.add(n);
                return;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                collectRail(n.getChild(i), out, d + 1);
            }
        } catch (Throwable ignored) {}
    }

    private boolean hasText(AccessibilityNodeInfo n) {
        try {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            return (t != null && t.length() > 0) || (d != null && d.length() > 0);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasIconChild(AccessibilityNodeInfo n) {
        return hasIconChild(n, 0);
    }

    private boolean hasIconChild(AccessibilityNodeInfo n, int d) {
        if (n == null || d > 4) return false;
        try {
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                CharSequence k = c.getClassName();
                if (k != null && k.toString().contains("ImageView")) return true;
                if (hasIconChild(c, d + 1)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isLeanbackField(AccessibilityNodeInfo n) {
        if (n == null) return false;
        try {
            CharSequence c = n.getClassName();
            if (c != null) {
                String s = c.toString();
                if (s.contains("StreamingTextView") || s.contains("SearchEditText")) return true;
            }
            String id = viewId(n);
            if (id != null) {
                String low = id.toLowerCase(Locale.US);
                if (low.contains("search_text") || low.endsWith("search_editor")
                        || low.endsWith("search_box") || low.endsWith("search_field")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Plain EditTexts (Zee5, Hotstar, ...) do not run the search when their
     * text is set: they have a separate Search button, or the IME action
     * submits it. Try the app's own button first, then the IME action.
     */
    private void submitSearch(final String pkg, final AccessibilityNodeInfo root,
                              final AccessibilityNodeInfo field) {
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                AccessibilityNodeInfo btn = root == null
                        ? null : findSubmitButton(root, field, 0);
                if (btn == null) {
                    for (AccessibilityNodeInfo r : appRoots(pkg)) {
                        btn = findSubmitButton(r, field, 0);
                        if (btn != null) break;
                    }
                }
                if (btn != null) {
                    L.f(MicA11yService.this, "search submit via " + nodeName(btn));
                    click(btn);
                    return;
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        boolean ok = field.performAction(ACTION_IME_ENTER);
                        L.f(MicA11yService.this, "search submit via IME enter ok=" + ok);
                    } catch (Throwable t) {
                        L.f(MicA11yService.this, "search submit failed: " + t);
                    }
                }
            }
        }, 400);
    }

    private AccessibilityNodeInfo findSubmitButton(AccessibilityNodeInfo n,
                                                   AccessibilityNodeInfo field, int depth) {
        if (n == null || depth > 30) return null;
        if (field != null && n.equals(field)) return null;
        try {
            String id = viewId(n);
            if (id != null) {
                String low = id.toLowerCase(Locale.US);
                if (low.contains("search_button") || low.contains("search_btn")
                        || low.contains("btn_search") || low.contains("search_submit")
                        || low.endsWith("search_go")) {
                    return n;
                }
            }
            CharSequence cls = n.getClassName();
            String c = cls == null ? "" : cls.toString();
            if ((c.endsWith("Button") || c.contains("ImageButton"))
                    && (mentionsSearch(n.getText())
                        || mentionsSearch(n.getContentDescription()))) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo r = findSubmitButton(n.getChild(i), field, depth + 1);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String viewId(AccessibilityNodeInfo n) {
        try { return n.getViewIdResourceName(); } catch (Throwable t) { return null; }
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
            // Cover the launch itself and the app's loading screen. The card
            // is opaque, so it has to be removed before the screenshot-based
            // Prime detector runs.
            showSearchingCard(pkg, text);
            startActivity(i);
            L.f(this, "search via url in " + pkg + ": " + url);
            if (PRIME_PKG.equals(pkg)) {
                // Prime's Android TV build restarts its web session for every
                // external link and asks "Who's watching?". Answer the picker
                // with OK while the cover hides it, then let the screenshot
                // detector take over as the safety net.
                primePresses = 0;
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (pressOk(pkg)) {
                            primePresses++;
                            L.f(MicA11yService.this, "prime: answered picker with OK");
                        }
                        h.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                hideOverlayNow();
                                primeSelectProfile(pkg, 0);
                            }
                        }, 1700);
                    }
                }, 1500);
            } else {
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() { hideOverlay(); }
                }, 2000);
            }
            return true;
        } catch (Throwable t) {
            hideOverlay();
            L.f(this, "search url failed for " + pkg + ": " + t);
            return false;
        }
    }

    /**
     * Universal last resort: hand the query to the app's own ACTION_SEARCH
     * activity. Works for apps that implement Android's search framework but
     * expose no field or search entry on the current screen.
     */
    private boolean launchSearchIntent(String pkg, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEARCH);
            i.setPackage(pkg);
            i.putExtra(SearchManager.QUERY, text);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                L.f(this, "no ACTION_SEARCH handler in " + pkg);
                return false;
            }
            showSearchingCard(pkg, text);
            startActivity(i);
            L.f(this, "search via ACTION_SEARCH in " + pkg);
            h.postDelayed(new Runnable() {
                @Override
                public void run() { hideOverlay(); }
            }, 2000);
            return true;
        } catch (Throwable t) {
            hideOverlay();
            L.f(this, "ACTION_SEARCH failed for " + pkg + ": " + t);
            return false;
        }
    }

    // ---- listening / searching overlay ---------------------------------

    private static final int PRIME_ATTEMPTS = 20;
    private int primePresses;

    private SearchOverlay overlay;
    private String currentQuery = "";
    private String lastOverlayQuery = "";

    private SearchOverlay overlay() {
        if (overlay == null) overlay = new SearchOverlay(this);
        return overlay;
    }

    /** Shows the listening card over the Jio assistant. */
    private void showListening(String pkg) {
        if (isBlocked(pkg)) return;
        try {
            String label = appLabel(pkg);
            overlay().showListening(pkg, label);
            lastOverlayQuery = "";
            L.f(this, "overlay: listening over " + label);
        } catch (Throwable t) {
            L.f(this, "overlay listening failed: " + t);
        }
    }

    /** Feeds the live recognised text into the listening card. */
    private void showTranscript(String text) {
        if (overlay == null || text == null) return;
        try {
            overlay.setTranscript(text);
        } catch (Throwable ignored) {
        }
    }

    /** Switches to the timeout/error state (auto-fades). */
    private void overlayMissed() {
        if (overlay != null && overlay.isShowing()) {
            try {
                overlay.showMissed();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Shows the searching card with the final query. */
    private void showSearchingCard(String pkg, String query) {
        String q = query == null ? "" : query;
        try {
            overlay().showSearching(pkg, appLabel(pkg), q);
            if (!q.equals(lastOverlayQuery)) {
                lastOverlayQuery = q;
                L.f(this, "overlay: searching \"" + q + "\" in " + appLabel(pkg));
            }
        } catch (Throwable t) {
            L.f(this, "overlay searching failed: " + t);
        }
    }

    private void hideOverlay() {
        if (overlay == null) return;
        try {
            overlay.dismiss();
        } catch (Throwable ignored) {
        }
    }

    /** Removes the card at once, for screenshot-based checks. */
    private void hideOverlayNow() {
        if (overlay == null) return;
        try {
            overlay.hideImmediately();
        } catch (Throwable ignored) {
        }
    }

    private boolean overlayShowing() {
        return overlay != null && overlay.isShowing();
    }

    private void destroyOverlay() {
        if (overlay == null) return;
        try {
            overlay.destroy();
        } catch (Throwable ignored) {
        }
        overlay = null;
    }

    /** User-facing name for the overlay. */
    private String appLabel(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence l = getPackageManager().getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Throwable ignored) {}
        return pkg;
    }

    @Override
    public void onDestroy() {
        destroyOverlay();
        super.onDestroy();
    }

    /**
     * Waits for Prime's profile picker and presses OK on the profile it
     * highlights.
     *
     * Prime's Android TV build always opens "Who's watching?" before running
     * an external search, but the search is queued behind the gate: picking a
     * profile runs it. The gate is drawn by Prime's own engine on a single
     * Ignite surface and exposes no usable accessibility nodes, so the picker
     * is recognised from a screenshot (its focus ring) and the engine's
     * clickable surface node is clicked, which the app treats as the remote's
     * select key.
     */
    private void primeSelectProfile(final String pkg, final int attempt) {
        if (Build.VERSION.SDK_INT < 30) return;
        if (attempt > PRIME_ATTEMPTS || primePresses >= 3) {
            L.f(this, "prime: profile picker not detected");
            hideOverlay();
            return;
        }
        // While the card is up it is the front window, so do not use
        // activeAppPackage() to decide whether Prime is still in front.
        if (!overlayShowing() && !pkg.equals(activeAppPackage())) {
            h.postDelayed(new Runnable() {
            @Override
            public void run() { primeSelectProfile(pkg, attempt + 1); }
        }, 500);
            return;
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult res) {
                            HardwareBuffer hb = res.getHardwareBuffer();
                            Bitmap soft = null;
                            boolean picker = false;
                            try {
                                // getPixel() is not supported on the hardware
                                // bitmap, so copy it into a software one first.
                                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                                if (hw != null) {
                                    soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    hw.recycle();
                                }
                                picker = soft != null && findProfileRingY(soft) >= 0;
                            } catch (Throwable t) {
                                L.e("prime screenshot: " + t);
                            } finally {
                                if (soft != null) soft.recycle();
                                try { hb.close(); } catch (Throwable ignored) {}
                            }
                            if (picker) {
                                if (pressOk(pkg)) {
                                    primePresses++;
                                    L.f(MicA11yService.this, "prime: picked highlighted profile ("
                                            + primePresses + ")");
                                    showSearchingCard(pkg, currentQuery);
                                    // Let the engine load the queued search
                                    // behind the card, then look again.
                                    h.postDelayed(new Runnable() {
            @Override
            public void run() { hideOverlayNow(); primeSelectProfile(pkg, attempt + 1); }
        }, 1800);
                                    return;
                                }
                            } else if (primePresses > 0) {
                                // A profile was picked and the picker is gone:
                                // Prime is already running the queued search.
                                hideOverlay();
                                L.f(MicA11yService.this, "prime: picker gone, search running");
                                return;
                            }
                            h.postDelayed(new Runnable() {
            @Override
            public void run() { primeSelectProfile(pkg, attempt + 1); }
        }, 900);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            // 3 = the engine's minimum interval between
                            // screenshots; back off so the next one works.
                            L.e("prime screenshot failed: " + errorCode);
                            h.postDelayed(new Runnable() {
            @Override
            public void run() { primeSelectProfile(pkg, attempt + 1); }
        }, 1100);
                        }
                    });
        } catch (Throwable t) {
            L.e("prime takeScreenshot: " + t);
        }
    }

    /**
     * Sends "select" to whatever Prime has focused by clicking the engine's
     * surface view. Accessibility gesture injection is refused on this device
     * (its TV display is not touchable), but the Ignite surface is clickable
     * and maps ACTION_CLICK to the remote's OK key.
     */
    private boolean pressOk(String pkg) {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return false;
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                if (p == null || !pkg.contentEquals(p)) continue;
                AccessibilityNodeInfo n = firstClickable(r, 0);
                if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            L.e("prime pressOk: " + t);
        }
        return false;
    }

    private AccessibilityNodeInfo firstClickable(AccessibilityNodeInfo n, int d) {
        if (n == null || d > 30) return null;
        if (n.isClickable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = firstClickable(n.getChild(i), d + 1);
            if (c != null) return c;
        }
        return null;
    }

    /**
     * Finds the highlighted profile's vertical centre. Only the focus ring is
     * near-white, and it straddles the fixed avatar column: each ring row has
     * white at both the avatar's left and right edge, so the tallest band of
     * rows that are white on both sides is the ring. Its height is checked so
     * that other white areas (text, artwork) cannot be mistaken for it, and
     * the ring's interior must stay dark/coloured rather than white.
     */
    private int findProfileRingY(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int lx0 = Math.max(0, (int) (w * 0.070));
        int lx1 = Math.min(w - 1, (int) (w * 0.095));
        int rx0 = Math.max(0, (int) (w * 0.148));
        int rx1 = Math.min(w - 1, (int) (w * 0.170));
        int runStart = -1, bestStart = -1, bestLen = 0;
        for (int y = (int) (h * 0.04); y < (int) (h * 0.96); y++) {
            boolean ringRow = rowHasWhite(bmp, y, lx0, lx1)
                    && rowHasWhite(bmp, y, rx0, rx1);
            if (ringRow) {
                if (runStart < 0) runStart = y;
            } else if (runStart >= 0) {
                int len = y - runStart;
                if (len > bestLen) { bestLen = len; bestStart = runStart; }
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            int len = (int) (h * 0.96) - runStart;
            if (len > bestLen) { bestLen = len; bestStart = runStart; }
        }
        // A profile ring is a fixed fraction of the screen height; anything
        // much shorter or taller is some other white element.
        if (bestLen < (int) (h * 0.05) || bestLen > (int) (h * 0.20)) return -1;
        int cy = bestStart + bestLen / 2;
        // Inside the ring sits the avatar image, never a white block.
        int ix0 = (int) (w * 0.105);
        int ix1 = (int) (w * 0.145);
        int bright = 0, total = 0;
        for (int x = ix0; x <= ix1; x += 2) {
            total++;
            if (isWhite(bmp.getPixel(x, cy))) bright++;
        }
        if (total == 0 || bright * 2 > total) return -1;
        return cy;
    }

    private boolean rowHasWhite(Bitmap bmp, int y, int x0, int x1) {
        for (int x = x0; x <= x1; x++) {
            if (isWhite(bmp.getPixel(x, y))) return true;
        }
        return false;
    }

    private boolean isWhite(int c) {
        int r = (c >> 16) & 0xff;
        int g = (c >> 8) & 0xff;
        int b = c & 0xff;
        return r > 230 && g > 230 && b > 230;
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
            // The search box itself is handled by findField() before this.
            if (isEditable(n)) return null;
            if (mentionsSearch(n.getText()) || mentionsSearch(n.getContentDescription())) {
                return n;
            }
            // Icon-only entries (Zee5's rail, Hotstar's magnifier) carry no
            // text, but their view id does.
            String id = viewId(n);
            if (id != null && n.isClickable()
                    && id.toLowerCase(Locale.US).contains("search")) {
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
