package ir.maniimani.driveplay;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Draggable progress bar. Thin at rest, grows and shows a thumb while dragging.
 * Big invisible touch area so it is easy to hit while driving. Always left → right.
 */
final class SeekView extends View {

    interface Listener {
        void onScrub(float fraction);        // live, while dragging

        void onSeek(float fraction);         // finger lifted
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private float progress;
    private float grow;            // 0 = rest, 1 = dragging
    private boolean dragging;
    private int color = Color.WHITE;
    private Listener listener;
    private ValueAnimator growAnim, colorAnim;

    SeekView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        track.setColor(Color.rgb(30, 30, 33));
        fill.setColor(color);
        thumb.setColor(Color.rgb(236, 236, 238));
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isDragging() {
        return dragging;
    }

    void setProgress(float p) {
        if (dragging) return;
        p = Math.max(0f, Math.min(1f, p));
        if (Math.abs(p - progress) < 0.0004f) return;
        progress = p;
        invalidate();
    }

    void animateColor(int target) {
        if (colorAnim != null) colorAnim.cancel();
        colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), color, target);
        colorAnim.setDuration(800);
        colorAnim.addUpdateListener(a -> {
            color = (int) a.getAnimatedValue();
            fill.setColor(color);
            invalidate();
        });
        colorAnim.start();
    }

    private void animateGrow(float target) {
        if (growAnim != null) growAnim.cancel();
        growAnim = ValueAnimator.ofFloat(grow, target);
        growAnim.setDuration(160);
        growAnim.addUpdateListener(a -> {
            grow = (float) a.getAnimatedValue();
            invalidate();
        });
        growAnim.start();
    }

    private float pad() {
        return 9 * d; // room for the thumb at both ends
    }

    private float fractionAt(float x) {
        float w = getWidth() - 2 * pad();
        return Math.max(0f, Math.min(1f, (x - pad()) / Math.max(1f, w)));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                animateGrow(1f);
                progress = fractionAt(e.getX());
                if (listener != null) listener.onScrub(progress);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                progress = fractionAt(e.getX());
                if (listener != null) listener.onScrub(progress);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                progress = fractionAt(e.getX());
                dragging = false;
                animateGrow(0f);
                if (listener != null) listener.onSeek(progress);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                animateGrow(0f);
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = (4 + 3 * grow) * d;
        float cy = getHeight() / 2f;
        float left = pad(), right = getWidth() - pad();
        float rad = h / 2f;
        r.set(left, cy - h / 2f, right, cy + h / 2f);
        c.drawRoundRect(r, rad, rad, track);
        float x = left + (right - left) * progress;
        if (progress > 0f) {
            r.set(left, cy - h / 2f, Math.max(left + h, x), cy + h / 2f);
            c.drawRoundRect(r, rad, rad, fill);
        }
        float tr = (4 + 4 * grow) * d;
        thumb.setAlpha((int) (255 * (0.0f + 1f * Math.max(grow, 0.0f))));
        if (grow > 0.01f) c.drawCircle(x, cy, tr, thumb);
    }
}
