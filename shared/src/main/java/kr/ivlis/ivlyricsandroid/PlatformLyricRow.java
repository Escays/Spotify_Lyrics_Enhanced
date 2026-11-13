package kr.ivlis.ivlyricsandroid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One timed lyric row as a platform (NetEase, QQ) returns it, kept separate from {@link LyricsLine}
 * so the platform text can be matched before it is merged into the displayed lyrics.
 */
final class PlatformLyricRow {
    private static final Pattern TIME_TAG_PATTERN =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern METADATA_TAG_PATTERN =
            Pattern.compile("^\\s*\\[(?:ti|ar|al|by|offset|re|ve|length|kana|au):[^]]*]\\s*$",
                    Pattern.CASE_INSENSITIVE);

    final long startTimeMs;
    final String text;

    PlatformLyricRow(long startTimeMs, String text) {
        this.startTimeMs = Math.max(0L, startTimeMs);
        this.text = text == null ? "" : text.trim();
    }

    boolean hasText() {
        return !text.isEmpty();
    }

    /**
     * Parses an LRC body. Rows without a timestamp are dropped, metadata tags are dropped, and a
     * timestamp carrying several rows produces one row per text, as LRC allows.
     */
    static List<PlatformLyricRow> parseLrc(String lrc) {
        if (lrc == null || lrc.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<PlatformLyricRow> rows = new ArrayList<>();
        for (String rawLine : lrc.split("\\r?\\n")) {
            if (rawLine.trim().isEmpty() || METADATA_TAG_PATTERN.matcher(rawLine).matches()) {
                continue;
            }
            Matcher matcher = TIME_TAG_PATTERN.matcher(rawLine);
            List<Long> stamps = new ArrayList<>(2);
            int consumed = 0;
            while (matcher.find() && matcher.start() == consumed) {
                stamps.add(toMillis(matcher.group(1), matcher.group(2), matcher.group(3)));
                consumed = matcher.end();
            }
            if (stamps.isEmpty()) {
                continue;
            }
            String text = rawLine.substring(consumed).trim();
            for (long stamp : stamps) {
                rows.add(new PlatformLyricRow(stamp, text));
            }
        }
        // A "You might also like" block is another song's titles, not lyrics: drop it before it can be
        // aligned onto the displayed lines.
        return LyricAdBlockFilter.strip(rows, row -> row.startTimeMs, row -> row.text);
    }

    /** Rows whose text repeats a credit or a platform notice are not lyric rows. */
    static boolean isCreditRow(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("作词") || lower.startsWith("作曲") || lower.startsWith("编曲")
                || lower.startsWith("制作人") || lower.startsWith("词：") || lower.startsWith("曲：")
                || lower.startsWith("词:") || lower.startsWith("曲:") || lower.startsWith("op：")
                || lower.startsWith("op:") || lower.startsWith("出品") || lower.startsWith("监制")
                || lower.startsWith("混音") || lower.startsWith("母带")
                || lower.startsWith("lyrics by") || lower.startsWith("composed by")
                || lower.startsWith("produced by") || lower.startsWith("written by")
                || lower.startsWith("arranged by") || lower.startsWith("mixed by")
                || trimmed.contains("享有本翻译作品") || trimmed.contains("翻译作品著作权")
                || trimmed.contains("本歌曲歌词翻译") || trimmed.contains("以下歌词翻译");
    }

    private static long toMillis(String minutes, String seconds, String fraction) {
        long value = parse(minutes) * 60_000L + parse(seconds) * 1_000L;
        if (fraction != null && !fraction.isEmpty()) {
            long scaled;
            if (fraction.length() == 1) {
                scaled = parse(fraction) * 100L;
            } else if (fraction.length() == 2) {
                scaled = parse(fraction) * 10L;
            } else {
                scaled = parse(fraction.substring(0, 3));
            }
            value += scaled;
        }
        return value;
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            return 0L;
        }
    }
}
