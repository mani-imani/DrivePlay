package ir.maniimani.geekmusic;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.view.View;

/** Circular-arrow "۱۰" button for jumping 10 s back / forward. */
final class SkipButton extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path head = new Path();
    private final boolean forward;

    SkipButton(Context c, boolean forward, int color, int ripple, Typeface face) {
        super(c);
        this.forward = forward;
        float d = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.8f * d);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(color);
        fill.setColor(color);
        text.setColor(color);
        text.setTypeface(face);
        text.setTextAlign(Paint.Align.CENTER);
        setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), null, new ShapeDrawable(new OvalShape())));
        setClickable(true);
        setContentDescription(forward ? "۱۰ ثانیه جلو" : "۱۰ ثانیه عقب");
    }

    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        int s = Math.min(getMeasuredWidth(), getMeasuredHeight());
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = s * 0.30f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        // arc with a gap at the top where the arrow head sits
        float start = forward ? -60f : -120f;
        float sweep = forward ? 300f : -300f;
        c.drawArc(oval, start, sweep, false, stroke);
        // arrow head at the end of the gap (top)
        double a = Math.toRadians(start);
        float hx = cx + (float) (r * Math.cos(a)), hy = cy + (float) (r * Math.sin(a));
        float hs = s * 0.09f;
        head.reset();
        float dir = forward ? 1f : -1f;
        head.moveTo(hx + dir * hs * 0.9f, hy);
        head.lineTo(hx - dir * hs * 0.5f, hy - hs);
        head.lineTo(hx - dir * hs * 0.5f, hy + hs);
        head.close();
        c.drawPath(head, fill);
        text.setTextSize(s * 0.24f);
        float ty = cy - (text.descent() + text.ascent()) / 2f;
        c.drawText("۱۰", cx, ty, text);
    }
}
