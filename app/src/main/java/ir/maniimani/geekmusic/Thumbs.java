package ir.maniimani.geekmusic;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.util.LruCache;

/** Small generated covers for list rows, cached. */
final class Thumbs {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Thumbs() {
    }

    static Bitmap get(int size, String title, String artist, Typeface face) {
        String key = size + "\u0001" + title + "\u0001" + artist;
        Bitmap b = CACHE.get(key);
        if (b == null) {
            b = Art.render(size, title, artist, face);
            CACHE.put(key, b);
        }
        return b;
    }
}
