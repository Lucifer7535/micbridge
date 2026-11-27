package dev.dedsec.micbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.animation.ValueAnimator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen card that replaces the Jio voice assistant visual while
 * MicBridge listens, shows the live transcript and then reports what it is
 * searching for. It is an accessibility overlay: not focusable, not
 * touchable, so it cannot steal the mic key or the app's input.
 *
 * Everything is built in code with simple Canvas animations, because the
 * APK is assembled with build-tools only (no support libraries).
 */
final class SearchOverlay {

    private static final int SCRIM_A = 0xFF0C121B;
    private static final int SCRIM_B = 0xFF05080C;
    private static final int CARD = 0xF2141B24;
    private static final int CARD_STROKE = 0x33FFFFFF;
    private static final int TEXT = 0xFFE9EDF3;
    private static final int MUTED = 0xFF95A1B2;
    private static final int ACCENT = 0xFF7FB2FF;
    private static final int ACCENT_2 = 0xFFC4B5FD;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());

    private View root;
    private View card;
    private TextView eyebrow;
    private TextView status;
    private TextView query;
    private TextView hint;
    private MicBadgeView badge;
    private WaveView wave;
    private SweepView sweep;

    private boolean searching;
    private boolean dismissing;
    private Runnable missed;

    SearchOverlay(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() {
        return root != null && !dismissing;
    }

    /** Starts the "listening" state over the Jio assistant. */
    void showListening(String pkg, String label) {
        removeNow();
        searching = false;
        build(pkg, label);
        status.setText("Listening\u2026");
        query.setTextColor(MUTED);
        query.setText("Speak now");
        hint.setText("Say what you want to watch");
        wave.setVisibility(View.VISIBLE);
        sweep.setVisibility(View.GONE);
        badge.setSearching(false);
        badge.startPulse();
        wave.start();
        enter();
    }

    /** Updates the partial/final recognised text while listening. */
    void setTranscript(String text) {
        if (root == null || searching || dismissing) return;
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        query.setTextColor(TEXT);
        query.setText(t);
    }

    /** Switches to the "searching" state with the actual query. */
    void showSearching(String pkg, String label, String text) {
        if (!isShowing()) {
            showListening(pkg, label);
        }
        searching = true;
        h.removeCallbacks(missedRunnable);
        badge.stopPulse();
        badge.setSearching(true);
        wave.stop();
        wave.setVisibility(View.GONE);
        sweep.setVisibility(View.VISIBLE);
        sweep.start();
        status.setText("Searching");
        query.setTextColor(ACCENT);
        String q = text == null ? "" : text.trim();
        query.setText(q.isEmpty() ? "\u2026" : "\u201c" + q + "\u201d");
        hint.setText("Opening results\u2026");
    }

    /** Short "nothing recognised" state, then fades out by itself. */
    void showMissed() {
        if (root == null || dismissing) return;
        searching = true;
        badge.stopPulse();
        badge.setSearching(false);
        wave.stop();
        wave.setVisibility(View.GONE);
        sweep.stop();
        sweep.setVisibility(View.GONE);
        status.setText("Didn\u2019t catch that");
        query.setTextColor(MUTED);
        query.setText("Try again with the mic key");
        hint.setText("");
        h.removeCallbacks(missedRunnable);
        h.postDelayed(missedRunnable, 1500);
    }

    private final Runnable missedRunnable = new Runnable() {
        @Override
        public void run() {
            dismiss();
        }
    };

    /** Fades the card out and removes its window. */
    void dismiss() {
        h.removeCallbacks(missedRunnable);
        if (root == null || dismissing) return;
        dismissing = true;
        final View v = root;
        if (wave != null) wave.stop();
        if (sweep != null) sweep.stop();
        if (badge != null) badge.stopPulse();
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(180)
                .setInterpolator(new LinearInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (root == v) removeNow();
                    }
                }).start();
    }

    /**
     * Removes the window without fading. Needed before a screenshot-based
     * check, because a fading overlay would still be captured.
     */
    void hideImmediately() {
        removeNow();
    }

    /** Removes the window immediately; safe to call at any time. */
    void destroy() {
        h.removeCallbacks(missedRunnable);
        removeNow();
    }

    private void removeNow() {
        h.removeCallbacks(missedRunnable);
        if (badge != null) badge.stopPulse();
        if (wave != null) wave.stop();
        if (sweep != null) sweep.stop();
        if (root != null) {
            try {
                root.animate().cancel();
                wm.removeViewImmediate(root);
            } catch (Throwable ignored) {
            }
        }
        root = null;
        card = null;
        eyebrow = null;
        status = null;
        query = null;
        hint = null;
        badge = null;
        wave = null;
        sweep = null;
        searching = false;
        dismissing = false;
    }

    // ---- construction ---------------------------------------------------

    private void build(String pkg, String label) {
        FrameLayout scrim = new FrameLayout(ctx);
        scrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{SCRIM_A, SCRIM_B}));
        scrim.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(40), dp(34), dp(40), dp(30));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(26));
        bg.setStroke(Math.max(1, dp(1)), CARD_STROKE);
        box.setBackground(bg);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            box.setElevation(dp(18));
        }

        int cardW = Math.max(dp(560),
                Math.min(dp(760),
                        (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.68f)));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(box, cardLp);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        badge = new MicBadgeView(ctx);
        head.addView(badge, new LinearLayout.LayoutParams(dp(92), dp(92)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(26), 0, 0, 0);

        eyebrow = new TextView(ctx);
        eyebrow.setTextColor(MUTED);
        eyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setSingleLine(true);
        eyebrow.setEllipsize(android.text.TextUtils.TruncateAt.END);
        eyebrow.setText("MICBRIDGE  \u00b7  " + (label == null ? pkg : label));
        texts.addView(eyebrow);

        status = new TextView(ctx);
        status.setTextColor(TEXT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 31);
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.setSingleLine(true);
        status.setPadding(0, dp(4), 0, dp(2));
        texts.addView(status);

        query = new TextView(ctx);
        query.setTextColor(TEXT);
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        query.setMaxLines(2);
        query.setEllipsize(android.text.TextUtils.TruncateAt.END);
        query.setLineSpacing(dp(2), 1f);
        texts.addView(query);

        head.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head);

        FrameLayout indicator = new FrameLayout(ctx);
        wave = new WaveView(ctx);
        indicator.addView(wave, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(64)));
        sweep = new SweepView(ctx);
        sweep.setVisibility(View.GONE);
        indicator.addView(sweep, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(64)));
        LinearLayout.LayoutParams indLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        indLp.topMargin = dp(20);
        box.addView(indicator, indLp);

        hint = new TextView(ctx);
        hint.setTextColor(MUTED);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        box.addView(hint);

        root = scrim;
        card = box;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            root = null;
            L.f(ctx, "overlay failed: " + t);
        }
    }

    private void enter() {
        if (root == null) return;
        // Appear at full opacity at once: the card is shown on the mic key
        // press to cover the Jio window, and a fade-in would let the stock
        // screen show through for ~200 ms.
        root.setAlpha(1f);
        if (card != null) {
            card.setScaleX(0.96f);
            card.setScaleY(0.96f);
            card.setTranslationY(dp(14));
            card.animate().scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    // ---- animated pieces ------------------------------------------------

    /** Round badge with a mic (listening) or a magnifier (searching). */
    private final class MicBadgeView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean searchIcon;
        private float pulse;
        private ValueAnimator anim;

        MicBadgeView(Context c) {
            super(c);
            fill.setStyle(Paint.Style.FILL);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
        }

        void setSearching(boolean s) {
            searchIcon = s;
            invalidate();
        }

        void startPulse() {
            stopPulse();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1500);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setRepeatMode(ValueAnimator.RESTART);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    pulse = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        void stopPulse() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            pulse = 0f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(w, h) / 2f - dp(3);

            if (!searchIcon && pulse > 0f) {
                fill.setColor(ACCENT);
                fill.setAlpha((int) (52 * (1f - pulse)));
                c.drawCircle(cx, cy, r * (1f + 0.30f * pulse), fill);
            }

            fill.setAlpha(255);
            fill.setColor(0xFF17212E);
            c.drawCircle(cx, cy, r, fill);

            line.setColor(0x557FB2FF);
            line.setStrokeWidth(Math.max(1, dp(1)));
            c.drawCircle(cx, cy, r, line);

            if (searchIcon) {
                line.setColor(ACCENT);
                line.setStrokeWidth(dp(3));
                float sr = r * 0.30f;
                c.drawCircle(cx - r * 0.08f, cy - r * 0.08f, sr, line);
                c.drawLine(cx + sr * 0.55f, cy + sr * 0.55f,
                        cx + r * 0.36f, cy + r * 0.36f, line);
            } else {
                fill.setColor(ACCENT);
                float mw = r * 0.34f;
                float top = cy - r * 0.38f;
                float bot = cy + r * 0.06f;
                rect.set(cx - mw / 2f, top, cx + mw / 2f, bot);
                c.drawRoundRect(rect, mw / 2f, mw / 2f, fill);

                line.setColor(ACCENT);
                line.setStrokeWidth(dp(2));
                rect.set(cx - r * 0.30f, cy - r * 0.22f,
                        cx + r * 0.30f, cy + r * 0.22f);
                c.drawArc(rect, 0f, 180f, false, line);
                c.drawLine(cx, cy + r * 0.22f, cx, cy + r * 0.36f, line);
                c.drawLine(cx - r * 0.13f, cy + r * 0.36f,
                        cx + r * 0.13f, cy + r * 0.36f, line);
            }
        }
    }

    /** Five rounded voice bars. Purely decorative: Jio owns the microphone. */
    private final class WaveView extends View {
        private static final int BARS = 5;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float phase;
        private ValueAnimator anim;

        WaveView(Context c) {
            super(c);
            p.setStyle(Paint.Style.FILL);
        }

        void start() {
            stop();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(900);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setRepeatMode(ValueAnimator.RESTART);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    phase = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        void stop() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            phase = 0f;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w > 0) {
                p.setShader(new LinearGradient(0, 0, w, 0,
                        ACCENT, ACCENT_2, Shader.TileMode.CLAMP));
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float barW = dp(8);
            float gap = dp(10);
            float total = BARS * barW + (BARS - 1) * gap;
            float left = (w - total) / 2f;
            float minH = dp(7);
            float maxH = dp(40);
            for (int i = 0; i < BARS; i++) {
                float t = (float) (0.5 + 0.5 * Math.sin(
                        (phase + i * 0.17f) * Math.PI * 2));
                float bh = minH + (maxH - minH) * t;
                float x = left + i * (barW + gap);
                float top = (h - bh) / 2f;
                p.setAlpha((int) (120 + 135 * t));
                c.drawRoundRect(x, top, x + barW, top + bh,
                        barW / 2f, barW / 2f, p);
            }
        }
    }

    /** A moving highlight on a thin track, for the searching state. */
    private final class SweepView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private LinearGradient shader;
        private float segW;
        private float phase;
        private ValueAnimator anim;

        SweepView(Context c) {
            super(c);
            track.setStyle(Paint.Style.FILL);
            track.setColor(0x22FFFFFF);
            p.setStyle(Paint.Style.FILL);
        }

        void start() {
            stop();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1100);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setRepeatMode(ValueAnimator.RESTART);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    phase = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        void stop() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            phase = 0f;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            segW = w * 0.28f;
            if (segW > 0) {
                shader = new LinearGradient(0, 0, segW, 0,
                        new int[]{0x007FB2FF, ACCENT, 0x00C4B5FD},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cy = h / 2f;
            float th = dp(3);
            float radius = th / 2f;
            c.drawRoundRect(dp(2), cy - radius, w - dp(2), cy + radius,
                    radius, radius, track);

            if (shader == null || segW <= 0) return;
            float x = -segW + phase * (w + segW);
            matrix.setTranslate(x, 0f);
            shader.setLocalMatrix(matrix);
            p.setShader(shader);
            c.drawRoundRect(x, cy - dp(3), x + segW, cy + dp(3),
                    dp(3), dp(3), p);
        }
    }
}
