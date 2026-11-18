package ir.maniimani.geekmusic;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * GeekMusic — pure-black music player for the car head unit (USB / SD card).
 * One screen: now playing, with the library sliding in over it.
 */
public class MainActivity extends Activity implements PlayerService.Listener, LibraryPanel.Host, Library.Listener {

    // night-first palette (shared with LibraryPanel)
    static final int C_TEXT = Color.rgb(236, 236, 238);
    static final int C_TEXT_2 = Color.rgb(142, 142, 150);
    static final int C_TEXT_3 = Color.rgb(84, 84, 92);
    static final int C_ICON = Color.rgb(205, 205, 212);
    static final int C_SURFACE = Color.rgb(14, 14, 16);
    static final int C_STROKE = Color.rgb(30, 30, 34);
    static final int C_RIPPLE = Color.argb(46, 255, 255, 255);

    private static final int REQ_STORAGE = 7;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PlayerService svc;
    private Library lib;
    private Typeface fRegular, fMedium, fBold, fBlack;

    private FrameLayout root;
    private GlowView glow;
    private LinearLayout content, info, controls, timeRow, topRow;
    private ArtworkView art;
    private TextView chipText, title, artist, tElapsed, tTotal;
    private LinearLayout chip;
    private SeekView seek;
    private SkipButton back10, fwd10;
    private ImageButton btnPrev, btnNext, btnShuffle, btnRepeat, btnLibrary, btnMoon;
    private PlayButton btnPlay;
    private View dimOverlay;
    private LibraryPanel library;
    private Chrome chrome;
    private TextView clock;
    private boolean libraryOpen;

    private Art.Palette palette = Art.NEUTRAL;
    private String shownKey;
    private boolean dim;
    private int lastW = -1, lastH = -1;
    private boolean firstStateApplied;

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

        fRegular = font("vazirmatn_Regular");
        fMedium = font("vazirmatn_Medium");
        fBold = font("vazirmatn_Bold");
        fBlack = font("vazirmatn_Black");
        lib = Library.get(this);

        SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
        dim = prefs.getBoolean("dim", false);

        buildUi();
        setContentView(root);
        setDim(dim, false);
        renderEmpty();

        bindService(new Intent(this, PlayerService.class), conn, BIND_AUTO_CREATE);

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            lib.scanAsync();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addDataScheme("file");
        registerReceiver(mountReceiver, f);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE) lib.scanAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        chrome.start();
        lib.addListener(this);
        main.post(tick);
        if (svc != null) applyAll(false);
        title.setSelected(true);
    }

    @Override
    protected void onPause() {
        lib.removeListener(this);
        main.removeCallbacks(tick);
        chrome.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mountReceiver);
        if (svc != null) svc.removeListener(this);
        unbindService(conn);
        super.onDestroy();
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            svc = ((PlayerService.LocalBinder) b).service();
            svc.addListener(MainActivity.this);
            svc.prepareRestored();
            applyAll(false);
            library.onPlayerChanged();
            if (svc.current() == null) main.postDelayed(() -> {
                if (svc != null && svc.current() == null) openLibrary(false);
            }, 350);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
        }
    };

    private final BroadcastReceiver mountReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            main.removeCallbacks(rescan);
            main.postDelayed(rescan, 1200); // let the volume settle
        }
    };

    private final Runnable rescan = () -> lib.scanAsync();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderTime();
            if (libraryOpen) library.tick();
            main.postDelayed(this, 250);
        }
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) chrome.hide();
    }

    @Override
    public void onBackPressed() {
        if (libraryOpen) {
            if (!library.onBack()) closeLibrary();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // media keys that reach the activity directly (some units send them here)
        if (svc != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    svc.next();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    svc.prev();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    svc.toggle();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    svc.play();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    svc.pause();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    svc.seekBy(10_000);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    svc.seekBy(-10_000);
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ================================================================== player callbacks

    @Override
    public void onPlayerState() {
        applyAll(true);
        library.onPlayerChanged();
    }

    @Override
    public void onTrackChanged() {
        // wait briefly for real artwork so we don't flash the generated cover first
        main.removeCallbacks(artTimeout);
        main.postDelayed(artTimeout, 450);
        applyAll(true);
    }

    @Override
    public void onArtLoaded() {
        main.removeCallbacks(artTimeout);
        applyArt(true);
        library.onPlayerChanged();
    }

    private final Runnable artTimeout = () -> applyArt(true);

    @Override
    public void onLibraryChanged() {
        if (svc != null) applyText(svc.current());
    }

    // ================================================================== Host (library)

    @Override
    public PlayerService service() {
        return svc;
    }

    @Override
    public void onPickTrack(List<Track> context, int index) {
        if (svc == null) return;
        startService(new Intent(this, PlayerService.class));
        svc.playQueue(context, index);
        closeLibrary();
    }

    @Override
    public void closeLibrary() {
        if (!libraryOpen) return;
        libraryOpen = false;
        library.onHidden();
        library.animate().translationX(-root.getWidth()).setDuration(320)
                .setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f))
                .withEndAction(() -> library.setVisibility(View.INVISIBLE)).start();
        title.setSelected(true);
    }

    private void openLibrary(boolean atCurrentFolder) {
        if (libraryOpen) return;
        libraryOpen = true;
        library.setVisibility(View.VISIBLE);
        library.setTranslationX(-root.getWidth());
        library.onShown();
        if (atCurrentFolder && svc != null) library.revealFolderOf(svc.current());
        library.animate().translationX(0).setDuration(340)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
    }

    // ================================================================== state → UI

    private void applyAll(boolean animate) {
        if (svc == null) return;
        Track t = svc.current();
        if (t == null) {
            renderEmpty();
            return;
        }
        setControlsEnabled(true);
        applyText(t);
        if (svc.artReady() || !t.path.equals(shownKey)) {
            if (svc.artReady()) applyArt(animate);
        }
        if (shownKey == null) applyArt(false); // first frame: show something immediately

        boolean playing = svc.isPlaying();
        btnPlay.setPlaying(playing, firstStateApplied);
        float s = playing ? 1f : 0.94f;
        art.animate().scaleX(s).scaleY(s).setDuration(firstStateApplied ? 520 : 0)
                .setInterpolator(playing ? new OvershootInterpolator(1.3f) : new DecelerateInterpolator()).start();
        glow.setActive(playing);
        firstStateApplied = true;

        btnShuffle.setImageTintList(ColorStateList.valueOf(svc.shuffle() ? palette.accent : C_TEXT_3));
        int rep = svc.repeat();
        btnRepeat.setImageResource(rep == PlayerService.REPEAT_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        btnRepeat.setImageTintList(ColorStateList.valueOf(rep == PlayerService.REPEAT_OFF ? C_TEXT_3 : palette.accent));
        btnShuffle.setContentDescription(svc.shuffle() ? "پخش تصادفی: روشن" : "پخش تصادفی: خاموش");
        btnRepeat.setContentDescription(rep == PlayerService.REPEAT_ONE ? "تکرار یک آهنگ"
                : rep == PlayerService.REPEAT_ALL ? "تکرار همه" : "بدون تکرار");
        renderTime();
    }

    private void applyText(Track t) {
        if (t == null) return;
        setTextAnimated(title, t.displayTitle());
        String a = t.displayArtist(), al = t.displayAlbum();
        String line = !Text.blank(a) && !Text.blank(al) && !al.equals(t.displayTitle()) ? a + "  ·  " + al
                : !Text.blank(a) ? a : al;
        setTextAnimated(artist, Text.blank(line) ? "" : line);
        java.io.File folder = new java.io.File(t.folderPath);
        chipText.setText(folder.getName().isEmpty() ? "فلش" : folder.getName());
    }

    private void applyArt(boolean animate) {
        if (svc == null) return;
        Track t = svc.current();
        if (t == null) return;
        String key = t.path;
        art.show(key, svc.art(), t.displayTitle(), t.displayArtist());
        shownKey = key;
        Art.Palette p = svc.palette();
        if (p != palette || !animate) applyPalette(p);
    }

    private void applyPalette(Art.Palette p) {
        palette = p;
        glow.animateTo(p.glow);
        seek.animateColor(p.accent);
        btnPlay.animateRing(p.accent);
        library.setAccent(p.accent);
        if (svc != null) {
            btnShuffle.setImageTintList(ColorStateList.valueOf(svc.shuffle() ? p.accent : C_TEXT_3));
            btnRepeat.setImageTintList(ColorStateList.valueOf(svc.repeat() == PlayerService.REPEAT_OFF ? C_TEXT_3 : p.accent));
        }
        if (dim) btnMoon.setImageTintList(ColorStateList.valueOf(p.accent));
    }

    private void renderEmpty() {
        setControlsEnabled(false);
        art.show("__empty", null, "", "");
        applyPalette(Art.NEUTRAL);
        title.setText("آهنگی انتخاب نشده");
        artist.setText("کتابخونه رو باز کن و یه آهنگ انتخاب کن");
        chipText.setText("کتابخونه");
        tElapsed.setText(Text.time(0));
        tTotal.setText(Text.time(0));
        seek.setProgress(0);
        btnPlay.setPlaying(false, false);
    }

    private void renderTime() {
        if (svc == null || svc.current() == null || seek.isDragging()) return;
        long pos = svc.position(), dur = svc.duration();
        tElapsed.setText(Text.time(pos));
        tTotal.setText(dur > 0 ? Text.time(dur) : "--:--");
        seek.setProgress(dur > 0 ? pos / (float) dur : 0f);
    }

    private void setControlsEnabled(boolean on) {
        for (View v : new View[]{btnPrev, btnPlay, btnNext, btnShuffle, btnRepeat, seek, back10, fwd10}) {
            v.setEnabled(on);
            v.setAlpha(on ? 1f : 0.3f);
        }
    }

    private void setDim(boolean on, boolean animate) {
        dim = on;
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("dim", on).apply();
        float target = on ? 0.5f : 0f;
        if (animate) dimOverlay.animate().alpha(target).setDuration(400).start();
        else dimOverlay.setAlpha(target);
        btnMoon.setImageTintList(ColorStateList.valueOf(on ? palette.accent : C_TEXT_3));
    }

    // ================================================================== UI building

    private void buildUi() {
        root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        chrome = new Chrome(this);
        root.setBackgroundColor(Color.BLACK);

        glow = new GlowView(this);
        root.addView(glow, match());

        content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER);
        root.addView(content, match());

        art = new ArtworkView(this, fBlack);
        art.setOnClickListener(v -> openLibrary(true));
        content.addView(art, new LinearLayout.LayoutParams(dp(300), dp(300)));

        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // top row: library · folder chip · night
        topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        btnLibrary = iconButton(R.drawable.ic_library, 44, 24, C_ICON);
        btnLibrary.setContentDescription("کتابخونه");
        btnLibrary.setOnClickListener(v -> openLibrary(true));
        topRow.addView(btnLibrary);
        View sp1 = new View(this);
        topRow.addView(sp1, new LinearLayout.LayoutParams(0, 1, 1f));
        chip = buildChip();
        chip.setOnClickListener(v -> openLibrary(true));
        topRow.addView(chip);
        View sp2 = new View(this);
        topRow.addView(sp2, new LinearLayout.LayoutParams(0, 1, 1f));
        btnMoon = iconButton(R.drawable.ic_moon, 44, 22, C_TEXT_3);
        btnMoon.setContentDescription("حالت شب");
        btnMoon.setOnClickListener(v -> setDim(!dim, true));
        topRow.addView(btnMoon);
        info.addView(topRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title = text(fBlack, 30, C_TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setHorizontalFadingEdgeEnabled(true);
        title.setFadingEdgeLength(dp(24));
        title.setSelected(true);
        info.addView(title, fullWidth(dp(14)));

        artist = text(fMedium, 17, C_TEXT_2);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(artist, fullWidth(dp(4)));

        seek = new SeekView(this);
        seek.setListener(new SeekView.Listener() {
            @Override
            public void onScrub(float f) {
                if (svc != null) tElapsed.setText(Text.time((long) (f * svc.duration())));
            }

            @Override
            public void onSeek(float f) {
                if (svc != null) svc.seekTo((long) (f * svc.duration()));
            }
        });
        info.addView(seek, fullWidth(dp(18), dp(30)));

        timeRow = new LinearLayout(this);
        timeRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        tElapsed = text(fMedium, 13, C_TEXT_3);
        tTotal = text(fMedium, 13, C_TEXT_3);
        back10 = new SkipButton(this, false, C_TEXT_2, C_RIPPLE, fBold);
        fwd10 = new SkipButton(this, true, C_TEXT_2, C_RIPPLE, fBold);
        back10.setOnClickListener(v -> {
            if (svc != null) svc.seekBy(-10_000);
            pulse(back10);
        });
        fwd10.setOnClickListener(v -> {
            if (svc != null) svc.seekBy(10_000);
            pulse(fwd10);
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(9);
        timeRow.addView(tElapsed, tl);
        timeRow.addView(back10, pill(0));
        timeRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        timeRow.addView(fwd10, pill(0));
        LinearLayout.LayoutParams tr = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tr.rightMargin = dp(9);
        timeRow.addView(tTotal, tr);
        info.addView(timeRow, fullWidth(0));

        controls = new LinearLayout(this);
        controls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        controls.setGravity(Gravity.CENTER);
        btnShuffle = iconButton(R.drawable.ic_shuffle, 46, 22, C_TEXT_3);
        btnPrev = iconButton(R.drawable.ic_prev, 64, 32, C_ICON);
        btnPlay = new PlayButton(this, C_TEXT, C_RIPPLE);
        btnNext = iconButton(R.drawable.ic_next, 64, 32, C_ICON);
        btnRepeat = iconButton(R.drawable.ic_repeat, 46, 22, C_TEXT_3);
        btnPrev.setContentDescription("قبلی");
        btnNext.setContentDescription("بعدی");
        btnShuffle.setOnClickListener(v -> {
            if (svc != null) svc.setShuffle(!svc.shuffle());
            pulse(btnShuffle);
        });
        btnRepeat.setOnClickListener(v -> {
            if (svc != null) svc.cycleRepeat();
            pulse(btnRepeat);
        });
        btnPrev.setOnClickListener(v -> {
            if (svc != null) svc.prev();
            pulse(btnPrev);
        });
        btnNext.setOnClickListener(v -> {
            if (svc != null) svc.next();
            pulse(btnNext);
        });
        btnPlay.setOnClickListener(v -> {
            if (svc == null) return;
            startService(new Intent(this, PlayerService.class));
            svc.toggle();
            pulse(btnPlay);
        });
        controls.addView(btnShuffle, sq(46, 0));
        controls.addView(btnPrev, sq(64, 14));
        controls.addView(btnPlay, sq(84, 18));
        controls.addView(btnNext, sq(64, 18));
        controls.addView(btnRepeat, sq(46, 14));
        info.addView(controls, wrapCentered(dp(6)));

        clock = chrome.createClock(fBold, 20, C_TEXT_2);
        root.addView(clock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // library slides in over everything
        library = new LibraryPanel(this, this, fRegular, fMedium, fBold);
        library.setVisibility(View.INVISIBLE);
        root.addView(library, match());

        dimOverlay = new View(this);
        dimOverlay.setBackgroundColor(Color.BLACK);
        dimOverlay.setAlpha(0f);
        dimOverlay.setClickable(false);
        dimOverlay.setFocusable(false);
        root.addView(dimOverlay, match());

        // tap along the very top edge to bring the head unit's bar back for a moment
        chrome.addTopStrip(root, dp(12));

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t;
            if (w != lastW || h != lastH) {
                lastW = w;
                lastH = h;
                v.post(() -> relayout(w, h));
            }
        });
    }

    /** Landscape: art left, info right. Portrait: stacked. Everything scales to fit. */
    private void relayout(int w, int h) {
        boolean landscape = w >= h;
        int pad = landscape ? dp(28) : dp(22);
        content.setPadding(pad, pad, pad, pad);
        content.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        // Landscape: controls on the left (driver's side in a left-hand-drive car), cover on the right.
        // Portrait: cover on top.
        content.removeAllViews();
        if (landscape) {
            content.addView(info);
            content.addView(art);
        } else {
            content.addView(art);
            content.addView(info);
        }

        int clockSpace = dp(44); // room for the clock above the cover
        int artSize = landscape ? (int) Math.min(h - 2f * pad - clockSpace, w * 0.42f)
                : (int) Math.min(w - 2f * pad, h * 0.38f - clockSpace);
        artSize = Math.max(artSize, dp(120));
        art.setLayoutParams(new LinearLayout.LayoutParams(artSize, artSize));

        LinearLayout.LayoutParams ilp;
        if (landscape) {
            ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ilp.rightMargin = dp(32);
        } else {
            ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.topMargin = dp(20);
        }
        info.setLayoutParams(ilp);

        applyScale(1f);
        int infoW = landscape ? (w - 2 * pad - artSize - ilp.rightMargin) : (w - 2 * pad);
        int availH = landscape ? (h - 2 * pad) : (h - 2 * pad - artSize - ilp.topMargin);
        info.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, infoW), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        // the controls row is the widest thing: also fit width
        controls.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        float k = 1f;
        int needH = info.getMeasuredHeight(), needW = controls.getMeasuredWidth();
        if (needH > availH && needH > 0) k = Math.min(k, availH / (float) needH * 0.97f);
        if (needW > infoW && needW > 0) k = Math.min(k, infoW / (float) needW * 0.98f);
        if (k < 1f) applyScale(Math.max(0.55f, k));

        content.post(() -> {
            float cx = (art.getLeft() + art.getWidth() / 2f) / (float) Math.max(1, w);
            float cy = (art.getTop() + art.getHeight() / 2f) / (float) Math.max(1, h);
            glow.setCenter(cx, cy);
            chrome.placeClockAbove(art, dp(4));
        });
        if (!libraryOpen) library.setTranslationX(-w);
    }

    private void applyScale(float k) {
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30 * k);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17 * k);
        chipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * k);
        tElapsed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * k);
        tTotal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * k);

        topMargin(title, dp(14 * k));
        topMargin(artist, dp(4 * k));
        topMargin(seek, dp(18 * k));
        size(seek, -1, dp(30 * k));
        topMargin(controls, dp(6 * k));

        sizeIcon(btnLibrary, 44 * k, 24 * k, 0);
        sizeIcon(btnMoon, 44 * k, 22 * k, 0);
        size(back10, dp(64 * k), dp(30 * k));
        size(fwd10, dp(64 * k), dp(30 * k));
        ((LinearLayout.LayoutParams) back10.getLayoutParams()).leftMargin = dp(12 * k);
        ((LinearLayout.LayoutParams) fwd10.getLayoutParams()).rightMargin = dp(12 * k);
        topMargin(timeRow, dp(4 * k));
        sizeIcon(btnShuffle, 46 * k, 22 * k, 0);
        sizeIcon(btnPrev, 64 * k, 32 * k, 14 * k);
        LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) btnPlay.getLayoutParams();
        plp.width = plp.height = dp(84 * k);
        plp.leftMargin = dp(18 * k);
        btnPlay.setLayoutParams(plp);
        sizeIcon(btnNext, 64 * k, 32 * k, 18 * k);
        sizeIcon(btnRepeat, 46 * k, 22 * k, 14 * k);
        info.requestLayout();
    }

    private LinearLayout buildChip() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        c.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_SURFACE);
        bg.setStroke(dp(1), C_STROKE);
        bg.setCornerRadius(dp(100));
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(C_RIPPLE), bg, null));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_folder);
        icon.setImageTintList(ColorStateList.valueOf(C_TEXT_3));
        c.addView(icon, new LinearLayout.LayoutParams(dp(15), dp(15)));
        chipText = text(fMedium, 13, C_TEXT_2);
        chipText.setSingleLine(true);
        chipText.setEllipsize(TextUtils.TruncateAt.END);
        chipText.setMaxWidth(dp(220));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(7));
        c.addView(chipText, lp);
        return c;
    }

    // ================================================================== helpers

    private ImageButton iconButton(int icon, float sizeDp, float iconDp, int tint) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(tint));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp((sizeDp - iconDp) / 2f);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(C_RIPPLE), null, new ShapeDrawable(new OvalShape())));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private void sizeIcon(ImageButton b, float sizeDp, float iconDp, float leftDp) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.width = lp.height = dp(sizeDp);
        lp.leftMargin = dp(leftDp);
        b.setLayoutParams(lp);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        int pad = (dp(sizeDp) - dp(iconDp)) / 2;
        b.setPadding(pad, pad, pad, pad);
    }

    private void size(View v, int w, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (w != -1) lp.width = w;
        lp.height = h;
        v.setLayoutParams(lp);
    }

    private void topMargin(View v, int px) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.topMargin = px;
        v.setLayoutParams(lp);
    }

    private void pulse(View v) {
        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(2.2f)).start()
        ).start();
    }

    private void setTextAnimated(final TextView tv, final CharSequence text) {
        if (TextUtils.equals(tv.getText(), text)) return;
        if (tv.getText().length() == 0 || !firstStateApplied) {
            tv.setText(text);
            return;
        }
        tv.animate().cancel();
        tv.animate().alpha(0f).translationY(dp(6)).setDuration(140).withEndAction(() -> {
            tv.setText(text);
            tv.setTranslationY(-dp(6));
            tv.animate().alpha(1f).translationY(0).setDuration(300).setInterpolator(new DecelerateInterpolator()).start();
            if (tv == title) tv.setSelected(true);
        }).start();
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        return t;
    }

    private Typeface font(String name) {
        try {
            return Typeface.createFromAsset(getAssets(), "fonts/" + name + ".ttf");
        } catch (Throwable t) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams fullWidth(int top) {
        return fullWidth(top, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fullWidth(int top, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.topMargin = top;
        return lp;
    }

    private LinearLayout.LayoutParams wrapCentered(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = top;
        return lp;
    }

    private LinearLayout.LayoutParams pill(int unused) {
        return new LinearLayout.LayoutParams(dp(64), dp(30));
    }

    private LinearLayout.LayoutParams sq(int sizeDp, int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.leftMargin = dp(leftMarginDp);
        return lp;
    }
}
