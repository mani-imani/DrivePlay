package ir.maniimani.geekmusic;

import java.util.ArrayList;
import java.util.List;

/** A directory on a USB stick / SD card, mirroring the real folder structure. */
final class Folder {
    static final int KIND_FOLDER = 0, KIND_USB = 1, KIND_SD = 2, KIND_INTERNAL = 3;

    final String path;
    final String name;
    final int kind;
    Folder parent;
    final List<Folder> folders = new ArrayList<>();
    final List<Track> tracks = new ArrayList<>();
    int totalTracks; // including sub-folders

    Folder(String path, String name, int kind) {
        this.path = path;
        this.name = name;
        this.kind = kind;
    }

    boolean isRoot() {
        return kind != KIND_FOLDER;
    }

    /** All tracks in this folder and below, in on-disk order. */
    List<Track> allTracks() {
        List<Track> out = new ArrayList<>(totalTracks);
        collect(this, out);
        return out;
    }

    private static void collect(Folder f, List<Track> out) {
        out.addAll(f.tracks);
        for (Folder c : f.folders) collect(c, out);
    }
}
