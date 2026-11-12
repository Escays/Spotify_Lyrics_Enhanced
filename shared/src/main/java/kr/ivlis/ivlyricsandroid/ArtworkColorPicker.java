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
    private static final float MIN_VALUE = 0.22f;
    private static final float MAX_VALUE = 0.40f;
    private static final float MIN_SATURATION = 0.24f;
    private static final float MAX_SATURATION = 0.66f;
    /** Text colour the background has to stay readable against. */
    static final int LYRIC_TEXT_COLOR = 0xFFFFFFFF;

    private ArtworkColorPicker() {
    }

    /** A colour for the track; a deterministic dark tone when there is no usable artwork yet. */
    static int pick(Bitmap artwork, String fallbackKey) {
        if (artwork == null || artwork.isRecycled() || artwork.getWidth() <= 0 || artwork.getHeight() <= 0) {
            return fallbackFor(fallbackKey);
        }
        int stepX = Math.max(1, artwork.getWidth() / SAMPLE_GRID);
        int stepY = Math.max(1, artwork.getHeight() / SAMPLE_GRID);
        double weightSum = 0d;
        double redSum = 0d;
        double greenSum = 0d;
        double blueSum = 0d;
        float[] hsv = new float[3];
        for (int y = 0; y < artwork.getHeight(); y += stepY) {
            for (int x = 0; x < artwork.getWidth(); x += stepX) {
                int pixel = artwork.getPixel(x, y);
                if (Color.alpha(pixel) < 160) {
                    continue;
                }
                Color.colorToHSV(pixel, hsv);
                // Ignore the studio-white and pure-black frames most covers are padded with.
                if (hsv[2] > 0.93f || hsv[2] < 0.05f) {
                    continue;
                }
                double weight = 0.35d + hsv[1] * 1.6d;
                weightSum += weight;
                redSum += Color.red(pixel) * weight;
                greenSum += Color.green(pixel) * weight;
                blueSum += Color.blue(pixel) * weight;
            }
        }
        if (weightSum <= 0d) {
            return fallbackFor(fallbackKey);
        }
        return readable((int) Math.round(redSum / weightSum),
                (int) Math.round(greenSum / weightSum),
                (int) Math.round(blueSum / weightSum));
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
