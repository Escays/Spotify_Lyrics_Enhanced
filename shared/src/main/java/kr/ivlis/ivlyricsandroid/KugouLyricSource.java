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
 * Kugou Music: public search plus the krcs lyric lookup.
 *
 * <p>Kugou publishes its translation only inside the encrypted KRC container; the plain LRC download
 * carries the original rows alone. This source therefore returns the original rows with an empty
 * translation list, and the settings page reports exactly that ("found the recording, no usable
 * Chinese") instead of pretending the platform had nothing. It is still a real third source: a song
 * whose Chinese lives only on Kugou can be identified, and the row alignment is ready if a translation
 * endpoint becomes available.
 */
final class KugouLyricSource implements PlatformLyricSource {
    static final String ID = "kugou";
    static final String LABEL = "酷狗音乐";

    private static final String SEARCH_ENDPOINT = "http://mobilecdn.kugou.com/api/v3/search/song";
    private static final String LYRIC_SEARCH_ENDPOINT = "http://krcs.kugou.com/search";
    private static final String LYRIC_DOWNLOAD_ENDPOINT = "http://lyrics.kugou.com/download";
    private static final Map<String, String> HEADERS = PlatformHttpTransport.headers(
            "Accept", "application/json",
            "User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"
    );

    private final PlatformHttpTransport.Transport transport;

    KugouLyricSource(PlatformHttpTransport.Transport transport) {
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
    public List<PlatformRecordingMatcher.Candidate> search(String query, int limit) throws Exception {
        String url = SEARCH_ENDPOINT + "?format=json&keyword=" + encode(query)
                + "&page=1&pagesize=" + Math.max(1, limit) + "&showtype=1";
        PlatformHttpTransport.Response response = transport.get(url, HEADERS);
        if (!response.ok()) {
            throw new IOException(LABEL + " search failed (" + response.status + ")");
        }
        JSONObject root = new JSONObject(response.body);
        JSONObject data = root.optJSONObject("data");
        JSONArray info = data == null ? null : data.optJSONArray("info");
        List<PlatformRecordingMatcher.Candidate> candidates = new ArrayList<>();
        if (info == null) {
            return candidates;
        }
        for (int index = 0; index < info.length(); index++) {
            JSONObject item = info.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String hash = item.optString("hash", "");
            if (hash.isEmpty()) {
                continue;
            }
            candidates.add(new PlatformRecordingMatcher.Candidate(
                    hash,
                    item.optString("songname", ""),
                    item.optString("singername", ""),
                    item.optString("album_name", ""),
                    item.optLong("duration", 0L) * 1_000L
            ));
        }
        return candidates;
    }

    @Override
    public PlatformLyricDocument fetch(String candidateId) throws Exception {
        String searchUrl = LYRIC_SEARCH_ENDPOINT + "?ver=1&man=yes&client=mobi&keyword=&duration=&hash="
                + encode(candidateId);
        PlatformHttpTransport.Response searchResponse = transport.get(searchUrl, HEADERS);
        if (!searchResponse.ok()) {
            throw new IOException(LABEL + " lyric lookup failed (" + searchResponse.status + ")");
        }
        JSONObject searchRoot = new JSONObject(searchResponse.body);
        JSONArray candidates = searchRoot.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return new PlatformLyricDocument(ID, LABEL, candidateId, "", "", "", 0L,
                    new ArrayList<>(), new ArrayList<>(), false);
        }
        JSONObject best = candidates.optJSONObject(0);
        String id = best == null ? "" : best.optString("id", "");
        String accessKey = best == null ? "" : best.optString("accesskey", "");
        if (id.isEmpty() || accessKey.isEmpty()) {
            return new PlatformLyricDocument(ID, LABEL, candidateId, "", "", "", 0L,
                    new ArrayList<>(), new ArrayList<>(), false);
        }
        String downloadUrl = LYRIC_DOWNLOAD_ENDPOINT + "?ver=1&client=pc&id=" + encode(id)
                + "&accesskey=" + encode(accessKey) + "&fmt=lrc&charset=utf8";
        PlatformHttpTransport.Response download = transport.get(downloadUrl, HEADERS);
        if (!download.ok()) {
            throw new IOException(LABEL + " lyric download failed (" + download.status + ")");
        }
        JSONObject root = new JSONObject(download.body);
        String content = decode(root.optString("content", ""));
        return new PlatformLyricDocument(
                ID,
                LABEL,
                candidateId,
                best.optString("song", ""),
                best.optString("singer", ""),
                "",
                0L,
                PlatformLyricRow.parseLrc(content),
                new ArrayList<>(),
                false
        );
    }

    private static String decode(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return value;
        }
    }

    private static String encode(String value) {
        try {
            // Kugou (and some QQ endpoints) read "+" literally; %20 is a space everywhere.
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
