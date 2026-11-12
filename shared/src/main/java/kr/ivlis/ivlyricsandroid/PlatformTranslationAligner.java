package kr.ivlis.ivlyricsandroid;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer two of the platform-translation lookup: put an external Chinese translation back onto the
 * lines the renderer is currently showing.
 *
 * <p>Row numbers are not a contract. A platform may split one displayed line into two rows, join two
 * displayed lines into one, drop instrumental rows entirely and repeat a chorus with different
 * timestamps. The mapping is therefore solved as a monotone alignment that may consume several rows of
 * one side at a time, scored on normalised text and only accepted above a similarity floor. Timestamps
 * decide nothing on their own; they are used only to pair a translated row with its own original row.
 */
final class PlatformTranslationAligner {
    private static final double MIN_PAIR_SCORE = 0.45d;
    private static final double MIN_GROUP_SCORE = 0.50d;
    private static final long TRANSLATION_TIME_WINDOW_MS = 1_500L;

    private PlatformTranslationAligner() {
    }

    static final class Result {
        /** Aligned to the base line list; empty when the line has no usable platform translation. */
        final List<String> translations;
        final int translatedLines;
        final int translatableLines;
        /** Base lines that matched a platform row at all, whether or not that row was translated. */
        final int mappedLines;

        Result(List<String> translations, int translatedLines, int translatableLines, int mappedLines) {
            this.translations = translations;
            this.translatedLines = translatedLines;
            this.translatableLines = translatableLines;
            this.mappedLines = mappedLines;
        }

        boolean hasAny() {
            return translatedLines > 0;
        }

        double coverage() {
            return translatableLines == 0 ? 0d : (double) translatedLines / translatableLines;
        }

        /** Low coverage of the originals themselves means a different recording, not a missing translation. */
        double originalCoverage() {
            return translatableLines == 0 ? 0d : (double) mappedLines / translatableLines;
        }
    }

    /**
     * @param baseTexts      the displayed original lines, in display order
     * @param externalRows   the platform's own original rows, timestamped
     * @param translatedRows the platform's Chinese rows, timestamped (may be empty or partial)
     */
    static Result align(List<String> baseTexts, List<PlatformLyricRow> externalRows,
            List<PlatformLyricRow> translatedRows) {
        List<String> base = baseTexts == null ? new ArrayList<>() : baseTexts;
        List<String> output = new ArrayList<>(base.size());
        for (int index = 0; index < base.size(); index++) {
            output.add("");
        }
        List<PlatformLyricRow> originals = externalRows == null ? new ArrayList<>() : externalRows;
        if (base.isEmpty() || originals.isEmpty()) {
            return new Result(output, 0, 0, 0);
        }

        List<Integer> translatable = new ArrayList<>();
        for (int index = 0; index < base.size(); index++) {
            if (isTranslatable(base.get(index))) {
                translatable.add(index);
            }
        }
        if (translatable.isEmpty()) {
            return new Result(output, 0, 0, 0);
        }

        String[] translatedByRow = pairTranslations(originals, translatedRows);
        int baseCount = translatable.size();
        int rowCount = originals.size();

        double[][] dp = new double[baseCount + 1][rowCount + 1];
        byte[][] step = new byte[baseCount + 1][rowCount + 1];
        for (int i = 1; i <= baseCount; i++) {
            // A displayed line with no counterpart is allowed and costs nothing.
            dp[i][0] = 0d;
            step[i][0] = STEP_BASE_ONLY;
        }
        for (int j = 1; j <= rowCount; j++) {
            dp[0][j] = 0d;
            step[0][j] = STEP_ROW_ONLY;
        }

        for (int i = 1; i <= baseCount; i++) {
            String current = base.get(translatable.get(i - 1));
            for (int j = 1; j <= rowCount; j++) {
                double best = dp[i - 1][j];
                byte bestStep = STEP_BASE_ONLY;

                double pair = pairScore(current, originals.get(j - 1));
                if (pair >= MIN_PAIR_SCORE && dp[i - 1][j - 1] + pair > best) {
                    best = dp[i - 1][j - 1] + pair;
                    bestStep = STEP_ONE_ONE;
                }
                if (j >= 2) {
                    String joined = join(originals.get(j - 2), originals.get(j - 1));
                    double group = scoreAgainst(current, joined);
                    if (group >= MIN_GROUP_SCORE && dp[i - 1][j - 2] + group > best) {
                        best = dp[i - 1][j - 2] + group;
                        bestStep = STEP_ONE_TWO;
                    }
                }
                if (i >= 2) {
                    String previous = base.get(translatable.get(i - 2));
                    String joinedBase = joinText(previous, current);
                    double group = scoreAgainst(joinedBase, originals.get(j - 1).text);
                    if (group >= MIN_GROUP_SCORE && dp[i - 2][j - 1] + group > best) {
                        best = dp[i - 2][j - 1] + group;
                        bestStep = STEP_TWO_ONE;
                    }
                }
                dp[i][j] = best;
                step[i][j] = bestStep;
            }
        }

        int i = baseCount;
        int j = rowCount;
        int mapped = 0;
        while (i > 0 || j > 0) {
            byte current = i == 0 ? STEP_ROW_ONLY : j == 0 ? STEP_BASE_ONLY : step[i][j];
            switch (current) {
                case STEP_ONE_ONE:
                    apply(output, translatable, i - 1, i - 1, translatedByRow, j - 1, j - 1, base);
                    mapped++;
                    i--;
                    j--;
                    break;
                case STEP_ONE_TWO:
                    apply(output, translatable, i - 1, i - 1, translatedByRow, j - 2, j - 1, base);
                    mapped++;
                    i--;
                    j -= 2;
                    break;
                case STEP_TWO_ONE:
                    apply(output, translatable, i - 2, i - 1, translatedByRow, j - 1, j - 1, base);
                    mapped += 2;
                    i -= 2;
                    j--;
                    break;
                case STEP_ROW_ONLY:
                    j--;
                    break;
                default:
                    i--;
                    break;
            }
        }

        int translated = 0;
        for (String value : output) {
            if (!value.isEmpty()) {
                translated++;
            }
        }
        return new Result(output, translated, translatable.size(), mapped);
    }

    private static final byte STEP_BASE_ONLY = 0;
    private static final byte STEP_ROW_ONLY = 1;
    private static final byte STEP_ONE_ONE = 2;
    private static final byte STEP_ONE_TWO = 3;
    private static final byte STEP_TWO_ONE = 4;

    /**
     * Writes the translation of external rows {@code rowFrom..rowTo} onto base lines
     * {@code baseFrom..baseTo}. Several external rows are joined; several base lines share the
     * translation by character count instead of repeating it on every line.
     */
    private static void apply(List<String> output, List<Integer> translatable, int baseFrom, int baseTo,
            String[] translatedByRow, int rowFrom, int rowTo, List<String> base) {
        List<String> parts = new ArrayList<>();
        for (int row = rowFrom; row <= rowTo; row++) {
            String value = translatedByRow[row];
            if (value != null && !value.trim().isEmpty()) {
                parts.add(value.trim());
            }
        }
        if (parts.isEmpty()) {
            return;
        }
        int baseLines = baseTo - baseFrom + 1;
        if (baseLines == 1) {
            output.set(translatable.get(baseFrom), String.join(" ", parts));
            return;
        }
        String joined = String.join(" ", parts);
        List<Integer> weights = new ArrayList<>(baseLines);
        for (int index = baseFrom; index <= baseTo; index++) {
            weights.add(Math.max(1, ChineseLyricText.nonSpaceLength(base.get(translatable.get(index)))));
        }
        List<String> split = splitByWeight(joined, weights);
        for (int index = 0; index < baseLines; index++) {
            output.set(translatable.get(baseFrom + index), split.get(index));
        }
    }

    /**
     * Pairs each external original row with a Chinese row. Same-index pairing is used only when the two
     * lists line up in time; otherwise the nearest timestamp inside the window wins.
     */
    /**
     * Pairs every external original row with the Chinese row written for it.
     *
     * <p>A translated LRC row carries the timestamp of the row it translates, so an identical
     * timestamp is the only evidence used when it exists. Nearest-in-window pairing is a last resort
     * for platforms that re-time their translation; it never overrides an exact match, because that is
     * how a translation ends up one line above its own lyric.
     */
    static String[] pairTranslations(List<PlatformLyricRow> originals, List<PlatformLyricRow> translated) {
        String[] result = new String[originals.size()];
        if (translated == null || translated.isEmpty()) {
            return result;
        }
        boolean[] used = new boolean[translated.size()];
        int exact = pairByExactTime(originals, translated, used, result);
        if (exact > 0) {
            return result;
        }
        if (translated.size() == originals.size()) {
            int positional = pairByPosition(originals, translated, result);
            if (positional * 10 >= originals.size() * 6) {
                return result;
            }
            clear(result);
        }
        pairByNearestTime(originals, translated, used, result);
        return result;
    }

    private static int pairByExactTime(List<PlatformLyricRow> originals, List<PlatformLyricRow> translated,
            boolean[] used, String[] result) {
        int matches = 0;
        for (int index = 0; index < originals.size(); index++) {
            for (int candidate = 0; candidate < translated.size(); candidate++) {
                if (used[candidate]) {
                    continue;
                }
                if (translated.get(candidate).startTimeMs != originals.get(index).startTimeMs) {
                    continue;
                }
                used[candidate] = true;
                if (ChineseLyricText.isUsable(translated.get(candidate).text, originals.get(index).text)) {
                    result[index] = translated.get(candidate).text.trim();
                    matches++;
                }
                break;
            }
        }
        return matches;
    }

    private static int pairByPosition(List<PlatformLyricRow> originals, List<PlatformLyricRow> translated,
            String[] result) {
        int matches = 0;
        for (int index = 0; index < originals.size(); index++) {
            if (Math.abs(originals.get(index).startTimeMs - translated.get(index).startTimeMs)
                    > TRANSLATION_TIME_WINDOW_MS) {
                continue;
            }
            if (ChineseLyricText.isUsable(translated.get(index).text, originals.get(index).text)) {
                result[index] = translated.get(index).text.trim();
                matches++;
            }
        }
        return matches;
    }

    private static void pairByNearestTime(List<PlatformLyricRow> originals, List<PlatformLyricRow> translated,
            boolean[] used, String[] result) {
        for (int index = 0; index < originals.size(); index++) {
            long target = originals.get(index).startTimeMs;
            int bestIndex = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int candidate = 0; candidate < translated.size(); candidate++) {
                if (used[candidate]) {
                    continue;
                }
                long distance = Math.abs(translated.get(candidate).startTimeMs - target);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = candidate;
                }
            }
            if (bestIndex < 0 || bestDistance > TRANSLATION_TIME_WINDOW_MS) {
                continue;
            }
            used[bestIndex] = true;
            if (ChineseLyricText.isUsable(translated.get(bestIndex).text, originals.get(index).text)) {
                result[index] = translated.get(bestIndex).text.trim();
            }
        }
    }

    private static void clear(String[] values) {
        for (int index = 0; index < values.length; index++) {
            values[index] = null;
        }
    }
    private static boolean isTranslatable(String text) {
        String value = text == null ? "" : text.trim();
        return !value.isEmpty()
                && !ChineseLyricText.isPlaceholder(value)
                && !PlatformLyricRow.isCreditRow(value);
    }

    private static double pairScore(String baseText, PlatformLyricRow row) {
        if (row == null || !row.hasText()) {
            return 0d;
        }
        return scoreAgainst(baseText, row.text);
    }

    private static double scoreAgainst(String baseText, String externalText) {
        String base = baseText == null ? "" : baseText.trim();
        String external = externalText == null ? "" : externalText.trim();
        if (base.isEmpty() || external.isEmpty()) {
            return 0d;
        }
        return PlatformRecordingMatcher.textualSimilarity(base, external);
    }

    private static String join(PlatformLyricRow left, PlatformLyricRow right) {
        return joinText(left == null ? "" : left.text, right == null ? "" : right.text);
    }

    private static String joinText(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return a + " " + b;
    }

    /** Splits one translation across several displayed lines by their source length. */
    static List<String> splitByWeight(String text, List<Integer> weights) {
        List<String> parts = new ArrayList<>();
        String value = text == null ? "" : text.trim();
        if (weights == null || weights.isEmpty()) {
            parts.add(value);
            return parts;
        }
        int totalWeight = 0;
        for (int weight : weights) {
            totalWeight += Math.max(1, weight);
        }
        int length = value.length();
        int cursor = 0;
        for (int index = 0; index < weights.size(); index++) {
            int remaining = weights.size() - index - 1;
            if (remaining == 0) {
                parts.add(value.substring(cursor).trim());
                break;
            }
            int share = Math.max(1, (int) Math.round((double) length * Math.max(1, weights.get(index)) / totalWeight));
            int end = Math.min(length - remaining, cursor + share);
            if (end <= cursor) {
                end = Math.min(length, cursor + 1);
            }
            parts.add(value.substring(cursor, end).trim());
            cursor = end;
        }
        while (parts.size() < weights.size()) {
            parts.add("");
        }
        return parts;
    }
}
