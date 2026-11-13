package kr.ivlis.ivlyricsandroid;

import android.content.Context;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the module's outside dependencies are reachable from, and what to do about it.
 *
 * <p>Nothing here guesses the user's country: a proxy makes every heuristic wrong. The preset is a
 * set of defaults the reader can change, and the probe is a plain "can I reach this" report so a
 * failure is never mistaken for a broken feature.
 */
final class NetworkRegion {
    static final String CN = "cn";
    static final String GLOBAL = "global";
    static final String CUSTOM = "custom";

    private static final String PREFS = "network_region";
    private static final String KEY_REGION = "region";
    private static final int TIMEOUT_MS = 3_000;

    private NetworkRegion() {
    }

    static String region(Context context) {
        if (context == null) {
            return GLOBAL;
        }
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REGION, GLOBAL);
        return value == null || value.isEmpty() ? GLOBAL : value;
    }

    static void setRegion(Context context, String id) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_REGION, id == null ? GLOBAL : id).apply();
    }

    /**
     * Applies the defaults for a region. Only defaults: the reader can still change each one, which
     * is why this never runs on its own.
     */
    static void applyPreset(Context context, LyricsPageSettings pageSettings) {
        if (context == null || pageSettings == null) {
            return;
        }
        String region = region(context);
        if (CN.equals(region)) {
            // Google's keyless endpoint is not reachable from the mainland; Bing is.
            pageSettings.setMeaningTranslationEngine(KeylessTranslationProviders.BING_ID);
        } else if (GLOBAL.equals(region)) {
            pageSettings.setMeaningTranslationEngine(LyricsPageSettings.MEANING_ENGINE_AUTO);
        }
    }

    /** One endpoint the module talks to, and what the reader loses when it is unreachable. */
    static final class Endpoint {
        final String id;
        final String label;
        final String url;
        final String lossWhenDown;

        Endpoint(String id, String label, String url, String lossWhenDown) {
            this.id = id;
            this.label = label;
            this.url = url;
            this.lossWhenDown = lossWhenDown;
        }
    }

    static final class Result {
        final Endpoint endpoint;
        final boolean reachable;
        final long latencyMs;
        final String error;

        Result(Endpoint endpoint, boolean reachable, long latencyMs, String error) {
            this.endpoint = endpoint;
            this.reachable = reachable;
            this.latencyMs = latencyMs;
            this.error = error == null ? "" : error;
        }
    }

    static List<Endpoint> endpoints() {
        List<Endpoint> list = new ArrayList<>();
        list.add(new Endpoint("netease", "网易云音乐", "https://music.163.com", "中文译词"));
        list.add(new Endpoint("qq", "QQ 音乐", "https://y.qq.com", "中文译词"));
        list.add(new Endpoint("kugou", "酷狗音乐", "https://www.kugou.com", "中文译词"));
        list.add(new Endpoint("lrclib", "LRCLIB", "https://lrclib.net", "歌词时间轴"));
        list.add(new Endpoint("lrc_red", "lrc.red", "https://lrc.red", "歌词时间轴"));
        list.add(new Endpoint("unison", "Unison", "https://unison.boidu.dev", "歌词时间轴"));
        list.add(new Endpoint("genius", "Genius", "https://genius.com", "歌曲简介与注释"));
        list.add(new Endpoint("google", "Google 翻译", "https://translate.googleapis.com", "谷歌翻译引擎"));
        list.add(new Endpoint("github", "GitHub", "https://github.com", "版本更新检查"));
        return list;
    }

    /** Blocking: call from a worker thread and hand the list back to the UI. */
    static List<Result> probeAll() {
        List<Result> results = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            long started = System.currentTimeMillis();
            boolean ok;
            String error = "";
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint.url).openConnection();
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("HEAD");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = connection.getResponseCode();
                ok = code > 0 && code < 500;
            } catch (IOException failure) {
                ok = false;
                error = failure.getClass().getSimpleName();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            results.add(new Result(endpoint, ok, System.currentTimeMillis() - started, error));
        }
        return results;
    }
}
