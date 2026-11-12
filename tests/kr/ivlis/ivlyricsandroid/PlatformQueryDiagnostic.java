package kr.ivlis.ivlyricsandroid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manual diagnostic for one track: what query the production source sends, which recordings come back,
 * how each one scores, whether it is accepted, and what the fetched rows actually contain.
 *
 * <p>It exists because "no translation" hid four different failures: no search hit, wrong recording,
 * no Chinese rows, and rows that could not be aligned. This prints which one it is.
 */
public final class PlatformQueryDiagnostic {
    public static void main(String[] args) {
        String title = args.length > 0 ? args[0] : "Supper's Ready - 2007 Stereo Mix";
        String artist = args.length > 1 ? args[1] : "Genesis";
        long durationMs = args.length > 2 ? Long.parseLong(args[2]) * 1000L : 1_386_000L;

        System.out.println("title  : [" + title + "]");
        System.out.println("artist : [" + artist + "]");
        System.out.println("duration: " + (durationMs / 1000) + "s");
        System.out.println("hypothesis searchTitle: [" + strippedTitle(title) + "]");
        System.out.println("baseTitle    : [" + PlatformRecordingMatcher.baseTitle(title) + "]");
        System.out.println("versionTags  : " + PlatformRecordingMatcher.versionTags(title + " " + artist));
        System.out.println();

        PlatformHttpTransport.Transport transport = PlatformHttpTransport.http();
        for (PlatformLyricSource source : Arrays.asList(
                new NeteaseLyricSource(transport), new QqLyricSource(transport),
                new KugouLyricSource(transport))) {
            inspect(source, title, artist, durationMs);
        }
        inspectGenius(transport, title, artist);
    }

    static void inspect(PlatformLyricSource source, String title, String artist, long durationMs) {
        System.out.println("===== " + source.label() + " =====");
        try {
            String query = PlatformRecordingMatcher.searchQueries(title, artist).get(0);
            System.out.println("  query=\"" + query + "\"");
            List<PlatformRecordingMatcher.Candidate> candidates =
                    source.search(query, 10);
            if (candidates.isEmpty()) {
                System.out.println("  no search result at all");
                return;
            }
            List<PlatformRecordingMatcher.Score> ranked =
                    PlatformRecordingMatcher.rank(title, artist, "", durationMs, candidates);
            PlatformRecordingMatcher.Score best = null;
            for (int index = 0; index < ranked.size(); index++) {
                PlatformRecordingMatcher.Score score = ranked.get(index);
                System.out.println(String.format(java.util.Locale.ROOT,
                        "  #%d %-52s | %-28s | %4ds | score=%.2f%s%s",
                        index + 1,
                        trim(score.candidate.title, 52),
                        trim(score.candidate.artist, 28),
                        score.candidate.durationMs / 1000,
                        score.total,
                        score.acceptable() ? " ACCEPT" : "",
                        score.durationVetoed ? " VETO" : ""));
                if (best == null && score.acceptable()) {
                    best = score;
                }
            }
            if (best == null) {
                System.out.println("  -> no acceptable recording");
                return;
            }
            PlatformLyricSource.PlatformLyricDocument document = source.fetch(best.candidate.id);
            int usable = 0;
            int usableChinese = 0;
            List<String> samples = new ArrayList<>();
            for (PlatformLyricRow row : document.translationRows) {
                if (!row.hasText()) {
                    continue;
                }
                if (!ChineseLyricText.isPlaceholder(row.text)) {
                    usable++;
                }
                if (ChineseLyricText.isUsable(row.text, "")) {
                    usableChinese++;
                    if (samples.size() < 3) {
                        samples.add(row.text);
                    }
                }
            }
            System.out.println("  -> picked " + best.candidate.title + " / " + best.candidate.artist);
            System.out.println("     original rows=" + document.originalRows.size()
                    + " translation rows=" + document.translationRows.size()
                    + " non-placeholder=" + usable + " usable=" + usableChinese);
            System.out.println("     samples=" + samples);

            List<String> base = new ArrayList<>();
            for (PlatformLyricRow row : document.originalRows) {
                if (row.hasText()) {
                    base.add(row.text);
                }
            }
            PlatformTranslationAligner.Result aligned =
                    PlatformTranslationAligner.align(base, document.originalRows, document.translationRows);
            System.out.println("     self-align (platform original vs its own translation): "
                    + aligned.translatedLines + "/" + aligned.translatableLines);
        } catch (Exception error) {
            System.out.println("  FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        System.out.println();
    }

    /** The query the current matcher would need: drop the " - version" tail for recall only. */
    static String strippedTitle(String value) {
        String text = value == null ? "" : value;
        int dash = text.indexOf(" - ");
        return dash > 0 ? text.substring(0, dash).trim() : text;
    }

    static void inspectGenius(PlatformHttpTransport.Transport transport, String title, String artist) {
        System.out.println("===== Genius =====");
        GeniusClient client = new GeniusClient(transport);
        try {
            String query = PlatformRecordingMatcher.searchQueries(title, artist).get(0);
            List<GeniusClient.Hit> hits = client.search(query);
            System.out.println("  query=\"" + query + "\" hits=" + hits.size());
            for (int index = 0; index < Math.min(6, hits.size()); index++) {
                GeniusClient.Hit hit = hits.get(index);
                System.out.println(String.format(java.util.Locale.ROOT,
                        "  #%d %-56s | %-24s | titleScore=%.2f artistScore=%.2f",
                        index + 1, trim(hit.title, 56), trim(hit.artist, 24),
                        PlatformRecordingMatcher.titleSimilarity(title, hit.title),
                        PlatformRecordingMatcher.artistSimilarity(artist, hit.artist)));
            }
            GeniusClient.Hit chosen = null;
            for (String variant : PlatformRecordingMatcher.searchQueries(title, artist)) {
                chosen = SongMeaningService.chooseRecording(client.search(variant), title, artist);
                if (chosen != null) {
                    System.out.println("  matched with query=\"" + variant + "\"");
                    break;
                }
            }
            if (chosen == null) {
                System.out.println("  -> NO acceptable Genius hit");
                return;
            }
            System.out.println("  -> chose " + chosen.title + " / " + chosen.artist);
            SongMeaning meaning = client.fetch(chosen.id, GeniusClient.LABEL);
            System.out.println("     description=" + meaning.description.length() + " chars"
                    + " annotations=" + meaning.allAnnotations().size()
                    + " placed=" + meaning.placedAnnotations.size());
            for (int index = 0; index < Math.min(3, meaning.allAnnotations().size()); index++) {
                SongAnnotation annotation = meaning.allAnnotations().get(index);
                System.out.println("     [" + index + "] fragment=" + trim(annotation.fragment, 60));
            }
        } catch (Exception error) {
            System.out.println("  FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        System.out.println();
    }

    static String trim(String value, int length) {
        String text = value == null ? "" : value;
        return text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }
}
