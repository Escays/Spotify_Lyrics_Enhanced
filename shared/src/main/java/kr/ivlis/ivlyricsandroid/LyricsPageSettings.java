package kr.ivlis.ivlyricsandroid;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted choices for the full lyrics page: the side margin, the per-song solid background and
 * whether Genius meanings are enabled.
 *
 * <p>These live beside the shared appearance settings instead of inside them so the module keeps its
 * own defaults (24 dp, matching the reference full-page layout) without rewriting the shared appearance
 * contract that the standalone app and the cloud sync already use.
 */
final class LyricsPageSettings {
    static final String PREFS = "lyrics_page_settings";
    static final int DEFAULT_SIDE_PADDING_DP = 24;
    static final int MIN_SIDE_PADDING_DP = 0;
    static final int MAX_SIDE_PADDING_DP = 64;

    private static final String KEY_SIDE_PADDING_DP = "side_padding_dp";
    private static final String KEY_ARTWORK_SOLID = "artwork_solid_background";
    private static final String KEY_MEANINGS_ENABLED = "genius_meanings_enabled";

    private final SharedPreferences prefs;
    private volatile Snapshot cached;

    LyricsPageSettings(Context context) {
        prefs = context == null
                ? null
                : context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Snapshot snapshot() {
        Snapshot current = cached;
        if (current != null) {
            return current;
        }
        int padding = prefs == null
                ? DEFAULT_SIDE_PADDING_DP
                : prefs.getInt(KEY_SIDE_PADDING_DP, DEFAULT_SIDE_PADDING_DP);
        boolean artworkSolid = prefs != null && prefs.getBoolean(KEY_ARTWORK_SOLID, false);
        boolean meanings = prefs == null || prefs.getBoolean(KEY_MEANINGS_ENABLED, true);
        Snapshot snapshot = new Snapshot(clampPadding(padding), artworkSolid, meanings);
        cached = snapshot;
        return snapshot;
    }

    static int clampPadding(int value) {
        return Math.max(MIN_SIDE_PADDING_DP, Math.min(MAX_SIDE_PADDING_DP, value));
    }

    int sidePaddingDp() {
        return snapshot().sidePaddingDp;
    }

    boolean artworkSolidBackground() {
        return snapshot().artworkSolidBackground;
    }

    boolean meaningsEnabled() {
        return snapshot().meaningsEnabled;
    }

    void setSidePaddingDp(int value) {
        cached = null;
        if (prefs != null) {
            prefs.edit().putInt(KEY_SIDE_PADDING_DP, clampPadding(value)).apply();
        }
    }

    void setArtworkSolidBackground(boolean enabled) {
        cached = null;
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_ARTWORK_SOLID, enabled).apply();
        }
    }

    void setMeaningsEnabled(boolean enabled) {
        cached = null;
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_MEANINGS_ENABLED, enabled).apply();
        }
    }

    static final class Snapshot {
        final int sidePaddingDp;
        final boolean artworkSolidBackground;
        final boolean meaningsEnabled;

        Snapshot(int sidePaddingDp, boolean artworkSolidBackground, boolean meaningsEnabled) {
            this.sidePaddingDp = sidePaddingDp;
            this.artworkSolidBackground = artworkSolidBackground;
            this.meaningsEnabled = meaningsEnabled;
        }
    }
}
