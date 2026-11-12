package kr.ivlis.ivlyricsandroid;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Picks one readable, stable colour from cover art for the per-song solid background.
 *
 * <p>Average colour alone fails on white covers, on near-black covers and on artwork that is mostly
 * background. The picked colour therefore skips transparent, near-white and near-black samples, keeps
 * the cover's hue and saturation, and clamps brightness into a band where white lyric text keeps a
 * contrast ratio well above 4.5:1. The result depends only on the bitmap, so repainting the same cover
 * always yields the same colour.
 */
final class ArtworkColorPicker {
    private static final int SAMPLE_GRID = 48;
    /** Luminance ceiling for a colour that has to carry white text. */
    private static final float MAX_TINT_LUMINANCE = 0.14f;
    private static final float MIN_VALUE = 0.22f;
    private static final float MAX_VALUE = 0.40f;
    private static final float MIN_SATURATION = 0.24f;
    private static final float MAX_SATURATION = 0.66f;
    /** Text colour the background has to stay readable against. */
    static final int LYRIC_TEXT_COLOR = 0xFFFFFFFF;

    private ArtworkColorPicker() {
    }

    /**
     * A colour for the track, chosen the way the reference app does it: AndroidX Palette's
     * darkVibrant swatch, then darkMuted, then the dominant colour, then vibrant, and finally darkened
     * until white text reads on it. A weighted average (what this used to do) averages a cover's two
     * halves into a grey that matches neither.
     */
    static int pick(Bitmap artwork, String fallbackKey) {
        if (artwork == null || artwork.isRecycled() || artwork.getWidth() <= 0 || artwork.getHeight() <= 0) {
            return fallbackFor(fallbackKey);
        }
        Bitmap source = artwork;
        try {
            // The reference decodes the cover at 128 px before extracting; the same size keeps the
            // quantiser's buckets, and therefore the chosen swatch, identical.
            int longest = Math.max(artwork.getWidth(), artwork.getHeight());
            if (longest > 128) {
                float scale = 128f / longest;
                int scaledWidth = Math.max(1, Math.round(artwork.getWidth() * scale));
                int scaledHeight = Math.max(1, Math.round(artwork.getHeight() * scale));
                source = Bitmap.createScaledBitmap(artwork, scaledWidth, scaledHeight, true);
            }
            // AndroidX Palette with its default filters, and the reference's fallback order.
            androidx.palette.graphics.Palette palette =
                    androidx.palette.graphics.Palette.from(source).generate();
            androidx.palette.graphics.Palette.Swatch swatch = palette.getDarkVibrantSwatch();
            if (swatch == null) swatch = palette.getDarkMutedSwatch();
            if (swatch == null) swatch = palette.getDominantSwatch();
            if (swatch == null) swatch = palette.getVibrantSwatch();
            if (swatch == null) return fallbackFor(fallbackKey);
            return readableUnderWhite(swatch.getRgb());
        } catch (Throwable unavailable) {
            return fallbackFor(fallbackKey);
        } finally {
            if (source != artwork && source != null && !source.isRecycled()) {
                source.recycle();
            }
        }
    }

    /**
     * Darkens the colour, keeping its hue, until white text has about a 5.5:1 contrast — the same
     * luminance ceiling the reference uses for its tint.
     */
    static int readableUnderWhite(int color) {
        int out = color;
        double current = luminance(out);
        float step = 0f;
        while (current > MAX_TINT_LUMINANCE && step < 0.8f) {
            step += 0.05f;
            out = blend(color, Color.BLACK, step);
            current = luminance(out);
        }
        return out | 0xFF000000;
    }

    private static int blend(int from, int to, float fraction) {
        float t = Math.max(0f, Math.min(1f, fraction));
        int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int green = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int blue = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(255, clamp(red), clamp(green), clamp(blue));
    }

    /** Keeps the hue, makes the colour saturated enough to read as a colour, and dark enough for text. */
    static int readable(int red, int green, int blue) {
        float[] hsv = new float[3];
        Color.colorToHSV(Color.rgb(clamp(red), clamp(green), clamp(blue)), hsv);
        hsv[1] = clampValue(hsv[1], MIN_SATURATION, MAX_SATURATION);
        hsv[2] = clampValue(hsv[2], MIN_VALUE, MAX_VALUE);
        return Color.HSVToColor(hsv);
    }

    /**
     * A track without artwork still needs a colour, and it must not change while the song plays: the
     * hue comes from the track key, the brightness from the readable band.
     */
    static int fallbackFor(String key) {
        String value = key == null ? "" : key;
        int hash = 0;
        for (int index = 0; index < value.length(); index++) {
            hash = (hash * 31 + value.charAt(index)) & 0x7fffffff;
        }
        float hue = hash % 360;
        return Color.HSVToColor(new float[]{hue, 0.32f, 0.26f});
    }

    /** Relative luminance of a colour, used to keep lyrics readable on the picked background. */
    static double luminance(int color) {
        return 0.2126d * channel(Color.red(color))
                + 0.7152d * channel(Color.green(color))
                + 0.0722d * channel(Color.blue(color));
    }

    static double contrastRatio(int foreground, int background) {
        double lighter = Math.max(luminance(foreground), luminance(background));
        double darker = Math.min(luminance(foreground), luminance(background));
        return (lighter + 0.05d) / (darker + 0.05d);
    }

    private static double channel(int value) {
        double normalized = value / 255d;
        return normalized <= 0.03928d ? normalized / 12.92d : Math.pow((normalized + 0.055d) / 1.055d, 2.4d);
    }

    private static float clampValue(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
