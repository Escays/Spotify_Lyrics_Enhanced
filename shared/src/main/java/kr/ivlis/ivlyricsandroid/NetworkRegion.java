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
        list.add(new Endpoint("netease", "网易云音乐", "https://music.163.com", "network.loss_translation"));
        list.add(new Endpoint("qq", "QQ 音乐", "https://y.qq.com", "network.loss_translation"));
        list.add(new Endpoint("kugou", "酷狗音乐", "https://www.kugou.com", "network.loss_translation"));
        list.add(new Endpoint("lrclib", "LRCLIB", "https://lrclib.net", "network.loss_timeline"));
        list.add(new Endpoint("lrc_red", "lrc.red", "https://lrc.red", "network.loss_timeline"));
        list.add(new Endpoint("unison", "Unison", "https://unison.boidu.dev", "network.loss_timeline"));
        list.add(new Endpoint("genius", "Genius", "https://genius.com", "network.loss_meaning"));
        list.add(new Endpoint("google", "Google 翻译", "https://translate.googleapis.com", "network.loss_google"));
        list.add(new Endpoint("github", "GitHub", "https://github.com", "network.loss_update"));
        return list;
    }

    /**
     * Blocking, but all endpoints in flight at once: nine serial three-second timeouts would make a
     * bad network look like a frozen screen for half a minute.
     */
    static List<Result> probeAll() {
        List<Endpoint> endpoints = endpoints();
        Result[] results = new Result[endpoints.size()];
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(9, endpoints.size()));
        List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
        for (int index = 0; index < endpoints.size(); index++) {
            final int slot = index;
            final Endpoint endpoint = endpoints.get(index);
            pending.add(pool.submit(() -> results[slot] = probeOne(endpoint)));
        }
        for (java.util.concurrent.Future<?> future : pending) {
            try {
                future.get(TIMEOUT_MS + 1_500L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // A straggler stays null and is reported as unreachable below.
            }
        }
        pool.shutdownNow();
        List<Result> collected = new ArrayList<>();
        for (int index = 0; index < results.length; index++) {
            collected.add(results[index] != null ? results[index]
                    : new Result(endpoints.get(index), false, TIMEOUT_MS, "timeout"));
        }
        return collected;
    }

    private static Result probeOne(Endpoint endpoint) {
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
        return new Result(endpoint, ok, System.currentTimeMillis() - started, error);
    }
}
