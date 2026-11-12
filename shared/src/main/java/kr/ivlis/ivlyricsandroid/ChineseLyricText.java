package kr.ivlis.ivlyricsandroid;

import java.util.Locale;

/**
 * Decides whether a platform row counts as a usable Chinese translation of a lyric line.
 *
 * <p>A platform that "found the song" is not the same as a platform that has a usable Chinese
 * translation: rows are often romanisation, a copy of the original, a copyright notice, a credit or
 * an empty timestamp. Only a row that survives every rule below is offered to the renderer.
 */
final class ChineseLyricText {
    private static final String[] NOTICE_MARKERS = {
            "翻译作品", "翻譯作品", "享有本", "歌词翻译", "歌詞翻譯", "以下为", "以下為", "译者", "譯者",
            "未经许可", "未經許可", "禁止转载", "禁止轉載"
    };

    private ChineseLyricText() {
    }

    /** True when {@code translation} can stand in as the Chinese rendering of {@code source}. */
    static boolean isUsable(String translation, String source) {
        String candidate = translation == null ? "" : translation.trim();
        if (candidate.isEmpty() || isPlaceholder(candidate) || isNotice(candidate)) {
            return false;
        }
        if (hanCount(candidate) == 0) {
            // No Han character at all: romanisation, English or a bare symbol row.
            return false;
        }
        String original = source == null ? "" : source.trim();
        if (!original.isEmpty() && LyricsTextComparison.areEquivalent(candidate, original)) {
            // The platform repeated the original line instead of translating it.
            return false;
        }
        return !isUntranslatedJapanese(candidate, original);
    }

    /** A row that carries no words at all. */
    static boolean isPlaceholder(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return true;
        }
        if (value.equals("//") || value.equals("/") || value.equals("作词") || value.equals("作曲")) {
            return true;
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNotice(String text) {
        for (String marker : NOTICE_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return PlatformLyricRow.isCreditRow(text);
    }

    /**
     * A Japanese original keeps its kana when the platform only re-published it; a real Chinese
     * translation is written in Han characters.
     */
    private static boolean isUntranslatedJapanese(String candidate, String source) {
        int sourceKana = kanaCount(source);
        if (sourceKana == 0) {
            return false;
        }
        int candidateKana = kanaCount(candidate);
        if (candidateKana == 0) {
            return false;
        }
        return candidateKana * 3 >= Math.max(1, nonSpaceLength(candidate));
    }

    static int hanCount(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isHan(codePoint)) {
                count++;
            }
        }
        return count;
    }

    static int kanaCount(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean hiragana = codePoint >= 0x3041 && codePoint <= 0x309F;
            boolean katakana = codePoint >= 0x30A1 && codePoint <= 0x30FF;
            if (hiragana || katakana) {
                count++;
            }
        }
        return count;
    }

    static boolean hasKana(String text) {
        return kanaCount(text) > 0;
    }

    static int nonSpaceLength(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isWhitespace(codePoint)) {
                count++;
            }
        }
        return count;
    }

    static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private static boolean isHan(int codePoint) {
        // CJK Unified Ideographs plus the common extensions and compatibility block.
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x20000 && codePoint <= 0x2FA1F);
    }
}
