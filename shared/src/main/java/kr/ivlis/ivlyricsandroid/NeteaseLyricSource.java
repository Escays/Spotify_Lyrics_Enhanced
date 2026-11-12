package kr.ivlis.ivlyricsandroid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NetEase Cloud Music: public search and lyric endpoints, no account and no client id.
 *
 * <p>Only the song id, the original LRC and the {@code tlyric} block are used. When NetEase answers with
 * its encrypted envelope instead of JSON the lookup fails loudly; a payload that cannot be read is never
 * treated as "this song has no translation".
 */
final class NeteaseLyricSource implements PlatformLyricSource {
    static final String ID = "netease";
    static final String LABEL = "网易云音乐";

    private static final String SEARCH_ENDPOINT = "https://music.163.com/api/search/get";
    private static final String LYRIC_ENDPOINT = "https://music.163.com/api/song/lyric";
    private static final Map<String, String> HEADERS = PlatformHttpTransport.headers(
            "Referer", "https://music.163.com/",
            "Accept", "application/json"
    );

    private final PlatformHttpTransport.Transport transport;

    NeteaseLyricSource(PlatformHttpTransport.Transport transport) {
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
        String url = SEARCH_ENDPOINT + "?s=" + query + "&type=1&offset=0&limit=" + Math.max(1, limit);
        PlatformHttpTransport.Response response = transport.get(url, HEADERS);
        if (!response.ok()) {
            throw new IOException(LABEL + " search failed (" + response.status + ")");
        }
        JSONObject root = new JSONObject(response.body);
        Object result = root.opt("result");
        if (!(result instanceof JSONObject)) {
            // The /web variant returns {"result":"<hex>"} once the request is rate limited or signed out.
            throw new IOException(LABEL + " returned an unreadable search envelope");
        }
        JSONArray songs = ((JSONObject) result).optJSONArray("songs");
        List<PlatformRecordingMatcher.Candidate> candidates = new ArrayList<>();
        if (songs == null) {
            return candidates;
        }
        for (int index = 0; index < songs.length(); index++) {
            JSONObject song = songs.optJSONObject(index);
            if (song == null) {
                continue;
            }
            String id = song.optString("id", "");
            if (id.isEmpty()) {
                continue;
            }
            candidates.add(new PlatformRecordingMatcher.Candidate(
                    id,
                    song.optString("name", ""),
                    artists(song.optJSONArray("artists")),
                    albumName(song.optJSONObject("album")),
                    song.optLong("duration", 0L)
            ));
        }
        return candidates;
    }

    @Override
    public PlatformLyricDocument fetch(String candidateId) throws Exception {
        String url = LYRIC_ENDPOINT + "?id=" + encode(candidateId) + "&lv=-1&kv=-1&tv=-1";
        PlatformHttpTransport.Response response = transport.get(url, HEADERS);
        if (!response.ok()) {
            throw new IOException(LABEL + " lyric failed (" + response.status + ")");
        }
        JSONObject root = new JSONObject(response.body);
        String original = lrc(root.optJSONObject("lrc"));
        String translated = lrc(root.optJSONObject("tlyric"));
        String romanised = lrc(root.optJSONObject("romalrc"));
        return new PlatformLyricDocument(
                ID,
                LABEL,
                candidateId,
                "",
                "",
                "",
                0L,
                PlatformLyricRow.parseLrc(original),
                PlatformLyricRow.parseLrc(translated),
                !romanised.trim().isEmpty()
        );
    }

    private static String lrc(JSONObject block) {
        return block == null ? "" : block.optString("lyric", "");
    }

    private static String artists(JSONArray array) {
        if (array == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject artist = array.optJSONObject(index);
            if (artist != null) {
                String name = artist.optString("name", "").trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return String.join(" / ", names);
    }

    private static String albumName(JSONObject album) {
        return album == null ? "" : album.optString("name", "");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
