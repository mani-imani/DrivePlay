package ir.maniimani.driveplay;

import java.io.File;

/** One audio file. Tag fields may be filled in later as tags are read. */
final class Track {
    final String path;
    final String fileName;
    final String folderPath;
    volatile String title;   // from tags; null if unknown
    volatile String artist;
    volatile String album;
    volatile long duration;  // ms, 0 if unknown
    volatile boolean tagsRead;

    Track(String path) {
        this.path = path;
        File f = new File(path);
        this.fileName = f.getName();
        String parent = f.getParent();
        this.folderPath = parent == null ? "" : parent;
    }

    String displayTitle() {
        return !Text.blank(title) ? title : Text.stripExtension(fileName);
    }

    String displayArtist() {
        return Text.blank(artist) ? "" : artist;
    }

    String displayAlbum() {
        return Text.blank(album) ? "" : album;
    }

    String searchText() {
        return Text.norm(displayTitle() + " " + displayArtist() + " " + displayAlbum() + " " + fileName);
    }
}
