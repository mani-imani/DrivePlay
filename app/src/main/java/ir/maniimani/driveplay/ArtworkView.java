package ir.maniimani.driveplay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Square rounded cover: real artwork when the file has it, generated art otherwise. Cross-fades. */
final class ArtworkView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final Rect srcRect = new Rect(), prevSrc = new Rect();
    private final Typeface glyphFace;
    private final float radius;

    private Bitmap current, previous;
    private float fade = 1f;
    private String key = null;
    private Bitmap real;
    private String title = "", artist = "";
    private ValueAnimator anim;

    ArtworkView(Context c, Typeface glyphFace) {
        super(c);
        this.glyphFace = glyphFace;
        this.radius = 22 * c.getResources().getDisplayMetrics().density;
    }

    /** {@code key} identifies the content (e.g. file path); same key + same art = no-op. */
    void show(String key, Bitmap realArt, String title, String artist) {
        boolean same = key != null && key.equals(this.key) && realArt == this.real;
        if (same && current != null) return;
        this.key = key;
        this.real = realArt;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        rebuild(true);
    }

    private void rebuild(boolean animate) {
        int size = (int) Math.min(rect.width(), rect.height());
        if (size <= 0) return;
        Bitmap next = real != null ? real : Art.render(size, title, artist, glyphFace);
        if (animate && current != null) {
            previous = current;
            prevSrc.set(srcRect);
        } else {
            previous = null;
        }
        current = next;
        centreCrop(current, srcRect);
        if (anim != null) anim.cancel();
        if (previous == null) {
            fade = 1f;
            invalidate();
            return;
        }
        fade = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(480);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            if (fade >= 1f) previous = null;
            invalidate();
        });
        anim.start();
    }

    private static void centreCrop(Bitmap b, Rect out) {
        int w = b.getWidth(), h = b.getHeight(), s = Math.min(w, h);
        out.set((w - s) / 2, (h - s) / 2, (w - s) / 2 + s, (h - s) / 2 + s);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(wSpec, hSpec);
        int s = Math.min(getMeasuredWidth(), getMeasuredHeight());
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int size = Math.min(w, h);
        float left = (w - size) / 2f, top = (h - size) / 2f;
        rect.set(left, top, left + size, top + size);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        rebuild(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (current == null) return;
        canvas.save();
        canvas.clipPath(clip);
        if (previous != null && fade < 1f) {
            paint.setAlpha(255);
            canvas.drawBitmap(previous, prevSrc, rect, paint);
        }
        paint.setAlpha((int) (255 * fade));
        canvas.drawBitmap(current, srcRect, rect, paint);
        canvas.restore();
    }
}
