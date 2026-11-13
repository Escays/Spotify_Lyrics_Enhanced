package dev.ivlyrics.spotify;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Function;

/**
 * The sing-along control, drawn as the same pill as the translation toggle beside it: same corner
 * radius, padding, type scale and idle background. It used to be a round microphone button that also
 * hid itself after three seconds, which read as a different kind of control and looked like the
 * feature kept disappearing.
 *
 * <p>Owned and packaged only by the Spotify module.</p>
 */
final class SpotifyKaraokeToolbar extends FrameLayout {
    private static final int ACCENT = 0xff1ed760;
    private static final int PILL_BG = 0x22ffffff;
    private static final int ACTIVE_BG = 0x331ed760;

    private final SpotifyKaraokeController controller;
    /** Looks up sing_along.* text in the app's UI language. */
    private final Function<String, String> text;
    private final TextView pill;
    private SpotifyKaraokeController.State state = SpotifyKaraokeController.State.OFF;
    private boolean available;
    private String reason = "WAITING";
    private String lastError = "";
    private Runnable onLongPress;

    SpotifyKaraokeToolbar(Context context, SpotifyKaraokeController controller, Function<String, String> text) {
        super(context);
        this.controller = controller;
        this.text = text;
        setTag("ivLyricsKaraokeToolbar");
        pill = new TextView(context);
        pill.setTag("ivLyricsKaraokeToggle");
        pill.setTextSize(11f);
        pill.setTypeface(pill.getTypeface(), Typeface.BOLD);
        pill.setTextColor(0xe8ffffff);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setIncludeFontPadding(true);
        pill.setBackground(ripple(PILL_BG));
        pill.setText(text.apply("sing_along.button"));
        addView(pill, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        pill.setOnClickListener(view -> {
            if (!controller.visible()) {
                return;
            }
            if (!available && state != SpotifyKaraokeController.State.LOW
                    && state != SpotifyKaraokeController.State.HIGH
                    && state != SpotifyKaraokeController.State.LOADING
                    && state != SpotifyKaraokeController.State.ERROR) {
                Toast.makeText(context, reasonText(reason), Toast.LENGTH_SHORT).show();
                return;
            }
            controller.cycle();
        });
        pill.setOnLongClickListener(view -> {
            if (onLongPress == null) {
                return false;
            }
            onLongPress.run();
            return true;
        });
        render(state, false, "WAITING");
    }

    /** Long press opens the per-song settings, the same sheet the title uses. */
    void setOnLongPressListener(Runnable action) {
        onLongPress = action;
    }

    void render(SpotifyKaraokeController.State next, boolean canEnable, String detail) {
        state = next;
        available = canEnable;
        reason = detail;
        if (!controller.visible()) {
            // No sing-along for this track or account: gone, without leaving a gap in the row.
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        boolean enabled = next == SpotifyKaraokeController.State.LOW
                || next == SpotifyKaraokeController.State.HIGH;
        boolean waiting = next == SpotifyKaraokeController.State.LOADING
                || next == SpotifyKaraokeController.State.STOPPING;
        pill.setTextColor(enabled ? ACCENT : 0xe8ffffff);
        pill.setBackground(ripple(enabled ? ACTIVE_BG : PILL_BG));
        pill.setAlpha(waiting ? 0.6f : (canEnable || enabled ? 1f : 0.45f));
        pill.setEnabled(next != SpotifyKaraokeController.State.STOPPING);
        String label = next == SpotifyKaraokeController.State.LOW ? "sing_along.state_low"
                : next == SpotifyKaraokeController.State.HIGH ? "sing_along.state_high"
                : next == SpotifyKaraokeController.State.LOADING ? "sing_along.cancel"
                : next == SpotifyKaraokeController.State.STOPPING ? "sing_along.stopping"
                : "sing_along.state_off";
        String description = text.apply("sing_along.button") + " - " + text.apply(label);
        if (!canEnable && !enabled && !waiting && detail != null && !detail.isEmpty()) {
            description += ": " + reasonText(detail);
        }
        pill.setContentDescription(description);
        pill.setTooltipText(description);
        if (next == SpotifyKaraokeController.State.ERROR && !detail.equals(lastError)) {
            lastError = detail;
            Toast.makeText(getContext(), reasonText(detail), Toast.LENGTH_LONG).show();
        } else if (next != SpotifyKaraokeController.State.ERROR) {
            lastError = "";
        }
    }

    /**
     * The pill is persistent now, so this only re-asserts visibility. Kept because the page calls it
     * on resume and on touch.
     */
    void revealTemporarily() {
        if (controller.visible() && getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
    }

    private String reasonText(String value) {
        String key;
        switch (value == null ? "" : value) {
            case "NO_TRACK": key = "sing_along.no_track"; break;
            case "REMOTE": key = "sing_along.remote"; break;
            case "OFFLINE": key = "sing_along.offline"; break;
            case "LOW_QUALITY": key = "sing_along.quality"; break;
            case "UNSUPPORTED": key = "sing_along.version"; break;
            case "UNSUPPORTED_TRACK": key = "sing_along.unsupported_track"; break;
            case "UNSUPPORTED_ACCOUNT": key = "sing_along.unsupported_account"; break;
            case "MASK_ERROR": key = "sing_along.mask_error"; break;
            case "TIMEOUT": key = "sing_along.timeout"; break;
            case "RESET_FAILED": key = "sing_along.reset_failed"; break;
            case "SERVICE_ERROR": key = "sing_along.error"; break;
            default: key = "sing_along.waiting";
        }
        return text.apply(key);
    }

    private RippleDrawable ripple(int color) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(dp(11));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xffffffff);
        mask.setCornerRadius(dp(11));
        return new RippleDrawable(ColorStateList.valueOf(0x24ffffff), fill, mask);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
