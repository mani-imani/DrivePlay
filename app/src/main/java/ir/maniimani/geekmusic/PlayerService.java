package ir.maniimani.geekmusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Playback engine: MediaPlayer + queue (shuffle/repeat) + audio focus + MediaSession
 * (steering-wheel keys) + foreground notification + resume-where-you-left-off.
 */
public class PlayerService extends Service {

    interface Listener {
        /** Playing/paused, shuffle/repeat or queue changed. */
        void onPlayerState();

        /** A different track is now current (artwork may still be loading). */
        void onTrackChanged();

        /** Artwork for the current track finished loading. */
        void onArtLoaded();
    }

    static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    static final String ACTION_RESUME_IF_WAS_PLAYING = "ir.maniimani.geekmusic.RESUME";
    private static final String ACTION_TOGGLE = "ir.maniimani.geekmusic.TOGGLE";
    private static final String ACTION_NEXT = "ir.maniimani.geekmusic.NEXT";
    private static final String ACTION_PREV = "ir.maniimani.geekmusic.PREV";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;

    final class LocalBinder extends Binder {
        PlayerService service() {
            return PlayerService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private HandlerThread ioThread;
    private Handler io;

    private MediaPlayer mp;
    private boolean prepared;
    private int pendingSeek = -1;
    private boolean playWhenReady;

    private final List<Track> queue = new ArrayList<>();
    private int[] order = new int[0];
    private int pos = -1;              // index into order
    private boolean shuffle;
    private int repeat = REPEAT_ALL;
    private int errorStreak;

    private Bitmap art;
    private String artFor;
    private Art.Palette palette = Art.NEUTRAL;

    private AudioManager am;
    private AudioFocusRequest focusReq;
    private boolean hasFocus, resumeOnFocus, ducked;

    private MediaSession session;
    private final CarSource car = new CarSource();
    private SharedPreferences prefs;
    private boolean foreground;

    // ================================================================== lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        ioThread = new HandlerThread("player-io");
        ioThread.start();
        io = new Handler(ioThread.getLooper());
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getInt("repeat", REPEAT_ALL);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(this::onFocusChange, main)
                .build();
        setupSession();
        createChannel();
        restoreState();
        main.postDelayed(saver, 5000);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(a)) toggle();
        else if (ACTION_NEXT.equals(a)) next();
        else if (ACTION_PREV.equals(a)) prev();
        else if (ACTION_RESUME_IF_WAS_PLAYING.equals(a)) {
            // started from a broadcast with startForegroundService(): must go foreground right away
            startForeground(NOTIF_ID, placeholderNotification());
            foreground = true;
            Library lib = Library.get(this);
            if (!lib.scannedOnce() && !lib.isScanning()) lib.scanAsync();
            resumeIfWasPlaying();
            main.postDelayed(() -> {
                if (!isPlaying() && foreground) {
                    stopForeground(true);
                    foreground = false;
                }
            }, 4000);
        }
        if (!isPlaying() && !foreground) stopSelfSoon();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        saveState();
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonFocus();
        session.release();
        ioThread.quitSafely();
        super.onDestroy();
    }

    private void stopSelfSoon() {
        main.postDelayed(() -> {
            if (!isPlaying() && listeners.isEmpty()) stopSelf();
        }, 60_000);
    }

    // ================================================================== public API

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    Track current() {
        return pos >= 0 && pos < order.length ? queue.get(order[pos]) : null;
    }

    boolean isPlaying() {
        return mp != null && prepared && mp.isPlaying() || (playWhenReady && !prepared && current() != null);
    }

    long position() {
        if (mp != null && prepared) {
            try {
                return mp.getCurrentPosition();
            } catch (Throwable ignored) {
            }
        }
        return Math.max(pendingSeek, 0);
    }

    long duration() {
        if (mp != null && prepared) {
            try {
                int d = mp.getDuration();
                if (d > 0) return d;
            } catch (Throwable ignored) {
            }
        }
        Track t = current();
        return t == null ? 0 : t.duration;
    }

    boolean shuffle() {
        return shuffle;
    }

    int repeat() {
        return repeat;
    }

    Bitmap art() {
        Track t = current();
        return t != null && t.path.equals(artFor) ? art : null;
    }

    boolean artReady() {
        Track t = current();
        return t != null && t.path.equals(artFor);
    }

    Art.Palette palette() {
        return palette;
    }

    int queueSize() {
        return queue.size();
    }

    /** 1-based position in the queue as the user sees it (play order). */
    int queuePosition() {
        return pos + 1;
    }

    /** Replace the queue and start playing at {@code index}. */
    void playQueue(List<Track> tracks, int index) {
        if (tracks == null || tracks.isEmpty()) return;
        queue.clear();
        queue.addAll(tracks);
        index = Math.max(0, Math.min(index, tracks.size() - 1));
        buildOrder(index);
        pos = 0;
        if (!shuffle) pos = index;
        errorStreak = 0;
        load(true, 0);
    }

    void toggle() {
        if (isPlaying()) pause();
        else play();
    }

    void play() {
        if (current() == null) return;
        if (mp == null || (!prepared && !playWhenReady)) {
            load(true, (int) Math.max(0, position()));
            return;
        }
        if (!requestFocus()) return;
        playWhenReady = true;
        if (prepared) {
            mp.start();
            setVolume(ducked ? 0.25f : 1f);
        }
        takeOverCarSource();
        changed(false);
    }

    void pause() {
        playWhenReady = false;
        if (mp != null && prepared && mp.isPlaying()) mp.pause();
        resumeOnFocus = false;
        changed(false);
        saveState();
    }

    void next() {
        if (order.length == 0) return;
        if (pos + 1 < order.length) {
            pos++;
        } else {
            if (shuffle) reshuffleKeepingCurrent(-1);
            pos = 0;
        }
        load(true, 0);
    }

    void prev() {
        if (order.length == 0) return;
        if (position() > 3000) {
            seekTo(0);
            return;
        }
        pos = pos > 0 ? pos - 1 : order.length - 1;
        load(true, 0);
    }

    void seekTo(long ms) {
        long d = duration();
        if (d > 0) ms = Math.min(ms, d - 500);
        ms = Math.max(0, ms);
        if (mp != null && prepared) {
            mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        } else {
            pendingSeek = (int) ms;
        }
        updateSession();
        notifyState();
    }

    void seekBy(long delta) {
        seekTo(position() + delta);
    }

    void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        prefs.edit().putBoolean("shuffle", on).apply();
        Track cur = current();
        int curIndex = cur == null ? 0 : order[pos];
        buildOrder(curIndex);
        pos = shuffle ? 0 : curIndex;
        changed(false);
        saveState();
    }

    void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        prefs.edit().putInt("repeat", repeat).apply();
        changed(false);
    }

    // ================================================================== player internals

    private void load(boolean autoplay, int seekMs) {
        Track t = current();
        if (t == null) return;
        releasePlayer();
        prepared = false;
        playWhenReady = autoplay;
        pendingSeek = seekMs > 0 ? seekMs : -1;
        art = null;
        artFor = null;
        palette = Art.paletteFor(t.displayTitle(), t.displayArtist());
        loadArtAsync(t);

        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        final MediaPlayer player = mp;
        mp.setOnPreparedListener(p -> {
            if (p != mp) return;
            prepared = true;
            errorStreak = 0;
            if (pendingSeek > 0) p.seekTo(pendingSeek, MediaPlayer.SEEK_CLOSEST);
            pendingSeek = -1;
            if (t.duration <= 0) t.duration = p.getDuration();
            if (playWhenReady && requestFocus()) {
                p.start();
                setVolume(ducked ? 0.25f : 1f);
                takeOverCarSource();
            } else {
                playWhenReady = false;
            }
            changed(false);
        });
        mp.setOnCompletionListener(p -> {
            if (p != mp) return;
            onCompletion();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (p != mp) return true;
            onPlaybackError();
            return true;
        });
        try {
            mp.setDataSource(t.path);
            mp.prepareAsync();
        } catch (Throwable e) {
            main.post(this::onPlaybackError);
        }
        changed(true);
        saveState();
    }

    private void onCompletion() {
        if (repeat == REPEAT_ONE) {
            mp.seekTo(0);
            mp.start();
            return;
        }
        if (pos + 1 >= order.length && repeat == REPEAT_OFF) {
            // end of the list: stop on the first track, paused
            pos = 0;
            load(false, 0);
            return;
        }
        next();
    }

    private void onPlaybackError() {
        releasePlayer();
        prepared = false;
        errorStreak++;
        if (errorStreak >= Math.max(1, order.length) || errorStreak > 20) {
            playWhenReady = false;
            changed(false);
            return;
        }
        // skip unreadable files (e.g. a format the unit can't decode)
        main.postDelayed(() -> {
            if (pos + 1 < order.length) pos++;
            else pos = 0;
            load(true, 0);
        }, 300);
    }

    private void releasePlayer() {
        if (mp != null) {
            try {
                mp.reset();
                mp.release();
            } catch (Throwable ignored) {
            }
            mp = null;
        }
        prepared = false;
    }

    private void buildOrder(int keepFirst) {
        int n = queue.size();
        order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        if (shuffle && n > 1) {
            List<Integer> rest = new ArrayList<>();
            for (int i = 0; i < n; i++) if (i != keepFirst) rest.add(i);
            Collections.shuffle(rest, new Random());
            order[0] = keepFirst;
            for (int i = 0; i < rest.size(); i++) order[i + 1] = rest.get(i);
        }
    }

    private void reshuffleKeepingCurrent(int unused) {
        int n = order.length;
        if (n < 2) return;
        int last = order[pos];
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < n; i++) all.add(i);
        Collections.shuffle(all, new Random());
        // avoid repeating the song that just ended as the first of the new round
        if (all.get(0) == last) Collections.swap(all, 0, n - 1);
        for (int i = 0; i < n; i++) order[i] = all.get(i);
    }

    private void setVolume(float v) {
        try {
            if (mp != null) mp.setVolume(v, v);
        } catch (Throwable ignored) {
        }
    }

    private void takeOverCarSource() {
        io.post(car::takeOver);
    }

    // ================================================================== artwork

    private void loadArtAsync(final Track t) {
        io.post(() -> {
            Library.readTags(t);
            Bitmap b = embeddedArt(t.path);
            if (b == null) b = folderArt(t.folderPath);
            final Bitmap result = b;
            final Art.Palette p = b != null ? Art.paletteFromBitmap(b, t.displayTitle(), t.displayArtist())
                    : Art.paletteFor(t.displayTitle(), t.displayArtist());
            main.post(() -> {
                Track cur = current();
                if (cur == null || !cur.path.equals(t.path)) return;
                art = result;
                artFor = t.path;
                palette = p;
                updateSession();
                updateNotification();
                for (Listener l : listeners) l.onArtLoaded();
            });
        });
    }

    private static Bitmap embeddedArt(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            byte[] data = r.getEmbeddedPicture();
            return data == null ? null : decodeSampled(data, null);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Bitmap folderArt(String folder) {
        File[] files = new File(folder).listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (!(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) continue;
            if (n.startsWith("cover") || n.startsWith("folder") || n.startsWith("front") || n.startsWith("albumart")) {
                best = f;
                break;
            }
        }
        return best == null ? null : decodeSampled(null, best.getAbsolutePath());
    }

    private static Bitmap decodeSampled(byte[] data, String file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        if (data != null) BitmapFactory.decodeByteArray(data, 0, data.length, o);
        else BitmapFactory.decodeFile(file, o);
        int size = Math.max(o.outWidth, o.outHeight);
        if (size <= 0) return null;
        int sample = 1;
        while (size / (sample * 2) >= 720) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return data != null ? BitmapFactory.decodeByteArray(data, 0, data.length, o) : BitmapFactory.decodeFile(file, o);
    }

    // ================================================================== audio focus

    private boolean requestFocus() {
        if (hasFocus) return true;
        hasFocus = am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasFocus;
    }

    private void abandonFocus() {
        if (hasFocus) am.abandonAudioFocusRequest(focusReq);
        hasFocus = false;
    }

    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                if (ducked) {
                    ducked = false;
                    setVolume(1f);
                }
                if (resumeOnFocus) {
                    resumeOnFocus = false;
                    play();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                ducked = true;
                setVolume(0.25f);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // e.g. a phone call or navigation prompt: pause, then continue
                if (isPlaying()) {
                    pause();
                    resumeOnFocus = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                hasFocus = false;
                if (isPlaying()) pause();
                resumeOnFocus = false;
                break;
        }
    }

    // ================================================================== media session

    private void setupSession() {
        session = new MediaSession(this, "GeekMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                prev();
            }

            @Override
            public void onSeekTo(long p) {
                seekTo(p);
            }

            @Override
            public void onStop() {
                pause();
            }

            @Override
            public void onFastForward() {
                seekBy(10_000);
            }

            @Override
            public void onRewind() {
                seekBy(-10_000);
            }
        }, main);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT));
        session.setActive(true);
    }

    private void updateSession() {
        Track t = current();
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND
                | PlaybackState.ACTION_STOP;
        int state = t == null ? PlaybackState.STATE_NONE
                : isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position(), isPlaying() ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
        if (t != null) {
            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.displayTitle())
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.displayArtist())
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t.displayAlbum())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
            Bitmap a = art();
            if (a != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, a);
            session.setMetadata(m.build());
        }
        if (!session.isActive()) session.setActive(true);
    }

    // ================================================================== notification / foreground

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "پخش موسیقی", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    private PendingIntent action(String a, int code) {
        return PendingIntent.getService(this, code, new Intent(this, PlayerService.class).setAction(a),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void updateNotification() {
        Track t = current();
        if (t == null) return;
        boolean playing = isPlaying();
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(t.displayTitle())
                .setContentText(t.displayArtist())
                .setLargeIcon(art())
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "قبلی", action(ACTION_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        playing ? "توقف" : "پخش", action(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "بعدی", action(ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
        if (playing) {
            if (!foreground) {
                // keep the service alive on its own (not only while the UI is bound)
                startService(new Intent(this, PlayerService.class));
                startForeground(NOTIF_ID, n);
                foreground = true;
            } else {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, n);
            }
        } else if (foreground) {
            stopForeground(false);
            foreground = false;
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, n);
        }
    }

    private Notification placeholderNotification() {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle("GeekMusic")
                .setShowWhen(false)
                .build();
    }

    // ================================================================== state change fan-out

    private void changed(boolean trackChanged) {
        updateSession();
        updateNotification();
        for (Listener l : listeners) {
            if (trackChanged) l.onTrackChanged();
            l.onPlayerState();
        }
    }

    private void notifyState() {
        for (Listener l : listeners) l.onPlayerState();
    }

    // ================================================================== persistence

    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            if (isPlaying()) saveState();
            main.postDelayed(this, 5000);
        }
    };

    private void saveState() {
        final Track t = current();
        if (t == null) return;
        final List<String> paths = new ArrayList<>();
        for (Track q : queue) paths.add(q.path);
        final int[] ord = order.clone();
        final int p = pos;
        final long position = position();
        final boolean playing = isPlaying();
        io.post(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (String s : paths) sb.append(s).append('\n');
                File tmp = new File(getFilesDir(), "queue.txt.tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(sb.toString().getBytes("UTF-8"));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(new File(getFilesDir(), "queue.txt"));
                StringBuilder o = new StringBuilder();
                for (int i : ord) o.append(i).append(',');
                prefs.edit()
                        .putString("order", o.toString())
                        .putInt("pos", p)
                        .putLong("position", position)
                        .putBoolean("wasPlaying", playing)
                        .apply();
            } catch (Throwable ignored) {
            }
        });
    }

    private void restoreState() {
        try {
            File f = new File(getFilesDir(), "queue.txt");
            if (!f.exists()) return;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            }
            String[] lines = new String(buf, "UTF-8").split("\n");
            Library lib = Library.get(this);
            List<Track> q = new ArrayList<>();
            for (String l : lines) {
                if (l.isEmpty()) continue;
                Track t = lib.find(l);
                q.add(t != null ? t : new Track(l));
            }
            if (q.isEmpty()) return;
            String[] o = prefs.getString("order", "").split(",");
            int[] ord = new int[q.size()];
            boolean ok = o.length >= q.size();
            for (int i = 0; ok && i < q.size(); i++) {
                try {
                    ord[i] = Integer.parseInt(o[i]);
                    if (ord[i] < 0 || ord[i] >= q.size()) ok = false;
                } catch (NumberFormatException e) {
                    ok = false;
                }
            }
            if (!ok) for (int i = 0; i < ord.length; i++) ord[i] = i;
            queue.clear();
            queue.addAll(q);
            order = ord;
            pos = Math.max(0, Math.min(prefs.getInt("pos", 0), ord.length - 1));
            pendingSeek = (int) prefs.getLong("position", 0);
            Track cur = current();
            if (cur != null) {
                palette = Art.paletteFor(cur.displayTitle(), cur.displayArtist());
                loadArtAsync(cur);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Called after boot / USB mount: continue the last song if music was playing when the car turned off. */
    void resumeIfWasPlaying() {
        Track t = current();
        if (t == null || isPlaying() || !prefs.getBoolean("wasPlaying", false)) return;
        if (!new File(t.path).canRead()) return;
        load(true, (int) Math.max(0, prefs.getLong("position", 0)));
    }

    /** Restore the paused player for the UI (no autoplay). */
    void prepareRestored() {
        Track t = current();
        if (t == null || mp != null || !new File(t.path).canRead()) return;
        load(false, Math.max(0, pendingSeek));
    }
}
