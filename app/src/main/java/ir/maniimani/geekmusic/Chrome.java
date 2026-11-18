package ir.maniimani.geekmusic;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Full-screen ("immersive") mode plus an on-screen clock.
 * The head unit's top bar is hidden; a swipe down from the top edge or a tap on the thin
 * strip along the top shows it again for a few seconds.
 */
final class Chrome {

    private static final int HIDDEN = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    private static final int SHOWN = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    private static final long SHOW_FOR_MS = 4000;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hide;
    private TextView clock;

    Chrome(Activity a) {
        activity = a;
        // when the bar comes back for any reason (swipe, system), hide it again after a moment
        a.getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(vis -> {
            if ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                main.removeCallbacks(hide);
                main.postDelayed(hide, SHOW_FOR_MS);
            }
        });
    }

    void hide() {
        main.removeCallbacks(hide);
        activity.getWindow().getDecorView().setSystemUiVisibility(HIDDEN);
    }

    void showBriefly() {
        activity.getWindow().getDecorView().setSystemUiVisibility(SHOWN);
        main.removeCallbacks(hide);
        main.postDelayed(hide, SHOW_FOR_MS);
    }

    /** Thin invisible strip along the top edge: tap it to bring the bar back. */
    void addTopStrip(FrameLayout root, int heightPx) {
        View strip = new View(activity);
        strip.setOnClickListener(v -> showBriefly());
        strip.setContentDescription("نمایش نوار بالا");
        root.addView(strip, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, heightPx, Gravity.TOP));
    }

    // ------------------------------------------------------------------ clock

    TextView createClock(Typeface face, float sp, int color) {
        clock = new TextView(activity);
        clock.setTypeface(face);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        clock.setTextColor(color);
        clock.setGravity(Gravity.CENTER);
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(0.04f);
        updateClock();
        return clock;
    }

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    void start() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        activity.registerReceiver(tick, f);
        updateClock();
        hide();
    }

    void stop() {
        try {
            activity.unregisterReceiver(tick);
        } catch (Throwable ignored) {
        }
        main.removeCallbacks(hide);
    }

    private void updateClock() {
        if (clock == null) return;
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        String s = h + ":" + (m < 10 ? "0" : "") + m;
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(ch >= '0' && ch <= '9' ? (char) ('۰' + (ch - '0')) : ch);
        clock.setText(b.toString());
    }

    /** Centre the clock horizontally over {@code anchor}, vertically in the gap above it. */
    void placeClockAbove(View anchor, int minGapPx) {
        if (clock == null) return;
        int top = anchor.getTop() + ((View) anchor.getParent()).getTop();
        int left = anchor.getLeft() + ((View) anchor.getParent()).getLeft();
        int ch = clock.getHeight() > 0 ? clock.getHeight() : clock.getMeasuredHeight();
        boolean fits = top >= ch + minGapPx;
        clock.setVisibility(fits ? View.VISIBLE : View.INVISIBLE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) clock.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.width = anchor.getWidth();
        lp.leftMargin = left;
        lp.topMargin = Math.max(0, (top - ch) / 2);
        clock.setLayoutParams(lp);
    }
}
