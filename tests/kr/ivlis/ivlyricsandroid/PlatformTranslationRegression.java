package kr.ivlis.ivlyricsandroid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-side checks for the two-layer platform translation lookup, the merge order and the Genius
 * annotation placement. Everything runs against synthetic payloads; no service is contacted.
 */
public final class PlatformTranslationRegression {
    static int checks;

    static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
        checks++;
    }

    /** A platform that answers from canned data, so the matching logic is what gets exercised. */
    static final class FakeSource implements PlatformLyricSource {
        final String id;
        final String label;
        final List<PlatformRecordingMatcher.Candidate> candidates = new ArrayList<>();
        final Map<String, PlatformLyricDocument> documents = new HashMap<>();
        final List<String> queries = new ArrayList<>();
        int searchCount;
        int fetchCount;
        Runnable onSearch;

        FakeSource(String id, String label) {
            this.id = id;
            this.label = label;
        }

        FakeSource candidate(String id, String title, String artist, String album, long durationMs,
                String originalLrc, String translatedLrc) {
            candidates.add(new PlatformRecordingMatcher.Candidate(id, title, artist, album, durationMs));
            documents.put(id, new PlatformLyricDocument(
                    this.id, label, id, title, artist, album, durationMs,
                    PlatformLyricRow.parseLrc(originalLrc),
                    PlatformLyricRow.parseLrc(translatedLrc),
                    false));
            return this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public List<PlatformRecordingMatcher.Candidate> search(String query, int limit) {
            searchCount++;
            queries.add(query);
            if (onSearch != null) {
                onSearch.run();
            }
            return candidates;
        }

        @Override
        public PlatformLyricDocument fetch(String candidateId) {
            fetchCount++;
            return documents.get(candidateId);
        }
    }

    static final String SHAPE_LRC = String.join("\n", Arrays.asList(
            "[00:09.48]The club isn't the best place to find a lover",
            "[00:11.75]So the bar is where I go",
            "[00:14.59]Me and my friends at the table doing shots",
            "[00:16.76]Drinking fast and then we talk slow"
    ));

    static final String SHAPE_ZH = String.join("\n", Arrays.asList(
            "[00:09.48]夜店不是寻找情人的好地方",
            "[00:11.75]所以我去了酒吧",
            "[00:14.59]我和我朋友们在桌前干着杯",
            "[00:16.76]一饮而尽 再缓缓诉起衷肠"
    ));

    static void searchVariantsNormaliseVersionTails() {
        check(PlatformRecordingMatcher.searchTitle("Supper's Ready - 2007 Stereo Mix")
                .equals("Supper's Ready"), "dash version tail removed for search");
        check(PlatformRecordingMatcher.searchTitle("Sound and Vision - 2017 Remaster")
                .equals("Sound and Vision"), "remaster tail removed");
        check(PlatformRecordingMatcher.searchTitle("Lemon (Live)").equals("Lemon"), "live parenthetical removed");
        check(PlatformRecordingMatcher.searchTitle("残酷な天使のテーゼ (残酷天使的行动纲领)")
                .contains("残酷な天使のテーゼ"), "alias parenthetical kept");
        check(PlatformRecordingMatcher.searchTitle("Don\u2019t Stop Me Now").equals("Don't Stop Me Now"),
                "curly apostrophe normalised");
        List<String> queries = PlatformRecordingMatcher.searchQueries(
                "Sound and Vision - 2017 Remaster", "David Bowie");
        check(queries.get(0).equals("Sound and Vision David Bowie"), "most specific query first");
        check(queries.size() >= 2, "title-only fallback present");

        // The version tail still decides the recording even though the search dropped it.
        List<PlatformRecordingMatcher.Candidate> candidates = Arrays.asList(
                new PlatformRecordingMatcher.Candidate("studio", "Sound and Vision", "David Bowie", "", 205_000L),
                new PlatformRecordingMatcher.Candidate("live", "Sound and Vision (Live)", "David Bowie", "", 260_000L));
        List<PlatformRecordingMatcher.Score> ranked = PlatformRecordingMatcher.rank(
                "Sound and Vision - 2017 Remaster", "David Bowie", "", 205_000L, candidates);
        check(ranked.get(0).candidate.id.equals("studio"), "studio wins after the tail is only used for filtering");
    }

    static void sourceWithoutChineseDoesNotBlockTheNextOne() {
        FakeSource netease = new FakeSource(NeteaseLyricSource.ID, NeteaseLyricSource.LABEL)
                .candidate("a", "No Chinese", "Artist", "", 200_000L,
                        "[00:01.00]first line\n[00:02.00]second line", "");
        FakeSource qq = new FakeSource(QqLyricSource.ID, QqLyricSource.LABEL)
                .candidate("b", "No Chinese", "Artist", "", 200_000L,
                        "[00:01.00]first line\n[00:02.00]second line",
                        "[00:01.00]第一行\n[00:02.00]第二行");
        PlatformTranslationService service = new PlatformTranslationService(Arrays.asList(netease, qq));
        PlatformTranslationService.Snapshot snapshot = lookup(service, "spotify:track:b",
                "No Chinese", "Artist", 200_000L, Arrays.asList("first line", "second line"));
        check(snapshot.translatedLines == 2, "second source filled every line");
        PlatformTranslationService.SourceStatus first = snapshot.statusOf(NeteaseLyricSource.ID);
        check(first != null && first.state.equals(PlatformTranslationService.SourceStatus.STATE_NO_CHINESE),
                "first source reports a matched recording without Chinese");
        check(first.matched(), "a matched recording is distinguishable from no result");
        PlatformTranslationService.SourceStatus second = snapshot.statusOf(QqLyricSource.ID);
        check(second != null && second.contributedLines == 2, "contribution count is per source");
    }

    public static void main(String[] args) {
        searchVariantsNormaliseVersionTails();
        sourceWithoutChineseDoesNotBlockTheNextOne();
        englishSongUsesPlatformTranslation();
        japaneseCharacterSongMatchesDecoratedArtist();
        liveVersionLosesToStudioRecording();
        differentLineSplitsAreJoined();
        repeatedChorusKeepsBothOccurrences();
        partialCoverageFallsBackToTheSecondPlatform();
        unusableRowsAreRejected();
        switchingTheOriginalSourceRealigns();
        lateAnswerForThePreviousTrackIsDropped();
        geniusPlacementHandlesFragments();
        geniusWithoutDataStaysEmpty();
        System.out.println("PlatformTranslationRegression: " + checks + " checks passed");
    }

    static PlatformTranslationService.Snapshot lookup(
            PlatformTranslationService service,
            String trackKey,
            String title,
            String artist,
            long durationMs,
            List<String> baseTexts
    ) {
        return service.lookup(trackKey, title, artist, "", durationMs, baseTexts, "zh-CN", false);
    }

    static void englishSongUsesPlatformTranslation() {
        FakeSource netease = new FakeSource(NeteaseLyricSource.ID, NeteaseLyricSource.LABEL)
                .candidate("451703096", "Shape of You", "Ed Sheeran", "Shape of You", 233_712L,
                        SHAPE_LRC, SHAPE_ZH);
        PlatformTranslationService service = new PlatformTranslationService(Arrays.asList(netease));
        List<String> base = Arrays.asList(
                "The club isn't the best place to find a lover",
                "So the bar is where I go",
                "Me and my friends at the table doing shots",
                "Drinking fast and then we talk slow");
        PlatformTranslationService.Snapshot snapshot =
                lookup(service, "spotify:track:1", "Shape of You", "Ed Sheeran", 233_000L, base);
        check(snapshot.hasAny(), "English song translated from the platform");
        check(snapshot.translatedLines == 4, "every line translated");
        check(snapshot.providerLabel.equals(NeteaseLyricSource.LABEL), "one platform is credited alone");
        check(snapshot.lineTranslations.get(0).equals("夜店不是寻找情人的好地方"), "first line text");
        check(snapshot.lineSources.get(1).equals(NeteaseLyricSource.ID), "per-line source recorded");

        PlatformTranslationService.Snapshot again =
                lookup(service, "spotify:track:1", "Shape of You", "Ed Sheeran", 233_000L, base);
        check(again.translatedLines == 4, "cached answer reused");
        check(netease.searchCount == 1, "cache avoids a second search");
    }

    static void japaneseCharacterSongMatchesDecoratedArtist() {
        FakeSource qq = new FakeSource(QqLyricSource.ID, QqLyricSource.LABEL)
                .candidate("003cXP4n4TXFUp", "残酷な天使のテーゼ (残酷天使的行动纲领)", "高橋洋子 (たかはし ようこ)",
                        "残酷な天使のテーゼ / FLY ME TO THE MOON", 245_000L,
                        "[00:02.00]残酷な天使のように\n[00:07.68]少年よ 神話になれ",
                        "[00:02.00]如残酷的天使般\n[00:07.68]少年啊 成为神话吧");
        PlatformTranslationService service = new PlatformTranslationService(Arrays.asList(qq));
        List<String> base = Arrays.asList("残酷な天使のように", "少年よ 神話になれ");
        PlatformTranslationService.Snapshot snapshot =
                lookup(service, "spotify:track:2", "残酷な天使のテーゼ", "高橋洋子", 245_866L, base);
        check(snapshot.hasAny(), "character song translated");
        check(snapshot.translatedLines == 2, "both lines translated");
        check(snapshot.lineTranslations.get(1).equals("少年啊 成为神话吧"), "second line text");
    }

    static void liveVersionLosesToStudioRecording() {
        FakeSource netease = new FakeSource(NeteaseLyricSource.ID, NeteaseLyricSource.LABEL)
                .candidate("studio", "Hallelujah", "Jeff Buckley", "Grace", 413_000L,
                        "[00:10.00]I heard there was a secret chord\n[00:14.00]That David played and it pleased the Lord",
                        "[00:10.00]我曾听过一段神秘的旋律\n[00:14.00]那是David为取悦上帝而演奏的")
                .candidate("live", "Hallelujah (Live at Bearsville Studios, New York - 1993)", "Jeff Buckley",
                        "Hallelujah", 395_000L,
                        "[00:12.00]I heard there was a secret chord\n[00:16.00]That David played and it pleased the Lord",
                        "[00:12.00]现场版本的第一句\n[00:16.00]现场版本的第二句");
        PlatformTranslationService service = new PlatformTranslationService(Arrays.asList(netease));
        List<String> base = Arrays.asList(
                "I heard there was a secret chord",
                "That David played and it pleased the Lord");
        PlatformTranslationService.Snapshot snapshot =
                lookup(service, "spotify:track:3", "Hallelujah", "Jeff Buckley", 413_000L, base);
        check(snapshot.hasAny(), "studio recording translated");
        check(snapshot.lineTranslations.get(0).equals("我曾听过一段神秘的旋律"),
                "studio translation used, not the live take");

        List<PlatformRecordingMatcher.Score> ranked = PlatformRecordingMatcher.rank(
                "Hallelujah", "Jeff Buckley", "", 413_000L, netease.candidates);
        check(ranked.get(0).candidate.id.equals("studio"), "studio ranked first");
        check(ranked.get(1).durationVetoed || !ranked.get(1).acceptable(), "live take rejected");

        // The reverse direction: a live track must not borrow the studio translation either.
        List<PlatformRecordingMatcher.Score> liveRanked = PlatformRecordingMatcher.rank(
                "Hallelujah (Live at Bearsville Studios)", "Jeff Buckley", "", 395_000L, netease.candidates);
        check(liveRanked.get(0).candidate.id.equals("live"), "live take wins for a live track");
        for (PlatformRecordingMatcher.Score score : liveRanked) {
            if (score.candidate.id.equals("studio")) {
                check(score.durationVetoed || !score.acceptable(),
                        "studio recording rejected for a live track");
            }
        }
    }

    static void differentLineSplitsAreJoined() {
        // The platform splits the first displayed line into two rows and joins the last two.
        FakeSource netease = new FakeSource(NeteaseLyricSource.ID, NeteaseLyricSource.LABEL)
                .candidate("split", "Split Song", "Artist", "", 200_000L,
                        "[00:01.00]Hold me\n[00:02.00]through the night\n[00:05.00]and never let go my hand",
                        "[00:01.00]抱住我\n[00:02.00]度过这夜\n[00:05.00]永远不要放开我的手");
        PlatformTranslationService service = new PlatformTranslationService(Arrays.asList(netease));
        List<String> base = Arrays.asList(
                "Hold me through the night",
                "and never let go",
                "my hand");
        PlatformTranslationService.Snapshot snapshot =
                lookup(service, "spotify:track:4", "Split Song", "Artist", 200_000L, base);
        check(snapshot.hasAny(), "split rows still translate");
        check(snapshot.lineTranslations.get(0).equals("抱住我 度过这夜"), "two rows joined onto one line");
        check(!snapshot.lineTranslations.get(1).isEmpty(), "one row split across two lines");
        check(snapshot.translatedLines == 3, "all lines covered");
    }

    static void repeatedChorusKeepsBothOccurrences() {
        FakeSource netease = new FakeSource(NeteaseLyricSource.ID, NeteaseLyricSource.LABEL)
                .candidate("chorus", "Chorus Song", "Artist", "", 180_000L,
                        "[00:10.00]We are the champions\n[00:30.00]We are the champions",
                        "[00:10.00]我们是冠军\n[00:30.00]我们就是冠军");
        PlatformTranslationService service = new PlatformTranslationService(Arrays.asList(netease));
        List<String> base = Arrays.asList("We are the champions", "We are the champions");
        PlatformTranslationService.Snapshot snapshot =
                lookup(service, "spotify:track:5", "Chorus Song", "Artist", 180_000L, base);
        check(snapshot.translatedLines == 2, "both chorus occurrences translated");
        check(snapshot.lineTranslations.get(0).equals("我们是冠军"), "first occurrence");
        check(snapshot.lineTranslations.get(1).equals("我们就是冠军"), "second occurrence keeps its own row");
    }

    static void partialCoverageFallsBackToTheSecondPlatform() {
        FakeSource netease = new FakeSource(NeteaseLyricSource.ID, NeteaseLyricSource.LABEL)
                .candidate("a", "Partial", "Artist", "", 210_000L,
                        "[00:01.00]line one\n[00:02.00]line two\n[00:03.00]line three\n[00:04.00]line four",
                        "[00:01.00]第一行\n[00:03.00]第三行");
        FakeSource qq = new FakeSource(QqLyricSource.ID, QqLyricSource.LABEL)
                .candidate("b", "Partial", "Artist", "", 210_000L,
                        "[00:01.00]line one\n[00:02.00]line two\n[00:03.00]line three\n[00:04.00]line four",
                        "[00:02.00]第二行\n[00:04.00]第四行");
        PlatformTranslationService service = new PlatformTranslationService(Arrays.asList(netease, qq));
        List<String> base = Arrays.asList("line one", "line two", "line three", "line four");
        PlatformTranslationService.Snapshot snapshot =
                lookup(service, "spotify:track:6", "Partial", "Artist", 210_000L, base);
        check(snapshot.translatedLines == 4, "gaps filled by the second platform");
        check(snapshot.lineTranslations.get(0).equals("第一行"), "first platform keeps its line");
        check(snapshot.lineTranslations.get(1).equals("第二行"), "second platform fills the gap");
        check(snapshot.lineSources.get(0).equals(NeteaseLyricSource.ID), "line source 0");
        check(snapshot.lineSources.get(1).equals(QqLyricSource.ID), "line source 1");
        check(snapshot.providerLabel.contains(NeteaseLyricSource.LABEL)
                && snapshot.providerLabel.contains(QqLyricSource.LABEL), "mixed sources are both credited");
        check(netease.fetchCount == 1 && qq.fetchCount == 1, "each platform fetched once");
    }

    static void unusableRowsAreRejected() {
        check(!ChineseLyricText.isUsable("//", "line one"), "placeholder row rejected");
        check(!ChineseLyricText.isUsable("", "line one"), "empty row rejected");
        check(!ChineseLyricText.isUsable("line one", "line one"), "original repeated is not a translation");
        check(!ChineseLyricText.isUsable("line one", "line one"), "duplicate of the source rejected");
        check(!ChineseLyricText.isUsable("lain wan", "line one"), "romanisation rejected");
        check(!ChineseLyricText.isUsable("QQ音乐享有本翻译作品的著作权", "line one"), "copyright notice rejected");
        check(!ChineseLyricText.isUsable("作词 : 及川眠子", "line one"), "credit row rejected");
        check(!ChineseLyricText.isUsable("残酷な天使のように", "残酷な天使のように"),
                "untranslated Japanese rejected");
        check(ChineseLyricText.isUsable("如残酷的天使般", "残酷な天使のように"), "real translation accepted");

        FakeSource netease = new FakeSource(NeteaseLyricSource.ID, NeteaseLyricSource.LABEL)
                .candidate("c", "Romanised", "Artist", "", 200_000L,
                        "[00:01.00]sakura sakura\n[00:02.00]yoru ni kakeru",
                        "[00:01.00]sakura sakura\n[00:02.00]//");
        PlatformTranslationService service = new PlatformTranslationService(Arrays.asList(netease));
        PlatformTranslationService.Snapshot snapshot = lookup(service, "spotify:track:7", "Romanised",
                "Artist", 200_000L, Arrays.asList("sakura sakura", "yoru ni kakeru"));
        check(!snapshot.hasAny(), "a song found without a usable translation yields nothing");
    }

    static void switchingTheOriginalSourceRealigns() {
        FakeSource netease = new FakeSource(NeteaseLyricSource.ID, NeteaseLyricSource.LABEL)
                .candidate("d", "Switch", "Artist", "", 200_000L,
                        "[00:01.00]alpha beta\n[00:02.00]gamma delta",
                        "[00:01.00]阿尔法\n[00:02.00]伽马");
        PlatformTranslationService service = new PlatformTranslationService(Arrays.asList(netease));
        List<String> first = Arrays.asList("alpha beta", "gamma delta");
        List<String> second = Arrays.asList("gamma delta", "alpha beta");
        PlatformTranslationService.Snapshot one =
                lookup(service, "spotify:track:8", "Switch", "Artist", 200_000L, first);
        PlatformTranslationService.Snapshot two =
                lookup(service, "spotify:track:8", "Switch", "Artist", 200_000L, second);
        check(one.lineTranslations.get(0).equals("阿尔法"), "first source order");
        check(two.lineTranslations.get(0).equals("伽马"), "re-aligned after the original source changed");
        check(netease.searchCount == 2, "the changed text is a different cache entry");
    }

    static void lateAnswerForThePreviousTrackIsDropped() {
        AtomicReference<String> currentTrack = new AtomicReference<>("spotify:track:new");
        FakeSource netease = new FakeSource(NeteaseLyricSource.ID, NeteaseLyricSource.LABEL)
                .candidate("e", "Late", "Artist", "", 200_000L,
                        "[00:01.00]slow line", "[00:01.00]迟到的译文");
        // The search for the old track finishes after the user already switched songs.
        netease.onSearch = () -> currentTrack.set("spotify:track:other");
        PlatformTranslationService service =
                new PlatformTranslationService(Arrays.asList(netease), currentTrack::get);
        PlatformTranslationService.Snapshot snapshot =
                lookup(service, "spotify:track:old", "Late", "Artist", 200_000L,
                        Arrays.asList("slow line"));
        check(snapshot.stale, "late answer marked stale");
        check(!snapshot.hasAny(), "stale answer is not offered to the renderer");
    }

    static void geniusPlacementHandlesFragments() {
        List<String> base = Arrays.asList(
                "And last night, you were in my room",
                "And now my bed sheets smell like you",
                "Every day discovering something brand new",
                "I'm in love with your body",
                "Every day discovering something brand new",
                "I'm in love with your body");
        List<SongAnnotation> annotations = new ArrayList<>();
        annotations.add(new SongAnnotation("1", "And last night, you were in my room\nAnd now my bed sheets smell like you",
                "Two lines quoted together.", "https://genius.com/1", false, 3, "accepted"));
        annotations.add(new SongAnnotation("2", "Im in love with your body", "Punctuation differs.",
                "https://genius.com/2", false, 1, "accepted"));
        annotations.add(new SongAnnotation("3", "Oh", "Too short to place.", "https://genius.com/3",
                false, 0, "accepted"));
        AnnotationLineMapper.Result mapped = AnnotationLineMapper.map(base, annotations);
        check(mapped.placed.size() == 2, "two fragments placed");
        check(mapped.placed.get(0).lineIndexes.equals(Arrays.asList(0, 1)), "cross-line fragment covers both lines");
        check(mapped.placed.get(1).lineIndexes.contains(3) && mapped.placed.get(1).lineIndexes.contains(5),
                "a repeated chorus is placed on every occurrence");
        check(mapped.unplaced.size() == 1, "the ambiguous fragment stays unplaced");

        SongMeaning meaning = new SongMeaning("2949128", "Shape of You", "Ed Sheeran",
                "https://genius.com/song", "A description.", mapped.placed, mapped.unplaced, "Genius");
        check(meaning.hasDescription() && meaning.hasAnnotations(), "meaning has both parts");
        check(meaning.hasAnnotationOnLine(0) && !meaning.hasAnnotationOnLine(2), "underline only where placed");
        check(meaning.annotationsForLine(1).size() == 1, "annotation reachable from its line");

        // Full-width Japanese and punctuation differences still match.
        List<String> japanese = Arrays.asList("残酷な天使のように", "少年よ、神話になれ！");
        AnnotationLineMapper.Result japaneseMapped = AnnotationLineMapper.map(japanese,
                Arrays.asList(new SongAnnotation("4", "少年よ 神話になれ", "Full-width punctuation.",
                        "https://genius.com/4", false, 0, "accepted")));
        check(japaneseMapped.placed.size() == 1, "full-width Japanese fragment placed");
        check(japaneseMapped.placed.get(0).lineIndexes.contains(1), "placed on the right line");
    }

    static void geniusWithoutDataStaysEmpty() {
        check(SongMeaning.EMPTY.isEmpty(), "empty meaning");
        check(!SongMeaning.EMPTY.hasDescription(), "no description");
        AnnotationLineMapper.Result mapped = AnnotationLineMapper.map(
                Arrays.asList("line"), new ArrayList<>());
        check(mapped.placed.isEmpty() && mapped.unplaced.isEmpty(), "no annotations, no placements");
        check(SongMeaningService.chooseRecording(new ArrayList<>(), "Title", "Artist") == null,
                "no Genius hit yields no recording");
        check(SongMeaningService.place(SongMeaning.EMPTY, Arrays.asList("line")).isEmpty(),
                "placing an empty meaning stays empty");

        List<GeniusClient.Hit> hits = new ArrayList<>();
        hits.add(new GeniusClient.Hit("1", "Lemon (Romanized)", "Genius Romanizations", "https://genius.com/1"));
        hits.add(new GeniusClient.Hit("2", "Lemon (Live)", "米津玄師", "https://genius.com/2"));
        hits.add(new GeniusClient.Hit("3", "Lemon", "米津玄師", "https://genius.com/3"));
        GeniusClient.Hit chosen = SongMeaningService.chooseRecording(hits, "Lemon", "米津玄師");
        check(chosen != null && chosen.id.equals("3"), "plain studio entry preferred");
    }
}
