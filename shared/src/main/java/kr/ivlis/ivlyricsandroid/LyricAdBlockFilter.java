package kr.ivlis.ivlyricsandroid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Spotify's own lyrics and QQ Music's LRC both sometimes carry a "You might also like" block, written
 * as ordinary timed lines in the middle of the song: a marker followed by other songs' titles and the
 * artist name, all within a second or two of each other. Rendered as lyrics they read as nonsense
 * ("Genesis", "Watcher of the Skies"), and the platform aligner would happily attach translations to
 * them.
 *
 * <p>The block is recognised by its marker and by the way its lines cluster: each one starts within
 * three seconds of the marker and none of them reads like a lyric line. A real lyric line directly
 * after the block ends it, because it is either longer than a title or carries punctuation.</p>
 */
final class LyricAdBlockFilter {
    private LyricAdBlockFilter() {
    }

    /** Lines further from the marker than this are not part of the block. */
    private static final long BLOCK_WINDOW_MS = 3000L;
    /** A safety cap for block markers that carry no timing at all. */
    private static final int MAX_BLOCK_LINES = 8;
    /** A title that fits in a recommendation cell; lyric lines are usually longer. */
    private static final int MAX_TITLE_LENGTH = 32;

    private static final String[] MARKERS = {
            "you might also like",
            "你可能也喜欢",
            "你可能也會喜歡",
            "你也可能喜欢",
            "おすすめの曲"
    };

    private static final String PUNCTUATION = ",.!?;:，。！？；：、\"“”";

    static boolean isMarker(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        for (String marker : MARKERS) {
            if (value.equals(marker)) {
                return true;
            }
        }
        return false;
    }

    /** A line that reads like a sentence rather than a title. */
    static boolean looksLikeLyric(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return false;
        }
        if (value.length() > MAX_TITLE_LENGTH) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            if (PUNCTUATION.indexOf(value.charAt(index)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the list without any recommendation block. The input is returned unchanged when there
     * is no marker, so callers can use it unconditionally.
     */
    static <T> List<T> strip(List<T> items, ToLongFunction<T> timeOf, Function<T, String> textOf) {
        if (items == null || items.size() < 2) {
            return items;
        }
        for (int index = 0; index < items.size(); index++) {
            if (!isMarker(textOf.apply(items.get(index)))) {
                continue;
            }
            long markerTime = timeOf.applyAsLong(items.get(index));
            int cursor = index + 1;
            while (cursor < items.size() && cursor - index <= MAX_BLOCK_LINES) {
                String text = textOf.apply(items.get(cursor));
                long time = timeOf.applyAsLong(items.get(cursor));
                if (looksLikeLyric(text)) {
                    break;
                }
                if (markerTime > 0L && time > 0L && time - markerTime > BLOCK_WINDOW_MS) {
                    break;
                }
                if (text == null || text.trim().isEmpty()) {
                    break;
                }
                cursor++;
            }
            List<T> cleaned = new ArrayList<>(items.size() - (cursor - index));
            cleaned.addAll(items.subList(0, index));
            cleaned.addAll(items.subList(cursor, items.size()));
            return strip(cleaned, timeOf, textOf);
        }
        return items;
    }
}
