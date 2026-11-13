package kr.ivlis.ivlyricsandroid;

/**
 * Colour tokens of the redesigned settings screen (ivLyrics settings lab): neutral surfaces and
 * one blue that is only used for state. Both themes are chosen manually; there is no automatic mode.
 */
final class SettingsPalette {
    static final String THEME_DARK = "dark";
    static final String THEME_LIGHT = "light";

    final boolean dark;
    final int bg;
    final int surface;
    final int surface2;
    final int surface3;
    final int surface4;
    final int raise;
    final int line;
    final int line2;
    final int line3;
    final int text;
    final int text2;
    final int text3;
    final int text4;
    final int accent;
    final int accentText;
    final int accentSoft;
    final int accentLine;
    final int accentInk;
    final int ink;
    final int inkText;
    final int ok;
    final int okSoft;
    final int warn;
    final int warnSoft;
    final int danger;
    final int dangerSoft;
    final int info;
    final int infoSoft;
    final int switchOff;
    final int ripple;
    final int layerOriginal;
    final int layerPhonetic;
    final int layerTranslation;

    // HyperOS dark: black canvas, near-black cards with 20 dp corners, one blue accent.
    static final SettingsPalette DARK = new SettingsPalette(true,
            0xFF000000, 0xFF0E0E10, 0xFF161618, 0xFF1D1D20, 0xFF26262A, 0xFF1A1A1D,
            0x0FFFFFFF, 0x1AFFFFFF, 0x2BFFFFFF,
            0xFFFFFFFF, 0xFFB6B6BE, 0xFF8A8A93, 0xFF5C5C64,
            0xFF3482FF, 0xFF7FB0FF, 0x243482FF, 0x8C3482FF, 0xFFFFFFFF,
            0xFFFFFFFF, 0xFF000000,
            0xFF32D74B, 0x1F32D74B, 0xFFFFB020, 0x1FFFB020, 0xFFFF453A, 0x1FFF453A,
            0xFF7FB0FF, 0x1A3482FF, 0xFF3A3A3C, 0x24FFFFFF,
            0xFFD4D4D8, 0xFFE9A23B, 0xFF3FBF8A);

    static final SettingsPalette LIGHT = new SettingsPalette(false,
            0xFFF2F3F5, 0xFFFFFFFF, 0xFFF7F7F9, 0xFFEFEFF2, 0xFFE4E4E8, 0xFFFFFFFF,
            0x12000000, 0x1C000000, 0x2E000000,
            0xFF111114, 0xFF57575F, 0xFF6E6E77, 0xFFA6A6AE,
            0xFF3482FF, 0xFF1F6BEF, 0x143482FF, 0x733482FF, 0xFFFFFFFF,
            0xFF18181B, 0xFFFFFFFF,
            0xFF1A7F37, 0x171A7F37, 0xFF9A6700, 0x1A9A6700, 0xFFCF222E, 0x14CF222E,
            0xFF1D4ED8, 0x122563EB, 0xFFD4D4D9, 0x1A000000,
            0xFF3F3F46, 0xFFC27612, 0xFF15845A);

    // Over a darkened cover colour: no neutral cards, only translucent white layers, as the bulb
    // sheet draws them.
    static final SettingsPalette ARTWORK = new SettingsPalette(true,
            0x00000000, 0x12FFFFFF, 0x1FFFFFFF, 0x2BFFFFFF, 0x33FFFFFF, 0x0FFFFFFF,
            0x1AFFFFFF, 0x24FFFFFF, 0x33FFFFFF,
            0xFFFFFFFF, 0xE6FFFFFF, 0xA6FFFFFF, 0x70FFFFFF,
            0xFF9CC2FF, 0xFF0B1020, 0x269CC2FF, 0x8C9CC2FF, 0xFFFFFFFF,
            0xFFFFFFFF, 0xFF000000,
            0xFF7BE0A3, 0x1F7BE0A3, 0xFFFFC65C, 0x1FFFC65C, 0xFFFF7B72, 0x1FFF7B72,
            0xFF9CC2FF, 0x1A9CC2FF, 0x4DFFFFFF, 0x24FFFFFF,
            0xFFD4D4D8, 0xFFE9A23B, 0xFF3FBF8A);

    private SettingsPalette(
            boolean dark,
            int bg, int surface, int surface2, int surface3, int surface4, int raise,
            int line, int line2, int line3,
            int text, int text2, int text3, int text4,
            int accent, int accentText, int accentSoft, int accentLine, int accentInk,
            int ink, int inkText,
            int ok, int okSoft, int warn, int warnSoft, int danger, int dangerSoft,
            int info, int infoSoft, int switchOff, int ripple,
            int layerOriginal, int layerPhonetic, int layerTranslation
    ) {
        this.dark = dark;
        this.bg = bg;
        this.surface = surface;
        this.surface2 = surface2;
        this.surface3 = surface3;
        this.surface4 = surface4;
        this.raise = raise;
        this.line = line;
        this.line2 = line2;
        this.line3 = line3;
        this.text = text;
        this.text2 = text2;
        this.text3 = text3;
        this.text4 = text4;
        this.accent = accent;
        this.accentText = accentText;
        this.accentSoft = accentSoft;
        this.accentLine = accentLine;
        this.accentInk = accentInk;
        this.ink = ink;
        this.inkText = inkText;
        this.ok = ok;
        this.okSoft = okSoft;
        this.warn = warn;
        this.warnSoft = warnSoft;
        this.danger = danger;
        this.dangerSoft = dangerSoft;
        this.info = info;
        this.infoSoft = infoSoft;
        this.switchOff = switchOff;
        this.ripple = ripple;
        this.layerOriginal = layerOriginal;
        this.layerPhonetic = layerPhonetic;
        this.layerTranslation = layerTranslation;
    }

    static SettingsPalette forTheme(String theme) {
        return THEME_LIGHT.equals(theme) ? LIGHT : DARK;
    }

    String theme() {
        return dark ? THEME_DARK : THEME_LIGHT;
    }
}
