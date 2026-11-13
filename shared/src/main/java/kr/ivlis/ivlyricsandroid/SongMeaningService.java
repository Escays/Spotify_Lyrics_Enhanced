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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads a Genius meaning for the current recording, places its annotations on the displayed lines and
 * keeps a small disk cache.
 *
 * <p>The lookup is keyed by the recording query and by the displayed lyric text, so switching the
 * original lyric source re-runs placement against the new lines instead of reusing line numbers from the
 * previous source. A failed lookup yields an empty meaning: it never blocks lyrics, platform translations
 * or playback.
 */
final class SongMeaningService {
    private static final String CACHE_PREFS = "song_meaning_cache";
    private static final String KEYS_TRANSLATIONS = "translations";
    private static final int MAX_TRANSLATION_ENTRIES = 60;
    private static final long CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L;

    interface Listener {
        void onMeaning(String trackKey, SongMeaning meaning, String error);
    }

    /** Bridge to the existing translation chain; returns one translated string per input string. */
    interface FreeTextTranslator {
        List<String> translate(List<String> texts, String targetLang) throws Exception;
    }

    static final class MeaningTranslation {
        final String description;
        final Map<String, String> bodies;

        MeaningTranslation(String description, Map<String, String> bodies) {
            this.description = description == null ? "" : description;
            this.bodies = Collections.unmodifiableMap(new LinkedHashMap<>(bodies));
        }

        String bodyFor(SongAnnotation annotation) {
            String value = bodies.get(annotation.cacheKey());
            return value == null ? "" : value;
        }

        boolean isEmpty() {
            return description.trim().isEmpty() && bodies.isEmpty();
        }
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private final GeniusClient client;
    private final SharedPreferences cachePrefs;
    private final SharedPreferences translationPrefs;
    private volatile boolean shutdown;

    SongMeaningService(Context context, PlatformHttpTransport.Transport transport) {
        this.client = new GeniusClient(transport);
        Context appContext = context == null ? null : context.getApplicationContext();
        this.cachePrefs = appContext == null
                ? null
                : appContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        this.translationPrefs = cachePrefs;
    }

    /** Test seam: transport only, no preferences and no background thread. */
    SongMeaningService(PlatformHttpTransport.Transport transport) {
        this.client = new GeniusClient(transport);
        this.cachePrefs = null;
        this.translationPrefs = null;
    }

    void load(String trackKey, String title, String artist, List<String> baseTexts, boolean bypassCache,
            Listener listener) {
        if (shutdown) {
            return;
        }
        executor.execute(() -> {
            SongMeaning meaning;
            String error = null;
            try {
                meaning = loadBlocking(trackKey, title, artist, baseTexts, bypassCache);
            } catch (Exception failure) {
                meaning = SongMeaning.EMPTY;
                error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            }
            if (shutdown) {
                return;
            }
            listener.onMeaning(trackKey, meaning, error);
        });
    }

    SongMeaning loadBlocking(String trackKey, String title, String artist, List<String> baseTexts,
            boolean bypassCache) throws Exception {
        String cacheKey = cacheKey(trackKey, baseTexts);
        if (!bypassCache) {
            SongMeaning cached = readCache(cacheKey);
            if (cached != null) {
                return place(cached, baseTexts);
            }
        }

        // A query carrying the version tail (" - 2017 Remaster") returns unrelated songs from
        // Genius' fuzzy search, so the same cleaned variants the platforms use are tried in order.
        GeniusClient.Hit best = null;
        String lastQuery = "";
        SongMeaning found = SongMeaning.EMPTY;
        for (String query : PlatformRecordingMatcher.searchQueries(title, artist)) {
            lastQuery = query;
            List<GeniusClient.Hit> hits = client.search(query);
            GeniusClient.Hit candidate = chooseRecording(hits, title, artist);
            if (candidate == null) {
                continue;
            }
            if (best == null) {
                best = candidate;
            }
            // The first hit for one spelling can be an empty anniversary take or a remix while the
            // studio page — the one carrying the annotations — sits under the other spelling. An
            // empty page is not an answer, so the remaining queries still get their turn.
            SongMeaning fetched = client.fetch(candidate.id, GeniusClient.LABEL);
            if (!fetched.isEmpty()) {
                best = candidate;
                found = fetched;
                break;
            }
        }
        if (best == null) {
            PlatformTranslationSettings.log("genius: no acceptable hit for \"" + lastQuery + "\"");
            return SongMeaning.EMPTY;
        }
        if (found.isEmpty()) {
            PlatformTranslationSettings.log("genius: every hit for \"" + lastQuery
                    + "\" is empty; kept " + best.title);
        }
        // An empty answer is never cached: one miss today (a spelling the search engine has not
        // indexed yet, a dropped request) must not pin the panel to "nothing found" for a week.
        if (!found.isEmpty()) {
            writeCache(cacheKey, found);
        }
        return place(found, baseTexts);
    }

    /**
     * Picks the studio recording rather than a translation, a romanisation or a compilation entry.
     * Genius reports no duration, so title and artist carry the decision and the ranking prefers an
     * unadorned title over one described as live, remixed or translated.
     */
    static GeniusClient.Hit chooseRecording(List<GeniusClient.Hit> hits, String title, String artist) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        GeniusClient.Hit best = null;
        double bestScore = 0d;
        for (GeniusClient.Hit hit : hits) {
            double titleScore = PlatformRecordingMatcher.titleSimilarity(title, hit.title);
            double artistScore = PlatformRecordingMatcher.artistSimilarity(artist, hit.artist);
            double score = (0.65d * titleScore) + (0.35d * artistScore);
            String lower = ChineseLyricText.lower(hit.title);
            if (lower.contains("translation") || lower.contains("romanized") || lower.contains("romanisation")) {
                score -= 0.35d;
            }
            score -= PlatformRecordingMatcher.versionTags(hit.title).isEmpty() ? 0d : 0.08d;
            if (titleScore < 0.6d || artistScore < 0.4d) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                best = hit;
            }
        }
        return best;
    }

    static SongMeaning place(SongMeaning meaning, List<String> baseTexts) {
        if (meaning == null || meaning.isEmpty()) {
            return SongMeaning.EMPTY;
        }
        AnnotationLineMapper.Result mapped =
                AnnotationLineMapper.map(baseTexts, meaning.allAnnotations());
        return new SongMeaning(
                meaning.songId,
                meaning.title,
                meaning.artist,
                meaning.url,
                meaning.description,
                mapped.placed,
                mapped.unplaced,
                meaning.sourceLabel
        );
    }

    /**
     * Translates the description and every annotation body with the existing translation chain. Results
     * are cached per song and target language; a failure returns an empty translation so the original
     * text stays readable.
     */
    MeaningTranslation translate(SongMeaning meaning, String targetLang, FreeTextTranslator translator)
            throws Exception {
        return translate(meaning, targetLang, "", translator);
    }

    /** As above, but the cache is partitioned by engine so a switch does not reuse the old wording. */
    MeaningTranslation translate(SongMeaning meaning, String targetLang, String engineId,
            FreeTextTranslator translator) throws Exception {
        if (meaning == null || meaning.isEmpty() || translator == null) {
            return new MeaningTranslation("", Collections.emptyMap());
        }
        List<SongAnnotation> annotations = meaning.allAnnotations();
        List<String> texts = new ArrayList<>();
        if (meaning.hasDescription()) {
            texts.add(meaning.description);
        }
        for (SongAnnotation annotation : annotations) {
            texts.add(annotation.body);
        }
        if (texts.isEmpty()) {
            return new MeaningTranslation("", Collections.emptyMap());
        }
        String key = translationKey(meaning, targetLang, engineId);
        MeaningTranslation cached = readTranslation(key);
        if (cached != null) {
            return cached;
        }
        List<String> translated = translator.translate(texts, targetLang);
        if (translated == null || translated.size() != texts.size()) {
            return new MeaningTranslation("", Collections.emptyMap());
        }
        int cursor = 0;
        String description = "";
        if (meaning.hasDescription()) {
            description = safe(translated.get(cursor));
            cursor++;
        }
        Map<String, String> bodies = new LinkedHashMap<>();
        for (SongAnnotation annotation : annotations) {
            bodies.put(annotation.cacheKey(), safe(translated.get(cursor)));
            cursor++;
        }
        MeaningTranslation result = new MeaningTranslation(description, bodies);
        writeTranslation(key, result);
        return result;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private SongMeaning readCache(String key) {
        if (cachePrefs == null) {
            return null;
        }
        try {
            String raw = cachePrefs.getString("entry:" + key, "");
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            JSONObject object = new JSONObject(raw);
            long age = System.currentTimeMillis() - object.optLong("at", 0L);
            if (age < 0L || age > CACHE_MAX_AGE_MS) {
                return null;
            }
            List<SongAnnotation> annotations = new ArrayList<>();
            JSONArray array = object.optJSONArray("annotations");
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    JSONObject item = array.optJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    annotations.add(new SongAnnotation(
                            item.optString("id", ""),
                            item.optString("fragment", ""),
                            item.optString("body", ""),
                            item.optString("url", ""),
                            item.optBoolean("description", false),
                            item.optInt("votes", 0),
                            item.optString("classification", "")
                    ));
                }
            }
            return new SongMeaning(
                    object.optString("songId", ""),
                    object.optString("title", ""),
                    object.optString("artist", ""),
                    object.optString("url", ""),
                    object.optString("description", ""),
                    Collections.emptyList(),
                    annotations,
                    object.optString("sourceLabel", GeniusClient.LABEL)
            );
        } catch (Exception unreadable) {
            return null;
        }
    }

    private void writeCache(String key, SongMeaning meaning) {
        if (cachePrefs == null) {
            return;
        }
        try {
            JSONObject object = new JSONObject();
            object.put("at", System.currentTimeMillis());
            object.put("songId", meaning.songId);
            object.put("title", meaning.title);
            object.put("artist", meaning.artist);
            object.put("url", meaning.url);
            object.put("description", meaning.description);
            object.put("sourceLabel", meaning.sourceLabel);
            JSONArray annotations = new JSONArray();
            for (SongAnnotation annotation : meaning.allAnnotations()) {
                annotations.put(new JSONObject()
                        .put("id", annotation.id)
                        .put("fragment", annotation.fragment)
                        .put("body", annotation.body)
                        .put("url", annotation.url)
                        .put("description", annotation.description)
                        .put("votes", annotation.votes)
                        .put("classification", annotation.classification));
            }
            object.put("annotations", annotations);
            cachePrefs.edit().putString("entry:" + key, object.toString()).apply();
        } catch (Exception unwritable) {
            // Cache writes never block the panel.
        }
    }

    private MeaningTranslation readTranslation(String key) {
        if (translationPrefs == null) {
            return null;
        }
        try {
            String raw = translationPrefs.getString(KEYS_TRANSLATIONS, "");
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            JSONObject entries = new JSONObject(raw);
            JSONObject entry = entries.optJSONObject(key);
            if (entry == null) {
                return null;
            }
            long age = System.currentTimeMillis() - entry.optLong("at", 0L);
            if (age < 0L || age > CACHE_MAX_AGE_MS) {
                entries.remove(key);
                return null;
            }
            Map<String, String> bodies = new LinkedHashMap<>();
            JSONObject bodyObject = entry.optJSONObject("bodies");
            if (bodyObject != null) {
                java.util.Iterator<String> iterator = bodyObject.keys();
                while (iterator.hasNext()) {
                    String bodyKey = iterator.next();
                    bodies.put(bodyKey, bodyObject.optString(bodyKey, ""));
                }
            }
            return new MeaningTranslation(entry.optString("description", ""), bodies);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private void writeTranslation(String key, MeaningTranslation translation) {
        if (translationPrefs == null || translation.isEmpty()) {
            return;
        }
        try {
            JSONObject entries;
            String raw = translationPrefs.getString(KEYS_TRANSLATIONS, "");
            try {
                entries = raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            } catch (Exception corrupted) {
                entries = new JSONObject();
            }
            JSONObject bodies = new JSONObject();
            for (Map.Entry<String, String> entry : translation.bodies.entrySet()) {
                bodies.put(entry.getKey(), entry.getValue());
            }
            entries.put(key, new JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("description", translation.description)
                    .put("bodies", bodies));
            while (entries.length() > MAX_TRANSLATION_ENTRIES) {
                String oldest = null;
                long oldestAt = Long.MAX_VALUE;
                java.util.Iterator<String> iterator = entries.keys();
                while (iterator.hasNext()) {
                    String candidate = iterator.next();
                    JSONObject entry = entries.optJSONObject(candidate);
                    long at = entry == null ? 0L : entry.optLong("at", 0L);
                    if (at < oldestAt) {
                        oldestAt = at;
                        oldest = candidate;
                    }
                }
                if (oldest == null) {
                    break;
                }
                entries.remove(oldest);
            }
            translationPrefs.edit().putString(KEYS_TRANSLATIONS, entries.toString()).apply();
        } catch (Exception unwritable) {
            // Ignore: the translation is still displayed for this session.
        }
    }

    void shutdown() {
        shutdown = true;
        executor.shutdownNow();
    }

    private static String cacheKey(String trackKey, List<String> baseTexts) {
        // Bumped when the lookup itself changes: entries written by an older matcher must not answer
        // for it, and that includes the empty ones it used to store.
        StringBuilder payload = new StringBuilder("v2|").append(trackKey == null ? "" : trackKey).append('|');
        if (baseTexts != null) {
            for (String text : baseTexts) {
                payload.append(AnnotationLineMapper.squash(text)).append('\n');
            }
        }
        return sha256(payload.toString());
    }

    private static String translationKey(SongMeaning meaning, String targetLang, String engineId) {
        StringBuilder payload = new StringBuilder()
                .append(meaning.songId).append('|')
                .append(targetLang == null ? "" : targetLang.toLowerCase(Locale.ROOT)).append('|')
                .append(engineId == null ? "" : engineId).append('|')
                .append(meaning.description).append('|');
        for (SongAnnotation annotation : meaning.allAnnotations()) {
            payload.append(annotation.cacheKey()).append('\n');
        }
        return sha256(payload.toString());
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
