package ir.maniimani.geekmusic;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

/**
 * Pill-shaped "jump 10 seconds" button: a double chevron plus "۱۰".
 *   back:    [ ◀◀ ۱۰ ]      forward: [ ۱۰ ▶▶ ]
 * The chevrons always sit on the outer side, pointing the way time moves.
 */
final class SkipButton extends View {

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevrons = new Path();
    private final boolean forward;
    private final float d;

    SkipButton(Context c, boolean forward, int color, int ripple, Typeface face) {
        super(c);
        this.forward = forward;
        d = c.getResources().getDisplayMetrics().density;
        ink.setColor(color);
        ink.setStyle(Paint.Style.FILL);
        ink.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(color);
        text.setTypeface(face);
        text.setTextAlign(Paint.Align.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MainActivity.C_SURFACE);
        bg.setStroke(Math.max(1, Math.round(d)), MainActivity.C_STROKE);
        bg.setCornerRadius(100 * d);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(100 * d);
        setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), bg, mask));
        setClickable(true);
        setContentDescription(forward ? "۱۰ ثانیه جلو" : "۱۰ ثانیه عقب");
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cy = h / 2f;
        float th = h * 0.30f;                 // chevron height
        float tw = th * 0.78f;                // single chevron width
        float gap = th * 0.08f;
        float group = tw * 2 - gap;           // two overlapping chevrons
        text.setTextSize(h * 0.42f);
        float textW = text.measureText("۱۰");
        float space = h * 0.16f;
        float total = group + space + textW;
        float x0 = (w - total) / 2f;

        float chevX, textX;
        if (forward) {
            textX = x0 + textW / 2f;
            chevX = x0 + textW + space;
        } else {
            chevX = x0;
            textX = x0 + group + space + textW / 2f;
        }

        chevrons.reset();
        for (int i = 0; i < 2; i++) {
            float left = chevX + i * (tw - gap);
            if (forward) {
                chevrons.moveTo(left, cy - th / 2f);
                chevrons.lineTo(left + tw, cy);
                chevrons.lineTo(left, cy + th / 2f);
            } else {
                chevrons.moveTo(left + tw, cy - th / 2f);
                chevrons.lineTo(left, cy);
                chevrons.lineTo(left + tw, cy + th / 2f);
            }
            chevrons.close();
        }
        c.drawPath(chevrons, ink);
        float ty = cy - (text.descent() + text.ascent()) / 2f;
        c.drawText("۱۰", textX, ty, text);
    }
}
