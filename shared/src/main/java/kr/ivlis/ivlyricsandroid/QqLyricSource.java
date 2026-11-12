package kr.ivlis.ivlyricsandroid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * QQ Music: the legacy JSONP search endpoint for recordings and the {@code musicu} lyric module for the
 * original, translated and romanised rows. No account, no client id, no per-user credential.
 *
 * <p>QQ marks "translation exists but this line has none" with {@code //} rows and reports a copyright
 * notice as the first translated row, so rows are filtered before they reach the aligner.
 */
final class QqLyricSource implements PlatformLyricSource {
    static final String ID = "qq";
    static final String LABEL = "QQ音乐";

    private static final String SEARCH_ENDPOINT = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp";
    private static final String LYRIC_ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final Map<String, String> SEARCH_HEADERS = PlatformHttpTransport.headers(
            "Referer", "https://y.qq.com/",
            "Accept", "application/json"
    );
    private static final Map<String, String> LYRIC_HEADERS = PlatformHttpTransport.headers(
            "Referer", "https://y.qq.com/portal/player.html",
            "Origin", "https://y.qq.com",
            "Content-Type", "application/json"
    );

    private final PlatformHttpTransport.Transport transport;

    QqLyricSource(PlatformHttpTransport.Transport transport) {
        this.transport = transport;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public List<PlatformRecordingMatcher.Candidate> search(String title, String artist, int limit)
            throws Exception {
        String query = encode(title + " " + artist);
        String url = SEARCH_ENDPOINT + "?p=1&n=" + Math.max(1, limit) + "&w=" + query + "&format=json";
        PlatformHttpTransport.Response response = transport.get(url, SEARCH_HEADERS);
        if (!response.ok()) {
            throw new IOException(LABEL + " search failed (" + response.status + ")");
        }
        JSONObject root = new JSONObject(PlatformHttpTransport.stripJsonp(response.body));
        JSONObject data = root.optJSONObject("data");
        JSONObject song = data == null ? null : data.optJSONObject("song");
        JSONArray list = song == null ? null : song.optJSONArray("list");
        List<PlatformRecordingMatcher.Candidate> candidates = new ArrayList<>();
        if (list == null) {
            return candidates;
        }
        for (int index = 0; index < list.length(); index++) {
            JSONObject item = list.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String mid = item.optString("mid", item.optString("songmid", ""));
            if (mid.isEmpty()) {
                continue;
            }
            candidates.add(new PlatformRecordingMatcher.Candidate(
                    mid,
                    item.optString("name", item.optString("songname", "")),
                    singers(item.optJSONArray("singer")),
                    item.optString("albumname", ""),
                    // QQ reports whole seconds.
                    item.optLong("interval", 0L) * 1_000L
            ));
        }
        return candidates;
    }

    @Override
    public PlatformLyricDocument fetch(String candidateId) throws Exception {
        JSONObject parameters = new JSONObject()
                .put("songMID", candidateId)
                .put("songID", 0)
                .put("trans", 1)
                .put("roma", 1)
                .put("type", 1);
        JSONObject body = new JSONObject().put("req_1", new JSONObject()
                .put("module", "music.musichallSong.PlayLyricInfo")
                .put("method", "GetPlayLyricInfo")
                .put("param", parameters));
        PlatformHttpTransport.Response response =
                transport.post(LYRIC_ENDPOINT, LYRIC_HEADERS, body.toString());
        if (!response.ok()) {
            throw new IOException(LABEL + " lyric failed (" + response.status + ")");
        }
        JSONObject root = new JSONObject(PlatformHttpTransport.stripJsonp(response.body));
        JSONObject request = root.optJSONObject("req_1");
        JSONObject data = request == null ? null : request.optJSONObject("data");
        if (data == null) {
            throw new IOException(LABEL + " lyric response has no data block");
        }
        String original = decode(data.optString("lyric", ""));
        String translated = decode(data.optString("trans", ""));
        String romanised = decode(data.optString("roma", ""));
        return new PlatformLyricDocument(
                ID,
                LABEL,
                candidateId,
                data.optString("songName", ""),
                data.optString("singerName", ""),
                "",
                0L,
                PlatformLyricRow.parseLrc(original),
                PlatformLyricRow.parseLrc(translated),
                !romanised.trim().isEmpty()
        );
    }

    private static String singers(JSONArray array) {
        if (array == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject singer = array.optJSONObject(index);
            if (singer != null) {
                String name = singer.optString("name", "").trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return String.join(" / ", names);
    }

    private static String decode(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return "";
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(text);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            // Some responses already carry plain LRC.
            return value;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
