package ir.maniimani.driveplay;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Circular play/pause button drawn by hand, so it is always a perfect circle
 * whatever size the layout gives it. Icons cross-fade with a small scale.
 */
final class PlayButton extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icPlay, icPause;
    private final float ringWidth, iconFrac;

    private boolean playing;
    private float morph; // 0 = play icon, 1 = pause icon
    private int ringColor = Color.WHITE;
    private ValueAnimator morphAnim, colorAnim;

    PlayButton(Context c, int iconColor, int rippleColor) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        ringWidth = 2f * d;
        iconFrac = 0.44f;
        fill.setColor(Color.rgb(18, 18, 21));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ringWidth);
        ring.setColor(ringColor);
        icPlay = c.getDrawable(R.drawable.ic_play).mutate();
        icPause = c.getDrawable(R.drawable.ic_pause).mutate();
        icPlay.setTint(iconColor);
        icPause.setTint(iconColor);
        setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), null, new ShapeDrawable(new OvalShape())));
        setClickable(true);
        setFocusable(true);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(wSpec, hSpec);
        int s = Math.min(getMeasuredWidth(), getMeasuredHeight());
        setMeasuredDimension(s, s); // always square → always a circle
    }

    void setPlaying(boolean p, boolean animate) {
        if (p == playing && morphAnim == null && (morph == (p ? 1f : 0f))) return;
        playing = p;
        float target = p ? 1f : 0f;
        if (morphAnim != null) morphAnim.cancel();
        if (!animate) {
            morph = target;
            invalidate();
            return;
        }
        morphAnim = ValueAnimator.ofFloat(morph, target);
        morphAnim.setDuration(260);
        morphAnim.setInterpolator(new DecelerateInterpolator());
        morphAnim.addUpdateListener(a -> {
            morph = (float) a.getAnimatedValue();
            invalidate();
        });
        morphAnim.start();
    }

    void animateRing(int target) {
        if (colorAnim != null) colorAnim.cancel();
        colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), ringColor, target);
        colorAnim.setDuration(900);
        colorAnim.addUpdateListener(a -> {
            ringColor = (int) a.getAnimatedValue();
            ring.setColor(ringColor);
            invalidate();
        });
        colorAnim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = s / 2f - ringWidth;
        c.drawCircle(cx, cy, r, fill);
        c.drawCircle(cx, cy, r, ring);
        drawIcon(c, icPlay, 1f - morph, cx, cy, s);
        drawIcon(c, icPause, morph, cx, cy, s);
    }

    private void drawIcon(Canvas c, Drawable d, float t, float cx, float cy, float s) {
        if (t <= 0.01f) return;
        float size = s * iconFrac * (0.75f + 0.25f * t);
        int half = Math.round(size / 2f);
        d.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
        d.setAlpha(Math.round(255 * t));
        d.draw(c);
    }
}
