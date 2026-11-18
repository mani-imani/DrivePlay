package ir.maniimani.driveplay;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Finds every audio file on the USB sticks / SD cards (and internal storage), keeps the
 * real folder tree, and fills in tags from MediaStore, a local tag cache and — in the
 * background — the files themselves.
 */
final class Library {

    interface Listener {
        void onLibraryChanged();
    }

    static final String UNKNOWN = "ناشناس";

    private static final Set<String> AUDIO = new HashSet<>(java.util.Arrays.asList(
            "mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "amr", "3gp", "mka"));
    private static final Set<String> SKIP_DIRS = new HashSet<>(java.util.Arrays.asList(
            "android", "lost.dir", "system volume information", "$recycle.bin", "recycler", "data", "obb"));

    private static Library sInstance;

    static synchronized Library get(Context c) {
        if (sInstance == null) sInstance = new Library(c.getApplicationContext());
        return sInstance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object scanLock = new Object();

    private volatile List<Folder> roots = Collections.emptyList();
    private volatile List<Track> all = Collections.emptyList();
    private volatile Map<String, Track> byPath = Collections.emptyMap();
    private volatile boolean scanning;
    private volatile boolean scannedOnce;
    private volatile int generation;

    private Library(Context c) {
        ctx = c;
    }

    // ------------------------------------------------------------------ public API

    List<Folder> roots() {
        return roots;
    }

    List<Track> all() {
        return all;
    }

    boolean isScanning() {
        return scanning;
    }

    boolean scannedOnce() {
        return scannedOnce;
    }

    Track find(String path) {
        return path == null ? null : byPath.get(path);
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Groups tracks by artist (or album). Unknown goes last. */
    LinkedHashMap<String, List<Track>> group(boolean byArtist) {
        Map<String, List<Track>> m = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        for (Track t : all) {
            String v = byArtist ? t.displayArtist() : t.displayAlbum();
            String key = Text.blank(v) ? "" : Text.norm(v);
            if (!display.containsKey(key)) display.put(key, Text.blank(v) ? UNKNOWN : v.trim());
            List<Track> list = m.get(key);
            if (list == null) m.put(key, list = new ArrayList<>());
            list.add(t);
        }
        List<String> keys = new ArrayList<>(m.keySet());
        Collections.sort(keys, (a, b) -> {
            if (a.isEmpty() != b.isEmpty()) return a.isEmpty() ? 1 : -1;
            return Text.NATURAL.compare(display.get(a), display.get(b));
        });
        LinkedHashMap<String, List<Track>> out = new LinkedHashMap<>();
        for (String k : keys) out.put(display.get(k), m.get(k));
        return out;
    }

    List<Track> search(String q) {
        String n = Text.norm(q);
        if (n.isEmpty()) return Collections.emptyList();
        String[] words = n.split("\\s+");
        List<Track> out = new ArrayList<>();
        for (Track t : all) {
            String s = t.searchText();
            boolean ok = true;
            for (String w : words) {
                if (!s.contains(w)) {
                    ok = false;
                    break;
                }
            }
            if (ok) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ scanning

    void scanAsync() {
        synchronized (scanLock) {
            if (scanning) return;
            scanning = true;
        }
        notifyChanged();
        final int gen = ++generation;
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                doScan();
            } catch (Throwable ignored) {
            } finally {
                scanning = false;
                scannedOnce = true;
                notifyChanged();
            }
            enrichTags(gen);
        }, "library-scan");
        t.start();
    }

    private void doScan() {
        Map<String, String[]> store = readMediaStore();
        Map<String, String[]> cache = TagCache.load(ctx);

        List<Folder> newRoots = new ArrayList<>();
        List<Track> newAll = new ArrayList<>();
        Map<String, Track> newBy = new HashMap<>();

        for (Root r : findRoots()) {
            Folder root = new Folder(r.path, r.label, r.kind);
            walk(new File(r.path), root, 0, r.kind == Folder.KIND_INTERNAL);
            if (root.totalTracks > 0) newRoots.add(root);
        }
        for (Folder root : newRoots) {
            for (Track t : root.allTracks()) {
                if (newBy.containsKey(t.path)) continue;
                applyTags(t, store.get(t.path), cache.get(t.path));
                newBy.put(t.path, t);
                newAll.add(t);
            }
        }
        Collections.sort(newAll, (a, b) -> Text.NATURAL.compare(a.displayTitle(), b.displayTitle()));
        roots = newRoots;
        all = newAll;
        byPath = newBy;
    }

    private static void applyTags(Track t, String[] store, String[] cached) {
        String[] src = cached != null ? cached : store;
        if (src == null) return;
        t.title = Text.fixEncoding(src[0]);
        t.artist = cleanArtist(Text.fixEncoding(src[1]));
        t.album = Text.fixEncoding(src[2]);
        try {
            t.duration = Long.parseLong(src[3]);
        } catch (Exception ignored) {
        }
        // MediaStore falls back to the file name as title; treat that as "no tag"
        if (t.title != null && t.title.equals(Text.stripExtension(t.fileName))) t.title = null;
        t.tagsRead = cached != null;
    }

    private static String cleanArtist(String a) {
        if (a == null) return null;
        String n = a.trim();
        return n.equalsIgnoreCase("<unknown>") || n.equalsIgnoreCase("unknown") ? null : n;
    }

    private void walk(File dir, Folder into, int depth, boolean internal) {
        if (depth > 14) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        List<File> dirs = new ArrayList<>(), files = new ArrayList<>();
        for (File f : list) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                String low = name.toLowerCase(Locale.ROOT);
                if (SKIP_DIRS.contains(low)) continue;
                if (internal && depth == 0 && (low.equals("notifications") || low.equals("ringtones") || low.equals("alarms")
                        || low.equals("telegram") || low.equals("whatsapp") || low.equals("recordings"))) continue;
                dirs.add(f);
            } else if (isAudio(name)) {
                files.add(f);
            }
        }
        Collections.sort(dirs, (a, b) -> Text.NATURAL.compare(a.getName(), b.getName()));
        Collections.sort(files, (a, b) -> Text.NATURAL.compare(a.getName(), b.getName()));
        for (File f : files) into.tracks.add(new Track(f.getAbsolutePath()));
        int total = into.tracks.size();
        for (File d : dirs) {
            Folder sub = new Folder(d.getAbsolutePath(), d.getName(), Folder.KIND_FOLDER);
            walk(d, sub, depth + 1, internal);
            if (sub.totalTracks > 0) {
                sub.parent = into;
                into.folders.add(sub);
                total += sub.totalTracks;
            }
        }
        into.totalTracks = total;
    }

    static boolean isAudio(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && AUDIO.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ storage roots

    private static final class Root {
        final String path, label;
        final int kind;

        Root(String path, String label, int kind) {
            this.path = path;
            this.label = label;
            this.kind = kind;
        }
    }

    private List<Root> findRoots() {
        Map<String, Root> found = new LinkedHashMap<>();
        String internal = canon(Environment.getExternalStorageDirectory().getAbsolutePath());

        // 1. StorageManager volumes
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!Environment.MEDIA_MOUNTED.equals(v.getState()) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(v.getState()))
                    continue;
                String p = volumePath(v);
                if (p == null) continue;
                String desc = String.valueOf(v.getDescription(ctx));
                addRoot(found, p, internal, desc);
            }
        } catch (Throwable ignored) {
        }
        // 2. hidden getVolumePaths (what the stock player uses)
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            String[] paths = (String[]) sm.getClass().getMethod("getVolumePaths").invoke(sm);
            if (paths != null) for (String p : paths) addRoot(found, p, internal, null);
        } catch (Throwable ignored) {
        }
        // 3. anything readable under /storage and common head-unit mount points
        for (String base : new String[]{"/storage", "/mnt", "/mnt/media_rw"}) {
            File[] kids = new File(base).listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                String n = k.getName().toLowerCase(Locale.ROOT);
                if (n.equals("self") || n.equals("emulated") || n.equals("sdcard") || n.equals("runtime")
                        || n.equals("secure") || n.equals("asec") || n.equals("obb") || n.equals("user")
                        || n.equals("appfuse") || n.equals("expand") || n.equals("product") || n.equals("vendor"))
                    continue;
                addRoot(found, k.getAbsolutePath(), internal, null);
            }
        }
        addRoot(found, internal, internal, null);

        List<Root> out = new ArrayList<>(found.values());
        // removable first: USB, then SD, then internal
        Collections.sort(out, (a, b) -> a.kind - b.kind);
        // number duplicates: "فلش ۱", "فلش ۲"
        Map<Integer, Integer> count = new HashMap<>();
        for (Root r : out) count.put(r.kind, (count.containsKey(r.kind) ? count.get(r.kind) : 0) + 1);
        Map<Integer, Integer> seen = new HashMap<>();
        List<Root> labelled = new ArrayList<>();
        for (Root r : out) {
            int n = (seen.containsKey(r.kind) ? seen.get(r.kind) : 0) + 1;
            seen.put(r.kind, n);
            String label = r.label + (count.get(r.kind) > 1 && r.kind != Folder.KIND_INTERNAL ? " " + Text.fa(n) : "");
            labelled.add(new Root(r.path, label, r.kind));
        }
        return labelled;
    }

    private static void addRoot(Map<String, Root> found, String path, String internal, String desc) {
        if (path == null) return;
        File f = new File(path);
        if (!f.isDirectory() || !f.canRead()) return;
        String c = canon(path);
        for (String existing : found.keySet()) {
            if (existing.equals(c)) return;
        }
        int kind;
        String label;
        String low = (c + " " + (desc == null ? "" : desc)).toLowerCase(Locale.ROOT);
        if (c.equals(internal)) {
            kind = Folder.KIND_INTERNAL;
            label = "حافظه‌ی داخلی";
        } else if (low.contains("usb") || low.contains("udisk") || low.contains("otg")) {
            kind = Folder.KIND_USB;
            label = "فلش";
        } else if (low.contains("sd") || low.contains("card") || low.contains("ext")) {
            kind = Folder.KIND_SD;
            label = "کارت حافظه";
        } else {
            // unnamed removable volume (e.g. /storage/1A2B-3C4D): most often a USB stick in a car
            kind = Folder.KIND_USB;
            label = "فلش";
        }
        found.put(c, new Root(c, label, kind));
    }

    private static String volumePath(StorageVolume v) {
        for (String m : new String[]{"getPath", "getInternalPath"}) {
            try {
                Object o = StorageVolume.class.getMethod(m).invoke(v);
                if (o instanceof String) return (String) o;
            } catch (Throwable ignored) {
            }
        }
        try {
            Object o = StorageVolume.class.getMethod("getPathFile").invoke(v);
            if (o instanceof File) return ((File) o).getAbsolutePath();
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String canon(String p) {
        try {
            return new File(p).getCanonicalPath();
        } catch (IOException e) {
            return p;
        }
    }

    // ------------------------------------------------------------------ tags

    private Map<String, String[]> readMediaStore() {
        Map<String, String[]> m = new HashMap<>();
        String[] proj = {MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION};
        try (Cursor c = ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)) {
            if (c == null) return m;
            while (c.moveToNext()) {
                String path = c.getString(0);
                if (path == null) continue;
                String[] v = {c.getString(1), c.getString(2), c.getString(3), String.valueOf(c.getLong(4))};
                m.put(path, v);
                m.put(canon(path), v);
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    /** Reads real tags from files that haven't been read yet, slowly, in the background. */
    private void enrichTags(int gen) {
        List<Track> todo = new ArrayList<>();
        for (Track t : all) if (!t.tagsRead) todo.add(t);
        if (todo.isEmpty()) return;
        Map<String, String[]> cache = TagCache.load(ctx);
        long lastNotify = System.currentTimeMillis();
        int sinceSave = 0;
        for (Track t : todo) {
            if (gen != generation) return; // a newer scan started
            readTags(t);
            cache.put(t.path, new String[]{t.title, t.artist, t.album, String.valueOf(t.duration)});
            if (++sinceSave >= 50) {
                TagCache.save(ctx, cache);
                sinceSave = 0;
            }
            if (System.currentTimeMillis() - lastNotify > 2500) {
                lastNotify = System.currentTimeMillis();
                notifyChanged();
            }
        }
        TagCache.save(ctx, cache);
        notifyChanged();
    }

    /** Reads tags straight from the file (used for the playing track and background enrichment). */
    static void readTags(Track t) {
        if (t.tagsRead) return;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(t.path);
            String title = Text.fixEncoding(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            String artist = Text.fixEncoding(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (Text.blank(artist)) artist = Text.fixEncoding(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            String album = Text.fixEncoding(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!Text.blank(title)) t.title = title;
            if (!Text.blank(artist)) t.artist = cleanArtist(artist);
            if (!Text.blank(album)) t.album = album;
            if (dur != null) t.duration = Long.parseLong(dur.trim());
        } catch (Throwable ignored) {
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
            t.tagsRead = true;
        }
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) l.onLibraryChanged();
        });
    }

    // ------------------------------------------------------------------ tag cache (survives restarts)

    static final class TagCache {
        private static final String FILE = "tags.json";

        static synchronized Map<String, String[]> load(Context c) {
            Map<String, String[]> m = new HashMap<>();
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return m;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                JSONObject o = new JSONObject(new String(buf, 0, off, "UTF-8"));
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray a = o.getJSONArray(k);
                    m.put(k, new String[]{a.isNull(0) ? null : a.getString(0), a.isNull(1) ? null : a.getString(1),
                            a.isNull(2) ? null : a.getString(2), a.isNull(3) ? "0" : a.getString(3)});
                }
            } catch (Throwable ignored) {
            }
            return m;
        }

        static synchronized void save(Context c, Map<String, String[]> m) {
            try {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, String[]> e : m.entrySet()) {
                    JSONArray a = new JSONArray();
                    for (String s : e.getValue()) a.put(s == null ? JSONObject.NULL : s);
                    o.put(e.getKey(), a);
                }
                File tmp = new File(c.getFilesDir(), FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(o.toString().getBytes("UTF-8"));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(new File(c.getFilesDir(), FILE));
            } catch (Throwable ignored) {
            }
        }
    }
}
