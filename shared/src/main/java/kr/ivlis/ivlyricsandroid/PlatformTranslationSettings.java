package kr.ivlis.ivlyricsandroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * User-facing policy for the platform translation sources: the enable switch, which platform is asked
 * first, and the per-song override. The override is remembered per track so a manually chosen source
 * survives a page reopen without changing the global order.
 */
final class PlatformTranslationSettings {
    static final String PREFS = "platform_translation";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_ORDER = "source_order";
    static final String MANUAL_AUTO = "auto";
    static final String MANUAL_OFF = "off";
    private static final String KEY_MANUAL_PREFIX = "manual_source:";
    private static final String KEY_SELECTED_PREFIX = "selected_source:";

    static final List<String> SOURCE_IDS = Collections.unmodifiableList(java.util.Arrays.asList(
            NeteaseLyricSource.ID, QqLyricSource.ID, KugouLyricSource.ID));
    private static final String KEY_ENABLED_PREFIX = "source_enabled:";
    static final String KEY_PLATFORM_ONLY = "platform_only";
    /** When off, the first source that fills anything owns the whole translation. */
    static final String KEY_MIX = "mix_sources";
    /** Global translation source: "auto" follows the order, otherwise one source leads. */
    static final String KEY_GLOBAL_SOURCE = "global_source";
    /** Per-song switch: "true"/"false"; absent means follow the global setting. */
    private static final String KEY_TRACK_ENABLED_PREFIX = "track_enabled:";

    private final SharedPreferences prefs;
    private volatile Snapshot cached;

    PlatformTranslationSettings(Context context) {
        prefs = context == null
                ? null
                : context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    PlatformTranslationSettings(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static boolean isChineseTarget(String targetLanguage) {
        String value = targetLanguage == null ? "" : targetLanguage.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("zh");
    }

    static String label(String sourceId) {
        if (NeteaseLyricSource.ID.equals(sourceId)) {
            return NeteaseLyricSource.LABEL;
        }
        if (QqLyricSource.ID.equals(sourceId)) {
            return QqLyricSource.LABEL;
        }
        if (KugouLyricSource.ID.equals(sourceId)) {
            return KugouLyricSource.LABEL;
        }
        return "";
    }

    Snapshot snapshot() {
        Snapshot current = cached;
        if (current != null) {
            return current;
        }
        boolean enabled = prefs == null || prefs.getBoolean(KEY_ENABLED, true);
        List<String> order = new ArrayList<>();
        String stored = prefs == null ? "" : prefs.getString(KEY_ORDER, "");
        if (stored != null && !stored.trim().isEmpty()) {
            for (String part : stored.split(",")) {
                String id = normalizeSource(part);
                if (!id.isEmpty() && !order.contains(id)) {
                    order.add(id);
                }
            }
        }
        for (String id : SOURCE_IDS) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        Snapshot snapshot = new Snapshot(enabled, order);
        cached = snapshot;
        return snapshot;
    }

    private static String normalizeSource(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return SOURCE_IDS.contains(id) ? id : "";
    }

    void setEnabled(boolean enabled) {
        cached = null;
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        }
    }

    String globalSource() {
        if (prefs == null) {
            return MANUAL_AUTO;
        }
        String value = prefs.getString(KEY_GLOBAL_SOURCE, MANUAL_AUTO);
        String normalized = value == null ? MANUAL_AUTO : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? MANUAL_AUTO : normalized;
    }

    void setGlobalSource(String sourceId) {
        if (prefs == null) {
            return;
        }
        String value = sourceId == null || sourceId.trim().isEmpty()
                ? MANUAL_AUTO : sourceId.trim().toLowerCase(Locale.ROOT);
        prefs.edit().putString(KEY_GLOBAL_SOURCE, value).apply();
    }

    boolean mixSources() {
        return prefs == null || prefs.getBoolean(KEY_MIX, true);
    }

    void setMixSources(boolean mix) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_MIX, mix).apply();
        }
    }

    /** null means "follow the global switch". */
    Boolean trackEnabled(String trackKey) {
        if (prefs == null || trackKey == null || trackKey.isEmpty()) {
            return null;
        }
        String stored;
        try {
            stored = prefs.getString(KEY_TRACK_ENABLED_PREFIX + trackKey, "");
        } catch (ClassCastException wrongType) {
            // An earlier build wrote a boolean under this key; treat it as "not set".
            prefs.edit().remove(KEY_TRACK_ENABLED_PREFIX + trackKey).apply();
            return null;
        }
        if ("true".equals(stored)) {
            return Boolean.TRUE;
        }
        if ("false".equals(stored)) {
            return Boolean.FALSE;
        }
        return null;
    }

    void setTrackEnabled(String trackKey, Boolean enabled) {
        if (prefs == null || trackKey == null || trackKey.isEmpty()) {
            return;
        }
        if (enabled == null) {
            prefs.edit().remove(KEY_TRACK_ENABLED_PREFIX + trackKey).apply();
            return;
        }
        // Stored as a string, which is what trackEnabled() reads back.
        prefs.edit().putString(KEY_TRACK_ENABLED_PREFIX + trackKey, enabled ? "true" : "false").apply();
    }

    boolean enabledForTrack(String trackKey) {
        Boolean override = trackEnabled(trackKey);
        return override != null ? override : enabled();
    }

    boolean enabled() {
        return snapshot().enabled;
    }

    void setOrder(List<String> order) {
        List<String> cleaned = new ArrayList<>();
        for (String id : order == null ? Collections.<String>emptyList() : order) {
            String normalized = normalizeSource(id);
            if (!normalized.isEmpty() && !cleaned.contains(normalized)) {
                cleaned.add(normalized);
            }
        }
        for (String id : SOURCE_IDS) {
            if (!cleaned.contains(id)) {
                cleaned.add(id);
            }
        }
        cached = null;
        if (prefs != null) {
            prefs.edit().putString(KEY_ORDER, String.join(",", cleaned)).apply();
        }
    }

    /** Puts {@code sourceId} first without dropping the other source from the fallback chain. */
    void preferSource(String sourceId) {
        String id = normalizeSource(sourceId);
        if (id.isEmpty()) {
            return;
        }
        List<String> order = new ArrayList<>(snapshot().order);
        order.remove(id);
        order.add(0, id);
        setOrder(order);
    }

    String manualSource(String trackKey) {
        if (prefs == null || trackKey == null || trackKey.isEmpty()) {
            return MANUAL_AUTO;
        }
        String value = prefs.getString(KEY_MANUAL_PREFIX + trackKey, MANUAL_AUTO);
        String normalized = value == null ? MANUAL_AUTO : value.trim().toLowerCase(Locale.ROOT);
        if (MANUAL_OFF.equals(normalized)) {
            return MANUAL_OFF;
        }
        return normalizeSource(normalized).isEmpty() ? MANUAL_AUTO : normalized;
    }

    void setManualSource(String trackKey, String sourceId) {
        if (prefs == null || trackKey == null || trackKey.isEmpty()) {
            return;
        }
        String value = MANUAL_OFF.equals(sourceId) ? MANUAL_OFF : normalizeSource(sourceId);
        if (value.isEmpty()) {
            value = MANUAL_AUTO;
        }
        if (MANUAL_AUTO.equals(value)) {
            prefs.edit().remove(KEY_MANUAL_PREFIX + trackKey).apply();
        } else {
            prefs.edit().putString(KEY_MANUAL_PREFIX + trackKey, value).apply();
        }
    }

    /** The source that actually supplied the most lines for this track, for the attribution chip. */
    String selectedSource(String trackKey) {
        if (prefs == null || trackKey == null || trackKey.isEmpty()) {
            return "";
        }
        return prefs.getString(KEY_SELECTED_PREFIX + trackKey, "");
    }

    void setSelectedSource(String trackKey, String sourceId) {
        if (prefs == null || trackKey == null || trackKey.isEmpty()) {
            return;
        }
        if (sourceId == null || sourceId.isEmpty()) {
            prefs.edit().remove(KEY_SELECTED_PREFIX + trackKey).apply();
        } else {
            prefs.edit().putString(KEY_SELECTED_PREFIX + trackKey, sourceId).apply();
        }
    }

    /** The order a lookup should use for one track, honouring the per-song override. */
    List<String> orderForTrack(String trackKey) {
        Snapshot snapshot = snapshot();
        if (!snapshot.enabled) {
            return Collections.emptyList();
        }
        String manual = manualSource(trackKey);
        if (MANUAL_OFF.equals(manual)) {
            return Collections.emptyList();
        }
        List<String> order = new ArrayList<>();
        for (String id : snapshot.order) {
            if (isSourceEnabled(id)) {
                order.add(id);
            }
        }
        if (MANUAL_AUTO.equals(manual)) {
            // No per-song choice: the global source leads, the rest still backs it up.
            String globalSource = globalSource();
            if (!MANUAL_AUTO.equals(globalSource) && !MANUAL_OFF.equals(globalSource)) {
                order.remove(globalSource);
                order.add(0, globalSource);
            }
        }
        if (!MANUAL_AUTO.equals(manual)) {
            if (!order.contains(manual)) {
                // A manual choice still runs even when its global switch is off.
                order.add(0, manual);
            } else {
                order.remove(manual);
                order.add(0, manual);
            }
        }
        return order;
    }

    boolean isSourceEnabled(String sourceId) {
        if (prefs == null) {
            return true;
        }
        return prefs.getBoolean(KEY_ENABLED_PREFIX + sourceId, true);
    }

    void setSourceEnabled(String sourceId, boolean enabled) {
        if (prefs == null || sourceId == null || sourceId.isEmpty()) {
            return;
        }
        prefs.edit().putBoolean(KEY_ENABLED_PREFIX + sourceId, enabled).apply();
        cached = null;
    }

    /**
     * When set, platform translations are the only source: lines the platforms cannot cover stay
     * empty instead of going to the machine/AI stage.
     */
    boolean platformOnly() {
        return prefs != null && prefs.getBoolean(KEY_PLATFORM_ONLY, false);
    }

    void setPlatformOnly(boolean platformOnly) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_PLATFORM_ONLY, platformOnly).apply();
        }
    }

    static final class Snapshot {
        final boolean enabled;
        final List<String> order;

        Snapshot(boolean enabled, List<String> order) {
            this.enabled = enabled;
            this.order = Collections.unmodifiableList(new ArrayList<>(order));
        }
    }

    /** Diagnostics for the provider log, so the user can see what was searched and what was found. */
    static void log(String message) {
        Log.d("ivLyricsDebug", "platform translation: " + message);
    }
}
