package kr.ivlis.ivlyricsandroid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Places Genius annotation fragments on the displayed lyric lines.
 *
 * <p>Fragments are quoted from Genius' own lyric text, in English or Japanese, and described with
 * punctuation, full-width characters and line breaks that the display may not share. Matching therefore
 * compares comparison-normalised text (NFKC, case, punctuation removed, spaces optional), tries windows of
 * one to four consecutive lines for fragments that span rows, and accepts a fragment on every window it
 * matches so a repeated chorus is underlined each time it appears. A fragment too short to be
 * unambiguous is left unplaced instead of being attached to the wrong line.
 */
final class AnnotationLineMapper {
    private static final int MAX_WINDOW_LINES = 4;
    private static final int MAX_PLACEMENTS = 8;
    private static final int MIN_FUZZY_LENGTH = 12;
    private static final int MIN_UNAMBIGUOUS_LENGTH = 8;
    private static final double FUZZY_THRESHOLD = 0.82d;

    private AnnotationLineMapper() {
    }

    static final class Result {
        final List<SongAnnotation> placed;
        final List<SongAnnotation> unplaced;

        Result(List<SongAnnotation> placed, List<SongAnnotation> unplaced) {
            this.placed = Collections.unmodifiableList(new ArrayList<>(placed));
            this.unplaced = Collections.unmodifiableList(new ArrayList<>(unplaced));
        }
    }

    static Result map(List<String> baseTexts, List<SongAnnotation> annotations) {
        List<SongAnnotation> placed = new ArrayList<>();
        List<SongAnnotation> unplaced = new ArrayList<>();
        if (baseTexts == null || baseTexts.isEmpty() || annotations == null || annotations.isEmpty()) {
            return new Result(Collections.emptyList(), annotations == null
                    ? Collections.emptyList() : annotations);
        }
        List<String> flat = new ArrayList<>(baseTexts.size());
        for (String text : baseTexts) {
            flat.add(squash(text));
        }
        for (SongAnnotation annotation : annotations) {
            if (annotation == null || annotation.fragment.isEmpty()) {
                continue;
            }
            List<int[]> ranges = locate(flat, annotation.fragment);
            if (ranges.isEmpty()) {
                unplaced.add(annotation);
                continue;
            }
            List<Integer> lines = new ArrayList<>();
            for (int[] range : ranges) {
                for (int index = range[0]; index <= range[1]; index++) {
                    if (!lines.contains(index)) {
                        lines.add(index);
                    }
                }
            }
            Collections.sort(lines);
            placed.add(annotation.withLines(lines));
        }
        return new Result(placed, unplaced);
    }

    /** Line ranges the fragment sits on ({start, endInclusive}), empty when it cannot be placed reliably. */
    static List<int[]> locate(List<String> squashedLines, String fragment) {
        String target = squash(fragment);
        if (target.isEmpty() || squashedLines == null || squashedLines.isEmpty()) {
            return Collections.emptyList();
        }
        List<int[]> exact = new ArrayList<>();
        for (int start = 0; start < squashedLines.size(); start++) {
            StringBuilder window = new StringBuilder();
            for (int length = 1; length <= MAX_WINDOW_LINES && start + length <= squashedLines.size(); length++) {
                window.append(squashedLines.get(start + length - 1));
                if (window.length() < target.length()) {
                    continue;
                }
                if (window.toString().equals(target)) {
                    exact.add(new int[]{start, start + length - 1});
                    break;
                }
                if (window.length() > target.length()) {
                    break;
                }
            }
            if (exact.size() > MAX_PLACEMENTS) {
                break;
            }
        }
        if (!exact.isEmpty()) {
            if (target.length() < MIN_UNAMBIGUOUS_LENGTH && exact.size() > 2) {
                // "Oh" or "Yeah" appears everywhere; guessing would underline the wrong line.
                return Collections.emptyList();
            }
            return exact;
        }

        if (target.length() < MIN_FUZZY_LENGTH) {
            return Collections.emptyList();
        }
        List<int[]> fuzzy = new ArrayList<>();
        for (int start = 0; start < squashedLines.size(); start++) {
            StringBuilder window = new StringBuilder();
            for (int length = 1; length <= MAX_WINDOW_LINES && start + length <= squashedLines.size(); length++) {
                window.append(squashedLines.get(start + length - 1));
                double score = PlatformRecordingMatcher.textualSimilarity(window.toString(), target);
                if (score >= FUZZY_THRESHOLD) {
                    fuzzy.add(new int[]{start, start + length - 1});
                    break;
                }
            }
            if (fuzzy.size() > MAX_PLACEMENTS) {
                break;
            }
        }
        return fuzzy;
    }

    /** Comparison form: NFKC, lower case, punctuation dropped, no whitespace. */
    static String squash(String text) {
        String normalized = LyricsTextComparison.normalize(text);
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            if (!Character.isWhitespace(value)) {
                builder.append(value);
            }
        }
        return builder.toString();
    }
}
