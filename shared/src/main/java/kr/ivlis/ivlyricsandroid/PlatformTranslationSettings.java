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

    static final List<String> SOURCE_IDS = Collections.unmodifiableList(
            java.util.Arrays.asList(NeteaseLyricSource.ID, QqLyricSource.ID));

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
        List<String> order = new ArrayList<>(snapshot.order);
        if (!MANUAL_AUTO.equals(manual)) {
            order.remove(manual);
            order.add(0, manual);
        }
        return order;
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
