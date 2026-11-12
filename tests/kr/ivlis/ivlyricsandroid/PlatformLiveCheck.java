package kr.ivlis.ivlyricsandroid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manual live check: runs the production NetEase, QQ and Genius clients against the real services and
 * prints what actually came back — the recording that was picked, whether a usable Chinese translation
 * or a Genius description/annotation exists, and the reason when it does not.
 *
 * <p>This is not a unit test and never invents a result: a blocked endpoint prints its failure.
 */
public final class PlatformLiveCheck {
    static final class Song {
        final String title;
        final String artist;
        final long durationMs;

        Song(String title, String artist, long durationMs) {
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
        }
    }

    public static void main(String[] args) {
        PlatformHttpTransport.Transport transport = PlatformHttpTransport.http();
        List<PlatformLyricSource> sources = Arrays.asList(
                new NeteaseLyricSource(transport),
                new QqLyricSource(transport));
        List<Song> songs = Arrays.asList(
                new Song("Shape of You", "Ed Sheeran", 233_712L),
                new Song("Faded", "Alan Walker", 212_000L),
                new Song("Yesterday Once More", "Carpenters", 233_000L),
                new Song("残酷な天使のテーゼ", "高橋洋子", 245_866L),
                new Song("Lemon", "米津玄師", 256_000L),
                new Song("夜に駆ける", "YOASOBI", 261_000L),
                new Song("Hallelujah", "Jeff Buckley", 413_000L),
                new Song("Lemon (Live)", "米津玄師", 248_000L),
                // 丸ノ内 on Spotify, 丸の内 on Genius: the particle spelling must not lose the page.
                new Song("丸ノ内サディスティック", "椎名林檎", 0L));

        System.out.println("song | platform | picked recording | duration | translation rows | readable");
        for (Song song : songs) {
            for (PlatformLyricSource source : sources) {
                reportSong(transport, source, song);
            }
        }

        System.out.println();
        System.out.println("song | genius recording | description chars | annotations | placed");
        for (Song song : songs) {
            reportGenius(transport, song);
        }
    }

    static void reportSong(PlatformHttpTransport.Transport transport, PlatformLyricSource source, Song song) {
        String prefix = song.title + " / " + song.artist + " | " + source.label() + " | ";
        try {
            List<PlatformRecordingMatcher.Candidate> candidates = new ArrayList<>();
            for (String query : PlatformRecordingMatcher.searchQueries(song.title, song.artist)) {
                candidates = source.search(query, 10);
                if (!candidates.isEmpty()) {
                    break;
                }
            }
            if (candidates.isEmpty()) {
                System.out.println(prefix + "(no search result) | - | - | -");
                return;
            }
            List<PlatformRecordingMatcher.Score> ranked = PlatformRecordingMatcher.rank(
                    song.title, song.artist, "", song.durationMs, candidates);
            PlatformRecordingMatcher.Score best = null;
            for (PlatformRecordingMatcher.Score score : ranked) {
                if (score.acceptable()) {
                    best = score;
                    break;
                }
            }
            if (best == null) {
                System.out.println(prefix + "(no acceptable recording; best="
                        + ranked.get(0).candidate.title + " score=" + round(ranked.get(0).total)
                        + (ranked.get(0).durationVetoed ? " duration-veto" : "") + ") | - | - | -");
                return;
            }
            PlatformLyricSource.PlatformLyricDocument document = source.fetch(best.candidate.id);
            int usable = 0;
            for (PlatformLyricRow row : document.translationRows) {
                if (ChineseLyricText.isUsable(row.text, "")) {
                    usable++;
                }
            }
            System.out.println(prefix + best.candidate.title + " | "
                    + (best.candidate.durationMs / 1000) + "s (delta "
                    + (Math.abs(song.durationMs - best.candidate.durationMs) / 1000) + "s) | "
                    + usable + "/" + document.translationRows.size()
                    + " (originals " + document.originalRows.size() + ") | "
                    + (usable > 0 ? "yes" : "no"));
        } catch (Exception error) {
            System.out.println(prefix + "FAILED: " + error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "" : error.getMessage()));
        }
    }

    static void reportGenius(PlatformHttpTransport.Transport transport, Song song) {
        String prefix = song.title + " / " + song.artist + " | ";
        GeniusClient client = new GeniusClient(transport);
        try {
            GeniusClient.Hit chosen = null;
            SongMeaning meaning = SongMeaning.EMPTY;
            String winningQuery = "";
            boolean anyHit = false;
            // Mirrors SongMeaningService: every query gets a turn until one yields a page with content.
            for (String query : PlatformRecordingMatcher.searchQueries(song.title, song.artist)) {
                List<GeniusClient.Hit> hits = client.search(query);
                anyHit |= !hits.isEmpty();
                GeniusClient.Hit candidate = SongMeaningService.chooseRecording(hits, song.title, song.artist);
                if (candidate == null) {
                    continue;
                }
                if (chosen == null) {
                    chosen = candidate;
                }
                SongMeaning fetched = client.fetch(candidate.id, GeniusClient.LABEL);
                if (!fetched.isEmpty()) {
                    chosen = candidate;
                    meaning = fetched;
                    winningQuery = query;
                    break;
                }
            }
            if (chosen == null) {
                System.out.println(prefix + (anyHit ? "(no acceptable genius hit)" : "(no genius hit)")
                        + " | - | - | -");
                return;
            }
            int quoted = 0;
            for (SongAnnotation annotation : meaning.allAnnotations()) {
                if (!annotation.fragment.isEmpty() && !annotation.body.isEmpty()) {
                    quoted++;
                }
            }
            // Placement needs the displayed lyrics, which this host has no access to; the count reported
            // here is how many annotations came back with both a quote and an explanation.
            System.out.println(prefix + chosen.title + " / " + chosen.artist + " | "
                    + meaning.description.length() + " | " + meaning.allAnnotations().size()
                    + " | quoted=" + quoted + " | query=" + winningQuery);
        } catch (Exception error) {
            System.out.println(prefix + "FAILED: " + error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "" : error.getMessage()));
        }
    }

    static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
