package kr.ivlis.ivlyricsandroid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Combines the platform sources into one per-line Chinese translation.
 *
 * <p>Every source is asked the same way: try the search variants from most to least specific, rank the
 * recordings, reject a different recording, align the rows on normalised text, and keep only the lines
 * the source can actually translate. The first source fills what it can, the next fills the gaps, and
 * the remaining lines are left for the AI stage. Each source records a status so the song settings can
 * say "found the recording, no usable Chinese" instead of showing one generic failure.
 */
final class PlatformTranslationService {
    private static final String CACHE_PREFS = "platform_translation_cache";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_CACHE_ENTRIES = 40;
    private static final long CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L;
    private static final int SEARCH_LIMIT = 10;
    /** A document whose own rows barely match the displayed lyrics is a different recording. */
    private static final double MIN_ORIGINAL_COVERAGE = 0.25d;

    /** What happened with one source, in a form the settings page can render. */
    static final class SourceStatus {
        static final String STATE_DISABLED = "disabled";
        static final String STATE_NO_RESULT = "no_result";
        static final String STATE_NO_RECORDING = "no_recording";
        static final String STATE_NO_CHINESE = "no_chinese";
        static final String STATE_MISMATCH = "mismatch";
        static final String STATE_NETWORK = "network";
        static final String STATE_OK = "ok";

        final String sourceId;
        final String label;
        final String state;
        final String recordingTitle;
        final String recordingArtist;
        final long recordingDurationMs;
        final int translatedLines;
        final int translatableLines;
        final int contributedLines;
        final String detail;

        SourceStatus(String sourceId, String label, String state, String recordingTitle,
                String recordingArtist, long recordingDurationMs, int translatedLines,
                int translatableLines, int contributedLines, String detail) {
            this.sourceId = sourceId == null ? "" : sourceId;
            this.label = label == null ? "" : label;
            this.state = state == null ? STATE_NO_RESULT : state;
            this.recordingTitle = recordingTitle == null ? "" : recordingTitle;
            this.recordingArtist = recordingArtist == null ? "" : recordingArtist;
            this.recordingDurationMs = recordingDurationMs;
            this.translatedLines = translatedLines;
            this.translatableLines = translatableLines;
            this.contributedLines = contributedLines;
            this.detail = detail == null ? "" : detail;
        }

        boolean matched() {
            return STATE_OK.equals(state) || STATE_NO_CHINESE.equals(state);
        }
    }

    static final class Snapshot {
        final List<String> lineTranslations;
        final List<String> lineSources;
        final List<String> diagnostics;
        final List<SourceStatus> statuses;
        final String primarySourceId;
        final String providerLabel;
        final int translatedLines;
        final int translatableLines;
        final boolean stale;

        Snapshot(List<String> lineTranslations, List<String> lineSources, List<String> diagnostics,
                List<SourceStatus> statuses, String primarySourceId, String providerLabel,
                int translatedLines, int translatableLines, boolean stale) {
            this.lineTranslations = Collections.unmodifiableList(new ArrayList<>(lineTranslations));
            this.lineSources = Collections.unmodifiableList(new ArrayList<>(lineSources));
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
            this.statuses = Collections.unmodifiableList(
                    statuses == null ? new ArrayList<>() : new ArrayList<>(statuses));
            this.primarySourceId = primarySourceId == null ? "" : primarySourceId;
            this.providerLabel = providerLabel == null ? "" : providerLabel;
            this.translatedLines = translatedLines;
            this.translatableLines = translatableLines;
            this.stale = stale;
        }

        boolean hasAny() {
            return translatedLines > 0 && !stale;
        }

        boolean isTranslated(int lineIndex) {
            return lineIndex >= 0 && lineIndex < lineTranslations.size()
                    && !lineTranslations.get(lineIndex).isEmpty();
        }

        String sourceOf(int lineIndex) {
            return lineIndex >= 0 && lineIndex < lineSources.size() ? lineSources.get(lineIndex) : "";
        }

        SourceStatus statusOf(String sourceId) {
            for (SourceStatus status : statuses) {
                if (status.sourceId.equals(sourceId)) {
                    return status;
                }
            }
            return null;
        }

        static Snapshot empty() {
            return new Snapshot(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), "", "", 0, 0, false);
        }
    }

    private final PlatformTranslationSettings settings;
    private final SharedPreferences cachePrefs;
    private final List<PlatformLyricSource> sources;
    private final Supplier<String> currentTrackKey;
    private final Map<String, Snapshot> memoryCache = new LinkedHashMap<String, Snapshot>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };
    private final Map<String, Object> inFlight = new LinkedHashMap<>();

    PlatformTranslationService(
            Context context,
            PlatformTranslationSettings settings,
            PlatformHttpTransport.Transport transport,
            Supplier<String> currentTrackKey
    ) {
        this.settings = settings;
        Context appContext = context == null ? null : context.getApplicationContext();
        this.cachePrefs = appContext == null
                ? null
                : appContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        this.sources = new ArrayList<>();
        this.sources.add(new NeteaseLyricSource(transport));
        this.sources.add(new QqLyricSource(transport));
        this.sources.add(new KugouLyricSource(transport));
        this.currentTrackKey = currentTrackKey;
    }

    /** Test seam: fixed source list, no preferences. */
    PlatformTranslationService(List<PlatformLyricSource> sources) {
        this(sources, () -> "");
    }

    /** Test seam with an observable current track, so a late answer can be rejected. */
    PlatformTranslationService(List<PlatformLyricSource> sources, Supplier<String> currentTrackKey) {
        this.settings = new PlatformTranslationSettings((SharedPreferences) null);
        this.cachePrefs = null;
        this.sources = new ArrayList<>(sources);
        this.currentTrackKey = currentTrackKey == null ? () -> "" : currentTrackKey;
    }

    /**
     * Looks up a Chinese translation for {@code baseTexts}. Returns an empty snapshot when the feature
     * is off, the target language is not Chinese, or no platform had a usable translation.
     */
    Snapshot lookup(
            String trackKey,
            String title,
            String artist,
            String album,
            long durationMs,
            List<String> baseTexts,
            String targetLanguage,
            boolean bypassCache
    ) {
        if (!PlatformTranslationSettings.isChineseTarget(targetLanguage)) {
            return Snapshot.empty();
        }
        List<String> order = settings.orderForTrack(trackKey);
        if (order.isEmpty() || baseTexts == null || baseTexts.isEmpty()) {
            return Snapshot.empty();
        }
        boolean mix = settings.mixSources();
        String key = cacheKey(trackKey, order, settings.manualSource(trackKey) + "|mix=" + mix, baseTexts);
        if (!bypassCache) {
            Snapshot cached = memoryCache.get(key);
            if (cached == null) {
                cached = readCache(key);
                if (cached != null) {
                    memoryCache.put(key, cached);
                }
            }
            if (cached != null) {
                return isStale(trackKey) ? Snapshot.empty() : cached;
            }
        }

        Object lock;
        synchronized (inFlight) {
            lock = inFlight.computeIfAbsent(key, unused -> new Object());
        }
        try {
            synchronized (lock) {
                Snapshot first = memoryCache.get(key);
                if (!bypassCache && first != null) {
                    return isStale(trackKey) ? Snapshot.empty() : first;
                }
                Snapshot resolved = resolve(trackKey, title, artist, album, durationMs, baseTexts, order, mix);
                if (!resolved.stale) {
                    memoryCache.put(key, resolved);
                    writeCache(key, resolved);
                }
                return resolved;
            }
        } finally {
            synchronized (inFlight) {
                inFlight.remove(key);
            }
        }
    }

    private Snapshot resolve(
            String trackKey,
            String title,
            String artist,
            String album,
            long durationMs,
            List<String> baseTexts,
            List<String> order,
            boolean mix
    ) {
        List<String> translations = new ArrayList<>(Collections.nCopies(baseTexts.size(), ""));
        List<String> lineSources = new ArrayList<>(Collections.nCopies(baseTexts.size(), ""));
        List<String> diagnostics = new ArrayList<>();
        List<SourceStatus> statuses = new ArrayList<>();
        Map<String, Integer> lineCountBySource = new LinkedHashMap<>();

        for (String sourceId : order) {
            if (isStale(trackKey)) {
                diagnostics.add("stale track, result discarded");
                return new Snapshot(Collections.emptyList(), Collections.emptyList(), diagnostics,
                        Collections.emptyList(), "", "", 0, 0, true);
            }
            PlatformLyricSource source = sourceById(sourceId);
            if (source == null) {
                continue;
            }
            SourceStatus status = resolveOne(source, title, artist, album, durationMs, baseTexts,
                    translations, lineSources, diagnostics);
            statuses.add(status);
            if (status.contributedLines > 0) {
                lineCountBySource.put(sourceId, status.contributedLines);
                if (!mix) {
                    // Single-source mode: the first source with anything usable owns the translation,
                    // and its gaps stay empty instead of being filled by the next source.
                    diagnostics.add("mix off: stopped after " + source.label());
                    break;
                }
            }
        }

        int translatable = 0;
        for (String text : baseTexts) {
            if (text != null && !text.trim().isEmpty() && !ChineseLyricText.isPlaceholder(text)
                    && !PlatformLyricRow.isCreditRow(text)) {
                translatable++;
            }
        }
        int translated = 0;
        for (String value : translations) {
            if (!value.isEmpty()) {
                translated++;
            }
        }
        String primary = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : lineCountBySource.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                primary = entry.getKey();
            }
        }
        String label = providerLabel(lineCountBySource.keySet());
        return new Snapshot(translations, lineSources, diagnostics, statuses, primary, label,
                translated, translatable, isStale(trackKey));
    }

    /** One source: search variants, recording filter, alignment, and the status that describes it. */
    private SourceStatus resolveOne(
            PlatformLyricSource source,
            String title,
            String artist,
            String album,
            long durationMs,
            List<String> baseTexts,
            List<String> translations,
            List<String> lineSources,
            List<String> diagnostics
    ) {
        String label = source.label();
        PlatformRecordingMatcher.Score matched = null;
        PlatformTranslationAligner.Result aligned = null;
        String lastFailure = "";
        for (String query : PlatformRecordingMatcher.searchQueries(title, artist)) {
            List<PlatformRecordingMatcher.Candidate> candidates;
            try {
                candidates = source.search(query, SEARCH_LIMIT);
            } catch (Exception error) {
                diagnostics.add(label + " search \"" + query + "\" failed: " + describe(error));
                return new SourceStatus(source.id(), label, SourceStatus.STATE_NETWORK, "", "", 0L,
                        0, 0, 0, describe(error));
            }
            if (candidates.isEmpty()) {
                diagnostics.add(label + " search \"" + query + "\": no result");
                lastFailure = "no result";
                continue;
            }
            List<PlatformRecordingMatcher.Score> ranked =
                    PlatformRecordingMatcher.rank(title, artist, album, durationMs, candidates);
            PlatformRecordingMatcher.Score best = null;
            for (PlatformRecordingMatcher.Score score : ranked) {
                if (score.acceptable()) {
                    best = score;
                    break;
                }
            }
            if (best == null) {
                diagnostics.add(label + " search \"" + query + "\": " + candidates.size()
                        + " results, none the same recording (best " + round(ranked.get(0).total)
                        + (ranked.get(0).durationVetoed ? " duration-veto" : "") + ")");
                lastFailure = "no recording";
                continue;
            }
            PlatformLyricSource.PlatformLyricDocument document;
            try {
                document = source.fetch(best.candidate.id);
            } catch (Exception error) {
                diagnostics.add(label + " fetch \"" + best.candidate.title + "\" failed: " + describe(error));
                return new SourceStatus(source.id(), label, SourceStatus.STATE_NETWORK,
                        best.candidate.title, best.candidate.artist, best.candidate.durationMs,
                        0, 0, 0, describe(error));
            }
            PlatformTranslationAligner.Result attempt = PlatformTranslationAligner.align(
                    baseTexts, document.originalRows, document.translationRows);
            if (attempt.originalCoverage() < MIN_ORIGINAL_COVERAGE) {
                diagnostics.add(label + " picked \"" + best.candidate.title
                        + "\" but its rows do not match the displayed lyrics (originals "
                        + round(attempt.originalCoverage()) + ")");
                lastFailure = "mismatch";
                continue;
            }
            matched = best;
            aligned = attempt;
            break;
        }

        if (matched == null || aligned == null) {
            String state = "no result".equals(lastFailure) || lastFailure.isEmpty()
                    ? SourceStatus.STATE_NO_RESULT
                    : "no recording".equals(lastFailure)
                    ? SourceStatus.STATE_NO_RECORDING
                    : SourceStatus.STATE_MISMATCH;
            return new SourceStatus(source.id(), label, state, "", "", 0L, 0, 0, 0, lastFailure);
        }

        int added = 0;
        for (int index = 0; index < translations.size() && index < aligned.translations.size(); index++) {
            String value = aligned.translations.get(index);
            if (value.isEmpty() || !translations.get(index).isEmpty()) {
                continue;
            }
            translations.set(index, value);
            lineSources.set(index, source.id());
            added++;
        }
        diagnostics.add(label + " picked \"" + matched.candidate.title + "\" / "
                + matched.candidate.artist + " score=" + round(matched.total)
                + " delta=" + Math.abs(durationMs - matched.candidate.durationMs) + "ms"
                + " translated=" + aligned.translatedLines + "/" + aligned.translatableLines
                + " originals=" + round(aligned.originalCoverage())
                + " contributed=" + added);
        boolean chinese = aligned.translatedLines > 0;
        return new SourceStatus(source.id(), label,
                chinese ? SourceStatus.STATE_OK : SourceStatus.STATE_NO_CHINESE,
                matched.candidate.title, matched.candidate.artist, matched.candidate.durationMs,
                aligned.translatedLines, aligned.translatableLines, added,
                chinese ? "" : "found the recording, but no usable Chinese rows");
    }

    private static String describe(Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage().trim();
        return message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private String providerLabel(java.util.Set<String> contributing) {
        List<String> labels = new ArrayList<>();
        for (String id : contributing) {
            String label = PlatformTranslationSettings.label(id);
            if (!label.isEmpty()) {
                labels.add(label);
            }
        }
        return String.join(" + ", labels);
    }

    private PlatformLyricSource sourceById(String id) {
        for (PlatformLyricSource source : sources) {
            if (source.id().equals(id)) {
                return source;
            }
        }
        return null;
    }

    private boolean isStale(String trackKey) {
        String current = currentTrackKey == null ? "" : currentTrackKey.get();
        return !current.isEmpty() && !current.equals(trackKey);
    }

    static String cacheKey(String trackKey, List<String> order, String manual, List<String> baseTexts) {
        StringBuilder payload = new StringBuilder();
        payload.append(trackKey == null ? "" : trackKey).append('|')
                .append(String.join(",", order)).append('|')
                .append(manual == null ? "" : manual).append('|');
        for (String text : baseTexts) {
            payload.append(text == null ? "" : text).append('\n');
        }
        return sha256(payload.toString());
    }

    private Snapshot readCache(String key) {
        if (cachePrefs == null) {
            return null;
        }
        String raw = cachePrefs.getString(KEY_ENTRIES, "");
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject entries = new JSONObject(raw);
            JSONObject entry = entries.optJSONObject(key);
            if (entry == null) {
                return null;
            }
            long age = System.currentTimeMillis() - entry.optLong("at", 0L);
            if (age < 0L || age > CACHE_MAX_AGE_MS) {
                entries.remove(key);
                cachePrefs.edit().putString(KEY_ENTRIES, entries.toString()).apply();
                return null;
            }
            JSONArray lines = entry.optJSONArray("lines");
            JSONArray sourceIds = entry.optJSONArray("sources");
            List<String> translations = new ArrayList<>();
            List<String> lineSources = new ArrayList<>();
            if (lines != null) {
                for (int index = 0; index < lines.length(); index++) {
                    translations.add(lines.optString(index, ""));
                    lineSources.add(sourceIds == null ? "" : sourceIds.optString(index, ""));
                }
            }
            return new Snapshot(translations, lineSources, Collections.emptyList(),
                    Collections.emptyList(), entry.optString("primary", ""),
                    entry.optString("label", ""), entry.optInt("translated", 0),
                    entry.optInt("translatable", 0), false);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private void writeCache(String key, Snapshot snapshot) {
        if (cachePrefs == null || !snapshot.hasAny()) {
            return;
        }
        try {
            JSONObject entries;
            String raw = cachePrefs.getString(KEY_ENTRIES, "");
            try {
                entries = raw == null || raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
            } catch (Exception corrupted) {
                entries = new JSONObject();
            }
            JSONObject entry = new JSONObject();
            entry.put("at", System.currentTimeMillis());
            entry.put("primary", snapshot.primarySourceId);
            entry.put("label", snapshot.providerLabel);
            entry.put("translated", snapshot.translatedLines);
            entry.put("translatable", snapshot.translatableLines);
            entry.put("lines", new JSONArray(new ArrayList<Object>(snapshot.lineTranslations)));
            entry.put("sources", new JSONArray(new ArrayList<Object>(snapshot.lineSources)));
            entries.put(key, entry);
            prune(entries);
            cachePrefs.edit().putString(KEY_ENTRIES, entries.toString()).apply();
        } catch (Exception unwritable) {
            // A cache that cannot be written never blocks the translation itself.
        }
    }

    private static void prune(JSONObject entries) {
        if (entries.length() <= MAX_CACHE_ENTRIES) {
            return;
        }
        List<String> keys = new ArrayList<>();
        java.util.Iterator<String> iterator = entries.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        keys.sort((left, right) -> Long.compare(
                entries.optJSONObject(left) == null ? 0L : entries.optJSONObject(left).optLong("at", 0L),
                entries.optJSONObject(right) == null ? 0L : entries.optJSONObject(right).optLong("at", 0L)));
        for (int index = 0; index < keys.size() - MAX_CACHE_ENTRIES; index++) {
            entries.remove(keys.get(index));
        }
    }

    /** True when the user wants platform translations only, with gaps left empty. */
    boolean platformOnly() {
        return settings != null && settings.platformOnly();
    }

    boolean enabled() {
        return settings != null && settings.enabled();
    }

    List<String> sourceOrder() {
        return settings == null ? java.util.Collections.<String>emptyList() : settings.snapshot().order;
    }

    void clearCache() {
        memoryCache.clear();
        if (cachePrefs != null) {
            cachePrefs.edit().remove(KEY_ENTRIES).apply();
        }
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(Character.forDigit((item >> 4) & 0xf, 16));
                builder.append(Character.forDigit(item & 0xf, 16));
            }
            return builder.toString();
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
