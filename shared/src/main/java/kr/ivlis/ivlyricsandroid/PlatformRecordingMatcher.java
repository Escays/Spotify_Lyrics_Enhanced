package kr.ivlis.ivlyricsandroid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Layer one of the platform-translation lookup: decide which search result is the same recording as
 * the track Spotify is playing.
 *
 * <p>Title alone is not enough. A live take, a remaster, an acoustic re-recording and a character-song
 * credit all share a title with the studio release, and their line breaks and timings differ. The score
 * therefore combines title, artist, duration and album, and treats version tags as a veto rather than a
 * small bonus.
 */
final class PlatformRecordingMatcher {
    /** Candidates whose duration differs by more than this are a different recording. */
    static final long MAX_DURATION_DELTA_MS = 25_000L;
    static final double MIN_ACCEPT_SCORE = 0.62;

    /**
     * Version tags that change the audio itself. A candidate carrying one of these while the playing
     * track does not is a different recording the moment its length also disagrees.
     */
    private static final Set<String> STRICT_VERSION_TAGS = new LinkedHashSet<>(Arrays.asList(
            "live", "remix", "cover", "acoustic", "instrumental", "inst", "karaoke",
            "off vocal", "a cappella", "acapella", "demo", "session", "unplugged",
            "orchestral", "piano", "现场", "现场版", "演唱会", "翻唱", "伴奏",
            "纯音乐", "钢琴", "钢琴版", "ライブ", "カバー", "インスト", "歌ってみた",
            "オルゴール", "ピアノ"
    ));
    private static final long STRICT_DURATION_TOLERANCE_MS = 5_000L;

    private static final Set<String> VERSION_TAGS = new LinkedHashSet<>(Arrays.asList(
            "live", "remaster", "remastered", "acoustic", "instrumental", "inst", "remix", "demo",
            "cover", "karaoke", "off vocal", "a cappella", "acapella", "edit", "reprise", "mono",
            "stereo", "session", "unplugged", "orchestral", "piano", "8d", "sped up", "slowed",
            "现场", "现场版", "演唱会", "重制", "重置", "重錄", "重录", "翻唱", "伴奏", "纯音乐",
            "钢琴", "钢琴版", "加速", "降速", "ライブ", "リマスター", "カバー", "インスト", "歌ってみた",
            "オルゴール", "ピアノ"
    ));

    private PlatformRecordingMatcher() {
    }

    static final class Candidate {
        final String id;
        final String title;
        final String artist;
        final String album;
        final long durationMs;

        Candidate(String id, String title, String artist, String album, long durationMs) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.album = album == null ? "" : album;
            this.durationMs = Math.max(0L, durationMs);
        }
    }

    static final class Score {
        final Candidate candidate;
        final double total;
        final boolean durationVetoed;

        Score(Candidate candidate, double total, boolean durationVetoed) {
            this.candidate = candidate;
            this.total = total;
            this.durationVetoed = durationVetoed;
        }

        boolean acceptable() {
            return !durationVetoed && total >= MIN_ACCEPT_SCORE;
        }
    }

    /** Scores every candidate and returns them best first; rejected candidates stay for diagnostics. */
    static List<Score> rank(String title, String artist, String album, long durationMs,
            List<Candidate> candidates) {
        List<Score> scores = new ArrayList<>();
        if (candidates == null) {
            return scores;
        }
        for (Candidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            scores.add(score(title, artist, album, durationMs, candidate));
        }
        scores.sort((left, right) -> Double.compare(right.total, left.total));
        return scores;
    }

    static Score score(String title, String artist, String album, long durationMs, Candidate candidate) {
        double titleScore = titleSimilarity(title, candidate.title);
        double artistScore = artistSimilarity(artist, candidate.artist);
        double albumScore = album.isEmpty() || candidate.album.isEmpty()
                ? 0d
                : textualSimilarity(baseTitle(album), baseTitle(candidate.album));

        boolean durationKnown = durationMs > 0L && candidate.durationMs > 0L;
        long delta = durationKnown ? Math.abs(durationMs - candidate.durationMs) : 0L;
        boolean durationVetoed = durationKnown && delta > MAX_DURATION_DELTA_MS;
        if (durationKnown && !durationVetoed && delta > STRICT_DURATION_TOLERANCE_MS
                && hasStrictVersionTag(queryText(title, album), candidate.title + " " + candidate.album)) {
            // "Live at ..." against the studio release: same words, different performance.
            durationVetoed = true;
        }
        double durationScore = durationKnown ? durationScore(delta) : 0.5d;

        double total = (0.46d * titleScore) + (0.30d * artistScore) + (0.19d * durationScore)
                + (0.05d * albumScore);
        total -= tagPenalty(title + " " + album, candidate.title + " " + candidate.album);
        if (titleScore < 0.55d || artistScore < 0.40d) {
            total = Math.min(total, 0.30d);
        }
        return new Score(candidate, Math.max(0d, Math.min(1d, total)), durationVetoed);
    }

    private static String queryText(String title, String album) {
        return (title == null ? "" : title) + " " + (album == null ? "" : album);
    }

    /** True when only the candidate carries a version tag that changes the performance. */
    private static boolean hasStrictVersionTag(String queryText, String candidateText) {
        Set<String> queryTags = versionTags(queryText);
        for (String tag : versionTags(candidateText)) {
            if (STRICT_VERSION_TAGS.contains(tag) && !queryTags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private static double durationScore(long deltaMs) {
        if (deltaMs <= 3_000L) {
            return 1d;
        }
        if (deltaMs <= 8_000L) {
            return 0.78d;
        }
        if (deltaMs <= 15_000L) {
            return 0.5d;
        }
        return 0.15d;
    }

    /**
     * Version tags are asymmetric on purpose: the playing track asking for a live take and a studio
     * candidate is a mismatch, and a labelled candidate against a plain query is also weaker.
     */
    static double tagPenalty(String queryText, String candidateText) {
        Set<String> queryTags = versionTags(queryText);
        Set<String> candidateTags = versionTags(candidateText);
        if (queryTags.isEmpty() && candidateTags.isEmpty()) {
            return 0d;
        }
        double penalty = 0d;
        for (String tag : candidateTags) {
            if (!queryTags.contains(tag)) {
                penalty += 0.12d;
            }
        }
        for (String tag : queryTags) {
            if (!candidateTags.contains(tag)) {
                penalty += 0.16d;
            }
        }
        return Math.min(0.45d, penalty);
    }

    static Set<String> versionTags(String text) {
        Set<String> tags = new LinkedHashSet<>();
        String lower = ChineseLyricText.lower(text);
        if (lower.isEmpty()) {
            return tags;
        }
        for (String tag : VERSION_TAGS) {
            if (containsToken(lower, tag)) {
                tags.add(tag);
            }
        }
        // "2023 remaster" and "Live at ..." also read as a year plus a tag; keep the tag only.
        return tags;
    }

    private static boolean containsToken(String text, String token) {
        int index = text.indexOf(token);
        while (index >= 0) {
            int before = index - 1;
            int after = index + token.length();
            boolean beforeOk = before < 0 || !Character.isLetterOrDigit(text.codePointBefore(index));
            boolean afterOk = after >= text.length() || !Character.isLetterOrDigit(text.codePointAt(after));
            // CJK tags have no word boundaries; ASCII tags must not sit inside a longer word.
            if (beforeOk && afterOk || isCjk(token)) {
                return true;
            }
            index = text.indexOf(token, index + 1);
        }
        return false;
    }

    private static boolean isCjk(String token) {
        return ChineseLyricText.hanCount(token) > 0 || ChineseLyricText.hasKana(token);
    }

    /** The title without its parenthesised or bracketed decorations. */
    static String baseTitle(String text) {
        String value = text == null ? "" : text;
        StringBuilder builder = new StringBuilder(value.length());
        int depthRound = 0;
        int depthSquare = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '（' || codePoint == '(') {
                depthRound++;
                continue;
            }
            if (codePoint == ')' || codePoint == '）') {
                depthRound = Math.max(0, depthRound - 1);
                continue;
            }
            if (codePoint == '《' || codePoint == '【' || codePoint == '[') {
                depthSquare++;
                continue;
            }
            if (codePoint == '》' || codePoint == '】' || codePoint == ']') {
                depthSquare = Math.max(0, depthSquare - 1);
                continue;
            }
            if (depthRound == 0 && depthSquare == 0) {
                builder.appendCodePoint(codePoint);
            }
        }
        String stripped = builder.toString().trim();
        return stripped.isEmpty() ? (text == null ? "" : text.trim()) : stripped;
    }

    static double titleSimilarity(String left, String right) {
        double base = textualSimilarity(baseTitle(left), baseTitle(right));
        double full = textualSimilarity(left, right);
        double score = Math.max(base, full);
        // A decorated title still contains the plain one: "残酷な天使のテーゼ (残酷天使的行动纲领)".
        if (score < 0.75d
                && !baseTitle(left).isEmpty()
                && !baseTitle(right).isEmpty()
                && (ChineseLyricText.lower(right).contains(ChineseLyricText.lower(baseTitle(left)))
                || ChineseLyricText.lower(left).contains(ChineseLyricText.lower(baseTitle(right))))) {
            score = Math.max(score, 0.85d);
        }
        return score;
    }

    static double artistSimilarity(String left, String right) {
        List<String> leftTokens = artistTokens(left);
        List<String> rightTokens = artistTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0d;
        }
        double best = 0d;
        for (String leftToken : leftTokens) {
            for (String rightToken : rightTokens) {
                best = Math.max(best, textualSimilarity(leftToken, rightToken));
            }
        }
        if (best < 1d) {
            // "高橋洋子 (Yoko Takahashi)" against "高橋洋子": a shared leading name is enough.
            for (String leftToken : leftTokens) {
                for (String rightToken : rightTokens) {
                    String shortLeft = ChineseLyricText.lower(leftToken);
                    String shortRight = ChineseLyricText.lower(rightToken);
                    if (shortLeft.length() >= 2 && shortRight.contains(shortLeft)
                            || shortRight.length() >= 2 && shortLeft.contains(shortRight)) {
                        best = Math.max(best, 0.86d);
                    }
                }
            }
        }
        return best;
    }

    private static final String[] ARTIST_SEPARATORS = {
            "/", "、", "&", ",", "，", ";", "；", "feat.", "feat", "ft.", "ft", "with ", "×", " x "
    };

    static List<String> artistTokens(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return new ArrayList<>();
        }
        String normalized = baseTitle(value);
        for (String separator : ARTIST_SEPARATORS) {
            normalized = normalized.replace(separator, "\u0001");
        }
        List<String> tokens = new ArrayList<>();
        for (String part : normalized.split("\u0001")) {
            String token = part.trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** Dice coefficient over character bigrams on the comparison-normalised text. */
    static double textualSimilarity(String left, String right) {
        String a = squashed(left);
        String b = squashed(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        if (a.equals(b)) {
            return 1d;
        }
        if (a.length() == 1 || b.length() == 1) {
            return a.equals(b) ? 1d : 0d;
        }
        java.util.Map<String, Integer> bigrams = new java.util.HashMap<>();
        for (int index = 0; index + 1 < a.length(); index++) {
            String gram = a.substring(index, index + 2);
            bigrams.merge(gram, 1, Integer::sum);
        }
        int matches = 0;
        for (int index = 0; index + 1 < b.length(); index++) {
            String gram = b.substring(index, index + 2);
            Integer count = bigrams.get(gram);
            if (count != null && count > 0) {
                bigrams.put(gram, count - 1);
                matches++;
            }
        }
        return (2d * matches) / ((a.length() - 1) + (b.length() - 1));
    }

    private static String squashed(String text) {
        String normalized = LyricsTextComparison.normalize(text);
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            if (!Character.isWhitespace(value)) {
                builder.append(value);
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}
