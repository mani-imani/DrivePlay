package ir.maniimani.geekmusic;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Random;

/**
 * Generated cover art. Every song gets its own deterministic artwork built from its
 * title + artist: a deep two-tone gradient, soft light blooms, faint vinyl grooves
 * and the first letter of the title as a large watermark. Tuned to stay dark so it
 * never glares at night.
 */
final class Art {

    private Art() {
    }

    /** Accent + glow colours derived from a song. */
    static final class Palette {
        final int accent;   // UI accent (progress, play ring)
        final int glow;     // background bloom
        final float hue;

        Palette(float hue) {
            this.hue = hue;
            this.accent = Color.HSVToColor(new float[]{hue, 0.42f, 0.92f});
            this.glow = Color.HSVToColor(new float[]{hue, 0.70f, 0.55f});
        }
    }

    static final Palette NEUTRAL = new Palette(250f);

    static long seedOf(String title, String artist) {
        String s = (title == null ? "" : title) + "\u0001" + (artist == null ? "" : artist);
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }

    static Palette paletteFor(String title, String artist) {
        if (isBlank(title) && isBlank(artist)) return NEUTRAL;
        Random r = new Random(seedOf(title, artist));
        return new Palette(r.nextInt(360));
    }

    /**
     * Accent from a real cover: the most "colourful" hue (weighted by saturation × value),
     * falling back to the generated palette when the cover is grey or black-and-white.
     */
    static Palette paletteFromBitmap(Bitmap src, String title, String artist) {
        try {
            Bitmap b = Bitmap.createScaledBitmap(src, 32, 32, true);
            float[] bins = new float[36];
            float[] hsv = new float[3];
            float total = 0;
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    Color.colorToHSV(b.getPixel(x, y), hsv);
                    float w = hsv[1] * hsv[2];
                    if (hsv[1] < 0.18f || hsv[2] < 0.15f) continue;
                    bins[(int) (hsv[0] / 10f) % 36] += w;
                    total += w;
                }
            }
            if (b != src) b.recycle();
            if (total < 12f) return paletteFor(title, artist);
            int best = 0;
            for (int i = 1; i < 36; i++) {
                float a = bins[i] + 0.5f * (bins[(i + 35) % 36] + bins[(i + 1) % 36]);
                float c = bins[best] + 0.5f * (bins[(best + 35) % 36] + bins[(best + 1) % 36]);
                if (a > c) best = i;
            }
            return new Palette(best * 10f + 5f);
        } catch (Throwable t) {
            return paletteFor(title, artist);
        }
    }

    static Bitmap render(int size, String title, String artist, Typeface glyphFace) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        boolean empty = isBlank(title) && isBlank(artist);
        Random r = new Random(seedOf(title, artist));
        boolean small = size < 160; // list thumbnails: skip the fine grooves
        float h1 = empty ? 250 : r.nextInt(360);
        float h2 = (h1 + 35 + r.nextInt(70)) % 360;
        float sat = empty ? 0.18f : 0.62f;

        // 1. base gradient (deep)
        int c1 = Color.HSVToColor(new float[]{h1, sat, 0.30f});
        int c2 = Color.HSVToColor(new float[]{h2, sat, 0.10f});
        float angle = r.nextFloat() * (float) Math.PI * 2;
        float cx = size / 2f, cy = size / 2f;
        float dx = (float) Math.cos(angle) * size * 0.7f, dy = (float) Math.sin(angle) * size * 0.7f;
        p.setShader(new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, c1, c2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);

        // 2. soft blooms
        int blooms = 3 + r.nextInt(2);
        for (int i = 0; i < blooms; i++) {
            float bx = size * (0.1f + r.nextFloat() * 0.8f);
            float by = size * (0.1f + r.nextFloat() * 0.8f);
            float br = size * (0.35f + r.nextFloat() * 0.45f);
            float bh = (i % 2 == 0) ? h1 : h2;
            int core = Color.HSVToColor(empty ? 40 : 95, new float[]{bh, empty ? 0.2f : 0.75f, 0.85f});
            p.setShader(new RadialGradient(bx, by, br, core, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(bx, by, br, p);
        }
        p.setShader(null);

        // 3. vinyl grooves, off-centre
        float gx = size * (0.62f + r.nextFloat() * 0.25f);
        float gy = size * (0.62f + r.nextFloat() * 0.25f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, size / 420f));
        for (float rad = size * 0.12f; rad < size * 0.95f; rad += size * 0.028f) {
            int a = 10 + r.nextInt(8);
            if (small) continue;
            p.setColor(Color.argb(a, 255, 255, 255));
            c.drawCircle(gx, gy, rad, p);
        }
        p.setStyle(Paint.Style.FILL);

        // 4. glyph watermark
        String glyph = empty ? "♪" : firstGlyph(title, artist);
        if (!glyph.isEmpty()) {
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(glyphFace);
            tp.setColor(Color.argb(empty ? 40 : 34, 255, 255, 255));
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setTextSize(size * 0.62f);
            // Centre the glyph's actual ink, not its advance box: Persian letters
            // (و ر د س ...) sit off-centre in their box, so align on the measured bounds.
            Rect bounds = new Rect();
            tp.getTextBounds(glyph, 0, glyph.length(), bounds);
            float tx = size / 2f - bounds.exactCenterX();
            float ty = size / 2f - bounds.exactCenterY();
            c.drawText(glyph, tx, ty, tp);
        }

        // 5. top sheen + bottom vignette for depth
        p.setShader(new LinearGradient(0, 0, 0, size,
                new int[]{Color.argb(28, 255, 255, 255), Color.TRANSPARENT, Color.argb(110, 0, 0, 0)},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
        p.setShader(null);

        return bmp;
    }

    private static String firstGlyph(String title, String artist) {
        String s = !isBlank(title) ? title : artist;
        if (s == null) return "";
        s = s.trim();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(Character.toUpperCase(cp)));
            i += Character.charCount(cp);
        }
        return "";
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
