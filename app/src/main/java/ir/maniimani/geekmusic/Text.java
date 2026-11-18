package ir.maniimani.geekmusic;

import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.Locale;

/** Text helpers: Persian digits, time format, search normalisation, natural sort, mojibake repair. */
final class Text {

    private Text() {
    }

    private static final char[] FA = {'۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'};

    static String fa(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= '0' && c <= '9' ? FA[c - '0'] : c);
        }
        return b.toString();
    }

    static String fa(long n) {
        return fa(String.valueOf(n));
    }

    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        String out = h > 0
                ? h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec
                : m + ":" + (sec < 10 ? "0" : "") + sec;
        return fa(out);
    }

    static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Lower-case, unify Arabic/Persian letter variants and digits, drop ZWNJ — for search. */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'ي': case 'ى': c = 'ی'; break;
                case 'ك': c = 'ک'; break;
                case 'ة': c = 'ه'; break;
                case 'أ': case 'إ': case 'آ': c = 'ا'; break;
                case 'ؤ': c = 'و'; break;
                case '‌': case '‏': case '‎': continue;
                default:
                    if (c >= '۰' && c <= '۹') c = (char) ('0' + (c - '۰'));
                    else if (c >= '٠' && c <= '٩') c = (char) ('0' + (c - '٠'));
            }
            b.append(c);
        }
        return b.toString().toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Persian ID3 tags are often written in Windows-1256 and then read as Latin-1, which
     * turns "سوغاتی" into "ÓæÛÇÊí". Detect that pattern and decode it properly.
     */
    static String fixEncoding(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return s;
        int latinExt = 0, arabic = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x00C0 && c <= 0x00FF) latinExt++;
            else if (c >= 0x0600 && c <= 0x06FF) arabic++;
            else if (c > 0x00FF && !(c >= 0x2000 && c <= 0x206F)) return s; // real unicode text → leave it
        }
        if (arabic > 0 || latinExt < 2 || latinExt * 3 < countLetters(s)) return s;
        try {
            byte[] raw = s.getBytes("ISO-8859-1");
            String fixed = new String(raw, Charset.forName("windows-1256"));
            int ar = 0;
            for (int i = 0; i < fixed.length(); i++) {
                char c = fixed.charAt(i);
                if (c >= 0x0600 && c <= 0x06FF) ar++;
            }
            return ar >= latinExt / 2 ? fixed.trim() : s;
        } catch (Throwable t) {
            return s;
        }
    }

    private static int countLetters(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) n++;
        return Math.max(1, n);
    }

    /** "2 - x" < "10 - y": numbers compare by value, text by normalised form. */
    static final Comparator<String> NATURAL = (a, b) -> {
        String x = norm(a), y = norm(b);
        int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            char c1 = x.charAt(i), c2 = y.charAt(j);
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                int si = i, sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) i++;
                while (j < y.length() && Character.isDigit(y.charAt(j))) j++;
                String n1 = x.substring(si, i).replaceFirst("^0+(?=.)", "");
                String n2 = y.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (n1.length() != n2.length()) return n1.length() - n2.length();
                int c = n1.compareTo(n2);
                if (c != 0) return c;
            } else {
                if (c1 != c2) return c1 - c2;
                i++;
                j++;
            }
        }
        return (x.length() - i) - (y.length() - j);
    };

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
