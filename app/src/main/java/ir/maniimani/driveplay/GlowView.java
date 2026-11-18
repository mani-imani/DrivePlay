package ir.maniimani.driveplay;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Pure black background with a faint coloured bloom behind the artwork. */
final class GlowView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private int color = Color.TRANSPARENT;
    private float cxFrac = 0.27f, cyFrac = 0.5f;
    private float intensity = 1f;
    private ValueAnimator colorAnim, intensityAnim;

    GlowView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
    }

    void setCenter(float xFrac, float yFrac) {
        cxFrac = xFrac;
        cyFrac = yFrac;
        invalidate();
    }

    void animateTo(int target) {
        if (colorAnim != null) colorAnim.cancel();
        colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), color, target);
        colorAnim.setDuration(900);
        colorAnim.addUpdateListener(a -> {
            color = (int) a.getAnimatedValue();
            invalidate();
        });
        colorAnim.start();
    }

    /** Dimmer bloom while paused. */
    void setActive(boolean active) {
        float target = active ? 1f : 0.45f;
        if (intensityAnim != null) intensityAnim.cancel();
        intensityAnim = ValueAnimator.ofFloat(intensity, target);
        intensityAnim.setDuration(700);
        intensityAnim.addUpdateListener(a -> {
            intensity = (float) a.getAnimatedValue();
            invalidate();
        });
        intensityAnim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || Color.alpha(color) == 0) return;
        float cx = w * cxFrac, cy = h * cyFrac;
        float r = Math.max(w, h) * 0.55f;
        int a = (int) (64 * intensity);
        int core = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
        int mid = Color.argb(a / 3, Color.red(color), Color.green(color), Color.blue(color));
        paint.setShader(new RadialGradient(cx, cy, r, new int[]{core, mid, Color.TRANSPARENT},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
    }
}
