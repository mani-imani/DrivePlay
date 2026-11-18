package ir.maniimani.geekmusic;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The library: real folder tree of the USB stick / SD card, all songs, artists, albums and
 * search, with a mini player at the bottom. Persian / right-to-left.
 */
final class LibraryPanel extends FrameLayout implements Library.Listener {

    interface Host {
        PlayerService service();

        void onPickTrack(List<Track> context, int index);

        void closeLibrary();
    }

    private static final int TAB_FOLDERS = 0, TAB_SONGS = 1, TAB_ARTISTS = 2, TAB_ALBUMS = 3;

    private final Host host;
    private final Library lib;
    private final Typeface fRegular, fMedium, fBold;
    private final float d;

    private int accent = Art.NEUTRAL.accent;
    private int tab = TAB_FOLDERS;
    private boolean searching;
    private String query = "";

    // folder navigation
    private Folder folder;                         // null = list of roots (or the single root)
    private final List<int[]> scrollStack = new ArrayList<>();   // {position, offset} per level
    // artist / album drill-down
    private String groupName;
    private List<Track> groupTracks;

    private LinearLayout topBar, searchBar, crumb;
    private final TextView[] tabViews = new TextView[4];
    private EditText searchField;
    private TextView crumbTitle, crumbCount, emptyView;
    private ListView list;
    private final Adapter adapter = new Adapter();
    private MiniPlayer mini;

    LibraryPanel(Context c, Host host, Typeface regular, Typeface medium, Typeface bold) {
        super(c);
        this.host = host;
        this.lib = Library.get(c);
        this.fRegular = regular;
        this.fMedium = medium;
        this.fBold = bold;
        this.d = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
        setClickable(true); // swallow touches so the player underneath never gets them
        build();
        refresh(false);
    }

    // ================================================================== public

    void setAccent(int color) {
        accent = color;
        updateTabs();
        adapter.notifyDataSetChanged();
        mini.setAccent(color);
    }

    void onShown() {
        lib.addListener(this);
        refresh(SCROLL_KEEP);
        mini.update();
    }

    void onHidden() {
        lib.removeListener(this);
        hideKeyboard();
    }

    /** Open straight at the folder containing this track. */
    void revealFolderOf(Track t) {
        if (t == null) return;
        for (Folder root : lib.roots()) {
            Folder f = findFolder(root, t.folderPath);
            if (f != null) {
                searching = false;
                tab = TAB_FOLDERS;
                folder = f;
                scrollStack.clear();
                refresh(false);
                final int idx = adapter.indexOfTrack(t);
                if (idx >= 0) list.post(() -> list.setSelectionFromTop(idx, (int) (60 * d)));
                return;
            }
        }
    }

    /** @return true if consumed (navigated up / closed search). */
    boolean onBack() {
        if (searching) {
            closeSearch();
            return true;
        }
        if (groupName != null) {
            groupName = null;
            groupTracks = null;
            refresh(true);
            return true;
        }
        if (tab == TAB_FOLDERS && folder != null && !isTopFolder(folder)) {
            folder = folder.parent != null ? folder.parent : null;
            refresh(true);
            return true;
        }
        return false;
    }

    void onPlayerChanged() {
        adapter.notifyDataSetChanged();
        mini.update();
    }

    void tick() {
        mini.tick();
    }

    @Override
    public void onLibraryChanged() {
        // the tree may have been rebuilt: re-find the open folder by path
        if (folder != null) {
            Folder again = null;
            for (Folder root : lib.roots()) {
                again = findFolder(root, folder.path);
                if (again != null) break;
            }
            folder = again;
        }
        if (groupName != null && tab == TAB_ARTISTS || tab == TAB_ALBUMS && groupName != null) {
            List<Track> g = lib.group(tab == TAB_ARTISTS).get(groupName);
            if (g == null) {
                groupName = null;
                groupTracks = null;
            } else {
                groupTracks = g;
            }
        }
        refresh(SCROLL_KEEP);
    }

    // ================================================================== content

    private boolean isTopFolder(Folder f) {
        List<Folder> roots = lib.roots();
        return roots.size() == 1 && f == roots.get(0);
    }

    private static Folder findFolder(Folder f, String path) {
        if (f.path.equals(path)) return f;
        if (!path.startsWith(f.path)) return null;
        for (Folder c : f.folders) {
            Folder r = findFolder(c, path);
            if (r != null) return r;
        }
        return null;
    }

    private static final int SCROLL_TOP = 0, SCROLL_KEEP = 1, SCROLL_POP = 2;

    private void refresh(boolean restoreScroll) {
        refresh(restoreScroll ? SCROLL_POP : SCROLL_TOP);
    }

    private void refresh(int scroll) {
        int keepPos = list == null ? 0 : list.getFirstVisiblePosition();
        View first = list == null ? null : list.getChildAt(0);
        int keepOff = first == null ? 0 : first.getTop();
        List<Row> rows = new ArrayList<>();
        String crumbText = null, countText = null;
        List<Track> playable = null;

        if (searching) {
            List<Track> found = lib.search(query);
            for (Track t : found) rows.add(Row.track(t, found));
            if (!found.isEmpty()) {
                crumbText = "نتیجه‌ی جستجو";
                countText = Text.fa(found.size()) + " آهنگ";
                playable = found;
            }
        } else if (tab == TAB_FOLDERS) {
            List<Folder> roots = lib.roots();
            if (folder == null && roots.size() == 1) folder = roots.get(0);
            if (folder == null) {
                for (Folder r : roots) rows.add(Row.folder(r));
            } else {
                for (Folder f : folder.folders) rows.add(Row.folder(f));
                for (Track t : folder.tracks) rows.add(Row.track(t, folder.tracks));
                crumbText = folder.name;
                countText = Text.fa(folder.totalTracks) + " آهنگ";
                playable = folder.allTracks();
            }
        } else if (tab == TAB_SONGS) {
            List<Track> all = lib.all();
            for (Track t : all) rows.add(Row.track(t, all));
        } else if (groupName == null) {
            LinkedHashMap<String, List<Track>> groups = lib.group(tab == TAB_ARTISTS);
            for (Map.Entry<String, List<Track>> e : groups.entrySet()) rows.add(Row.group(e.getKey(), e.getValue(), tab == TAB_ARTISTS));
        } else {
            for (Track t : groupTracks) rows.add(Row.track(t, groupTracks));
            crumbText = groupName;
            countText = Text.fa(groupTracks.size()) + " آهنگ";
            playable = groupTracks;
        }

        boolean showCrumb = crumbText != null && (searching || groupName != null
                || (tab == TAB_FOLDERS && folder != null));
        crumb.setVisibility(showCrumb ? VISIBLE : GONE);
        if (showCrumb) {
            crumbTitle.setText(crumbText);
            crumbCount.setText(countText);
            crumb.setTag(playable);
            // no "up" arrow at the top of a single-root tree or in search
            crumb.getChildAt(0).setVisibility(searching || (tab == TAB_FOLDERS && isTopFolder(folder)) ? GONE : VISIBLE);
        }

        adapter.set(rows);
        updateEmpty(rows.isEmpty());
        if (scroll == SCROLL_TOP) {
            list.setSelection(0);
        } else if (scroll == SCROLL_KEEP) {
            list.setSelectionFromTop(keepPos, keepOff);
        } else {
            int[] st = scrollStack.isEmpty() ? new int[]{0, 0} : scrollStack.remove(scrollStack.size() - 1);
            list.setSelectionFromTop(st[0], st[1]);
        }
    }

    private void updateEmpty(boolean empty) {
        if (!empty) {
            emptyView.setVisibility(GONE);
            return;
        }
        emptyView.setVisibility(VISIBLE);
        if (lib.isScanning() && !lib.scannedOnce()) emptyView.setText("در حال خوندن فلش و کارت حافظه…");
        else if (searching) emptyView.setText(Text.blank(query) ? "اسم آهنگ، خواننده یا آلبوم رو بنویس" : "چیزی پیدا نشد");
        else if (lib.roots().isEmpty()) emptyView.setText("آهنگی پیدا نشد\nفلش یا کارت حافظه رو وصل کن");
        else emptyView.setText("خالیه");
    }

    // ================================================================== UI

    private void build() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- top bar: close · tabs · search
        topBar = new LinearLayout(getContext());
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(2));
        topBar.addView(iconButton(R.drawable.ic_close, 44, v -> host.closeLibrary(), "بستن"));
        LinearLayout tabs = new LinearLayout(getContext());
        tabs.setGravity(Gravity.CENTER);
        String[] names = {"پوشه‌ها", "همه‌ی آهنگ‌ها", "خواننده‌ها", "آلبوم‌ها"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = text(fMedium, 14, MainActivity.C_TEXT_2);
            t.setText(names[i]);
            t.setPadding(dp(14), dp(7), dp(14), dp(7));
            t.setOnClickListener(v -> selectTab(idx));
            tabViews[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(4));
            lp.setMarginEnd(dp(4));
            tabs.addView(t, lp);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(true);
        hs.addView(tabs, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topBar.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(iconButton(R.drawable.ic_search, 44, v -> openSearch(), "جستجو"));
        col.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // ---- search bar
        searchBar = new LinearLayout(getContext());
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(8), dp(6), dp(8), dp(2));
        searchBar.addView(iconButton(R.drawable.ic_back, 44, v -> closeSearch(), "برگشت"));
        searchField = new EditText(getContext());
        searchField.setTypeface(fMedium);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchField.setTextColor(MainActivity.C_TEXT);
        searchField.setHintTextColor(MainActivity.C_TEXT_3);
        searchField.setHint("جستجوی آهنگ، خواننده، آلبوم…");
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchField.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        searchField.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(MainActivity.C_SURFACE);
        sb.setStroke(dp(1), MainActivity.C_STROKE);
        sb.setCornerRadius(dp(100));
        searchField.setBackground(sb);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                refresh(false);
            }
        });
        searchField.setOnEditorActionListener((v, id, e) -> {
            hideKeyboard();
            return true;
        });
        LinearLayout.LayoutParams sflp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        sflp.setMarginStart(dp(6));
        sflp.setMarginEnd(dp(6));
        searchBar.addView(searchField, sflp);
        searchBar.setVisibility(GONE);
        col.addView(searchBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // ---- crumb: up · name · count · shuffle-all · play-all
        crumb = new LinearLayout(getContext());
        crumb.setGravity(Gravity.CENTER_VERTICAL);
        crumb.setPadding(dp(8), 0, dp(12), 0);
        crumb.addView(iconButton(R.drawable.ic_back, 40, v -> onBack(), "بالا"));
        LinearLayout names2 = new LinearLayout(getContext());
        names2.setOrientation(LinearLayout.VERTICAL);
        names2.setPadding(dp(6), 0, dp(6), 0);
        crumbTitle = text(fBold, 16, MainActivity.C_TEXT);
        crumbTitle.setGravity(Gravity.START);
        crumbTitle.setSingleLine(true);
        crumbTitle.setEllipsize(TextUtils.TruncateAt.END);
        crumbCount = text(fRegular, 12, MainActivity.C_TEXT_3);
        crumbCount.setGravity(Gravity.START);
        names2.addView(crumbTitle);
        names2.addView(crumbCount);
        crumb.addView(names2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        crumb.addView(iconButton(R.drawable.ic_shuffle, 40, v -> playAll(true), "پخش تصادفی"));
        TextView playAll = text(fMedium, 13, MainActivity.C_TEXT);
        playAll.setText("پخش همه");
        playAll.setPadding(dp(16), dp(7), dp(16), dp(7));
        GradientDrawable pa = new GradientDrawable();
        pa.setColor(MainActivity.C_SURFACE);
        pa.setStroke(dp(1), MainActivity.C_STROKE);
        pa.setCornerRadius(dp(100));
        playAll.setBackground(new RippleDrawable(ColorStateList.valueOf(MainActivity.C_RIPPLE), pa, null));
        playAll.setOnClickListener(v -> playAll(false));
        LinearLayout.LayoutParams palp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        palp.setMarginStart(dp(6));
        crumb.addView(playAll, palp);
        col.addView(crumb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // ---- list
        FrameLayout listHolder = new FrameLayout(getContext());
        list = new ListView(getContext());
        list.setDivider(new ColorDrawable(Color.rgb(18, 18, 20)));
        list.setDividerHeight(Math.max(1, dp(1)));
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(true);
        list.setFastScrollEnabled(true);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> onRow(adapter.getRow(position)));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int s) {
                if (s != SCROLL_STATE_IDLE) hideKeyboard();
            }

            @Override
            public void onScroll(AbsListView v, int a, int b, int c) {
            }
        });
        listHolder.addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyView = text(fMedium, 15, MainActivity.C_TEXT_3);
        emptyView.setLineSpacing(0, 1.3f);
        listHolder.addView(emptyView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(listHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- mini player
        mini = new MiniPlayer(getContext());
        col.addView(mini, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        updateTabs();
    }

    private void selectTab(int idx) {
        if (searching) closeSearch();
        if (tab == idx && groupName == null && !(idx == TAB_FOLDERS && folder != null && !isTopFolder(folder))) return;
        tab = idx;
        groupName = null;
        groupTracks = null;
        if (idx == TAB_FOLDERS) folder = null;
        scrollStack.clear();
        updateTabs();
        refresh(false);
    }

    private void updateTabs() {
        for (int i = 0; i < 4; i++) {
            TextView t = tabViews[i];
            boolean on = i == tab;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(100));
            bg.setColor(on ? Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)) : Color.TRANSPARENT);
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(MainActivity.C_RIPPLE), bg, null));
            t.setTextColor(on ? accent : MainActivity.C_TEXT_2);
            t.setTypeface(on ? fBold : fMedium);
        }
    }

    private void openSearch() {
        searching = true;
        topBar.setVisibility(GONE);
        searchBar.setVisibility(VISIBLE);
        searchField.setText(query);
        searchField.requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        searchField.post(() -> imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT));
        refresh(false);
    }

    private void closeSearch() {
        searching = false;
        hideKeyboard();
        searchBar.setVisibility(GONE);
        topBar.setVisibility(VISIBLE);
        refresh(false);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    @SuppressWarnings("unchecked")
    private void playAll(boolean shuffled) {
        Object tag = crumb.getTag();
        if (!(tag instanceof List)) return;
        List<Track> tracks = (List<Track>) tag;
        if (tracks.isEmpty()) return;
        PlayerService s = host.service();
        if (s == null) return;
        s.setShuffle(shuffled);
        int start = shuffled ? new java.util.Random().nextInt(tracks.size()) : 0;
        host.onPickTrack(tracks, start);
    }

    private void onRow(Row r) {
        if (r == null) return;
        switch (r.type) {
            case Row.FOLDER:
                pushScroll();
                folder = r.folder;
                refresh(false);
                break;
            case Row.GROUP:
                pushScroll();
                groupName = r.name;
                groupTracks = r.tracks;
                refresh(false);
                break;
            case Row.TRACK:
                hideKeyboard();
                host.onPickTrack(r.tracks, r.tracks.indexOf(r.track));
                break;
        }
    }

    private void pushScroll() {
        View first = list.getChildAt(0);
        scrollStack.add(new int[]{list.getFirstVisiblePosition(), first == null ? 0 : first.getTop()});
    }

    // ================================================================== rows

    private static final class Row {
        static final int FOLDER = 0, TRACK = 1, GROUP = 2;
        int type;
        Folder folder;
        Track track;
        String name;
        List<Track> tracks;
        boolean artist;

        static Row folder(Folder f) {
            Row r = new Row();
            r.type = FOLDER;
            r.folder = f;
            return r;
        }

        static Row track(Track t, List<Track> ctx) {
            Row r = new Row();
            r.type = TRACK;
            r.track = t;
            r.tracks = ctx;
            return r;
        }

        static Row group(String name, List<Track> ts, boolean artist) {
            Row r = new Row();
            r.type = GROUP;
            r.name = name;
            r.tracks = ts;
            r.artist = artist;
            return r;
        }
    }

    private final class Adapter extends BaseAdapter {
        private List<Row> rows = Collections.emptyList();

        void set(List<Row> r) {
            rows = r;
            notifyDataSetChanged();
        }

        Row getRow(int i) {
            return i >= 0 && i < rows.size() ? rows.get(i) : null;
        }

        int indexOfTrack(Track t) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).track == t) return i;
            return -1;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            RowView v = convert instanceof RowView ? (RowView) convert : new RowView(getContext());
            v.bind(rows.get(i));
            return v;
        }
    }

    private final class RowView extends LinearLayout {
        final ImageView thumb;
        final TextView title, sub, trailing;
        final ImageView playing;

        RowView(Context c) {
            super(c);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(8), dp(16), dp(8));
            setMinimumHeight(dp(60));
            setLayoutDirection(LAYOUT_DIRECTION_RTL);
            setBackground(new RippleDrawable(ColorStateList.valueOf(MainActivity.C_RIPPLE), null, new ColorDrawable(Color.WHITE)));

            thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 8 * d);
                }
            });
            thumb.setClipToOutline(true);
            addView(thumb, new LayoutParams(dp(44), dp(44)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            texts.setPadding(dp(14), 0, dp(10), 0);
            title = text(fMedium, 15, MainActivity.C_TEXT);
            title.setGravity(Gravity.START);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = text(fRegular, 12, MainActivity.C_TEXT_3);
            sub.setGravity(Gravity.START);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            texts.addView(sub);
            addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            playing = new ImageView(c);
            playing.setImageResource(R.drawable.ic_note);
            addView(playing, new LayoutParams(dp(20), dp(20)));

            trailing = text(fRegular, 12, MainActivity.C_TEXT_3);
            addView(trailing, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(Row r) {
            Track cur = host.service() == null ? null : host.service().current();
            playing.setVisibility(GONE);
            trailing.setVisibility(VISIBLE);
            title.setTextColor(MainActivity.C_TEXT);
            int thumbPx = dp(44);
            switch (r.type) {
                case Row.FOLDER: {
                    Folder f = r.folder;
                    int icon = f.kind == Folder.KIND_USB ? R.drawable.ic_usb
                            : f.kind == Folder.KIND_SD ? R.drawable.ic_sd : R.drawable.ic_folder;
                    setIconThumb(icon);
                    title.setText(f.name);
                    int subs = f.folders.size();
                    sub.setText(subs > 0 ? Text.fa(subs) + " پوشه" : "");
                    sub.setVisibility(subs > 0 ? VISIBLE : GONE);
                    trailing.setText(Text.fa(f.totalTracks) + " آهنگ");
                    break;
                }
                case Row.GROUP: {
                    if (Library.UNKNOWN.equals(r.name)) setIconThumb(r.artist ? R.drawable.ic_person : R.drawable.ic_album);
                    else {
                        thumb.setBackground(null);
                        thumb.setPadding(0, 0, 0, 0);
                        thumb.setImageTintList(null);
                        thumb.setImageBitmap(Thumbs.get(thumbPx, r.name, "", fBold));
                    }
                    title.setText(r.name);
                    String sub2 = "";
                    if (!r.artist && !r.tracks.isEmpty()) sub2 = r.tracks.get(0).displayArtist();
                    sub.setText(sub2);
                    sub.setVisibility(Text.blank(sub2) ? GONE : VISIBLE);
                    trailing.setText(Text.fa(r.tracks.size()) + " آهنگ");
                    break;
                }
                default: {
                    Track t = r.track;
                    thumb.setBackground(null);
                    thumb.setPadding(0, 0, 0, 0);
                    thumb.setImageTintList(null);
                    thumb.setImageBitmap(Thumbs.get(thumbPx, t.displayTitle(), t.displayArtist(), fBold));
                    title.setText(t.displayTitle());
                    String a = t.displayArtist();
                    String al = t.displayAlbum();
                    String s = !Text.blank(a) && !Text.blank(al) ? a + " · " + al : !Text.blank(a) ? a : al;
                    sub.setText(s);
                    sub.setVisibility(Text.blank(s) ? GONE : VISIBLE);
                    trailing.setText(t.duration > 0 ? Text.time(t.duration) : "");
                    if (cur != null && cur.path.equals(t.path)) {
                        title.setTextColor(accent);
                        playing.setVisibility(VISIBLE);
                        playing.setImageTintList(ColorStateList.valueOf(accent));
                        trailing.setVisibility(GONE);
                    }
                }
            }
        }

        private void setIconThumb(int icon) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(20, 20, 23));
            bg.setCornerRadius(8 * d);
            thumb.setBackground(bg);
            thumb.setImageResource(icon);
            thumb.setImageTintList(ColorStateList.valueOf(MainActivity.C_TEXT_2));
            thumb.setPadding(dp(11), dp(11), dp(11), dp(11));
        }
    }

    // ================================================================== mini player

    private final class MiniPlayer extends LinearLayout {
        final ImageView art;
        final TextView title, sub;
        final ImageButton toggle, next;
        final View bar;
        int color = Art.NEUTRAL.accent;
        String artKey;

        MiniPlayer(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackgroundColor(Color.rgb(10, 10, 12));
            bar = new View(c) {
                final android.graphics.Paint p = new android.graphics.Paint();

                @Override
                protected void onDraw(android.graphics.Canvas cv) {
                    PlayerService s = host.service();
                    float f = 0;
                    if (s != null && s.duration() > 0) f = s.position() / (float) s.duration();
                    p.setColor(Color.rgb(26, 26, 29));
                    cv.drawRect(0, 0, getWidth(), getHeight(), p);
                    p.setColor(color);
                    cv.drawRect(0, 0, getWidth() * Math.max(0, Math.min(1, f)), getHeight(), p); // LTR
                }
            };
            addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, dp(2))));

            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutDirection(LAYOUT_DIRECTION_RTL);
            row.setPadding(dp(14), 0, dp(8), 0);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(MainActivity.C_RIPPLE), null, new ColorDrawable(Color.WHITE)));
            row.setOnClickListener(v -> host.closeLibrary());
            art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 7 * d);
                }
            });
            art.setClipToOutline(true);
            row.addView(art, new LayoutParams(dp(42), dp(42)));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            texts.setPadding(dp(12), 0, dp(8), 0);
            title = text(fBold, 14, MainActivity.C_TEXT);
            title.setGravity(Gravity.START);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = text(fRegular, 12, MainActivity.C_TEXT_3);
            sub.setGravity(Gravity.START);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            texts.addView(sub);
            row.addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            toggle = iconButton(R.drawable.ic_play, 48, v -> {
                PlayerService s = host.service();
                if (s != null) s.toggle();
            }, "پخش");
            next = iconButton(R.drawable.ic_next, 48, v -> {
                PlayerService s = host.service();
                if (s != null) s.next();
            }, "بعدی");
            next.setScaleX(-1f); // in RTL the "next" arrow points left
            row.addView(toggle);
            row.addView(next);
            addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        void setAccent(int c) {
            color = c;
            bar.invalidate();
        }

        void update() {
            PlayerService s = host.service();
            Track t = s == null ? null : s.current();
            setVisibility(t == null ? GONE : VISIBLE);
            if (t == null) return;
            title.setText(t.displayTitle());
            String a = t.displayArtist();
            sub.setText(Text.blank(a) ? t.displayAlbum() : a);
            toggle.setImageResource(s.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            String key = t.path + (s.art() != null ? "#real" : "");
            if (!key.equals(artKey)) {
                artKey = key;
                if (s.art() != null) art.setImageBitmap(s.art());
                else art.setImageBitmap(Thumbs.get(dp(42), t.displayTitle(), t.displayArtist(), fBold));
            }
            bar.invalidate();
        }

        void tick() {
            if (getVisibility() == VISIBLE) bar.invalidate();
        }
    }

    // ================================================================== helpers

    private ImageButton iconButton(int icon, int sizeDp, OnClickListener l, String desc) {
        ImageButton b = new ImageButton(getContext());
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(MainActivity.C_ICON));
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp((sizeDp - 24) / 2f);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(MainActivity.C_RIPPLE), null, new ShapeDrawable(new OvalShape())));
        b.setContentDescription(desc);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView t = new TextView(getContext());
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTextDirection(TEXT_DIRECTION_FIRST_STRONG);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * d);
    }
}
