package kr.ivlis.ivlyricsandroid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Genius: the public search, song and referent endpoints, with no account and no client token.
 *
 * <p>The description lives on the song object; per-line explanations arrive as referents, each one
 * quoting the lyric fragment it explains. Both are plain text when {@code text_format=plain} is asked
 * for, so no HTML parsing happens on the device.
 */
final class GeniusClient {
    static final String LABEL = "Genius";
    private static final String SEARCH_ENDPOINT = "https://genius.com/api/search/multi";
    private static final String SONG_ENDPOINT = "https://genius.com/api/songs/";
    private static final String REFERENT_ENDPOINT = "https://genius.com/api/referents";
    private static final Map<String, String> HEADERS = PlatformHttpTransport.headers(
            "Accept", "application/json"
    );
    private static final int REFERENT_PAGE_SIZE = 50;
    private static final int MAX_REFERENTS = 150;

    private final PlatformHttpTransport.Transport transport;

    GeniusClient(PlatformHttpTransport.Transport transport) {
        this.transport = transport;
    }

    static final class Hit {
        final String id;
        final String title;
        final String artist;
        final String url;

        Hit(String id, String title, String artist, String url) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.url = url == null ? "" : url;
        }
    }

    List<Hit> search(String title, String artist) throws Exception {
        String query = encode((title + " " + artist).trim());
        PlatformHttpTransport.Response response =
                transport.get(SEARCH_ENDPOINT + "?q=" + query, HEADERS);
        if (!response.ok()) {
            throw new IOException(LABEL + " search failed (" + response.status + ")");
        }
        JSONObject root = new JSONObject(response.body);
        JSONObject payload = root.optJSONObject("response");
        JSONArray sections = payload == null ? null : payload.optJSONArray("sections");
        List<Hit> hits = new ArrayList<>();
        if (sections == null) {
            return hits;
        }
        for (int sectionIndex = 0; sectionIndex < sections.length(); sectionIndex++) {
            JSONObject section = sections.optJSONObject(sectionIndex);
            JSONArray sectionHits = section == null ? null : section.optJSONArray("hits");
            if (sectionHits == null) {
                continue;
            }
            for (int index = 0; index < sectionHits.length(); index++) {
                JSONObject hit = sectionHits.optJSONObject(index);
                JSONObject result = hit == null ? null : hit.optJSONObject("result");
                if (result == null || !"song".equals(result.optString("_type", ""))) {
                    continue;
                }
                String id = result.optString("id", "");
                if (id.isEmpty()) {
                    continue;
                }
                String url = result.optString("url", "");
                if (url.isEmpty()) {
                    String path = result.optString("path", "");
                    url = path.isEmpty() ? "" : "https://genius.com" + path;
                }
                hits.add(new Hit(id, result.optString("title", ""),
                        result.optString("artist_names", ""), url));
            }
        }
        return hits;
    }

    /**
     * The description and the referents of one song. A description of "?" is Genius' own placeholder
     * for "no description", not text to show.
     */
    SongMeaning fetch(String songId, String sourceLabel) throws Exception {
        PlatformHttpTransport.Response response =
                transport.get(SONG_ENDPOINT + encode(songId) + "?text_format=plain", HEADERS);
        if (!response.ok()) {
            throw new IOException(LABEL + " song failed (" + response.status + ")");
        }
        JSONObject root = new JSONObject(response.body);
        JSONObject payload = root.optJSONObject("response");
        JSONObject song = payload == null ? null : payload.optJSONObject("song");
        if (song == null) {
            throw new IOException(LABEL + " song response has no song object");
        }
        String description = "";
        JSONObject descriptionObject = song.optJSONObject("description");
        if (descriptionObject != null) {
            String plain = descriptionObject.optString("plain", "");
            if (!plain.trim().isEmpty() && !"?".equals(plain.trim())) {
                description = plain.trim();
            }
        }
        String songUrl = song.optString("url", "");
        if (songUrl.isEmpty()) {
            String path = song.optString("path", "");
            songUrl = path.isEmpty() ? "" : "https://genius.com" + path;
        }
        List<SongAnnotation> annotations = fetchReferents(songId);
        return new SongMeaning(
                songId,
                song.optString("title", ""),
                song.optString("artist_names", ""),
                songUrl,
                description,
                // Placement happens only against the lyrics that are actually displayed.
                Collections.emptyList(),
                annotations,
                sourceLabel == null || sourceLabel.isEmpty() ? LABEL : sourceLabel
        );
    }

    private List<SongAnnotation> fetchReferents(String songId) throws Exception {
        List<SongAnnotation> annotations = new ArrayList<>();
        int page = 1;
        while (annotations.size() < MAX_REFERENTS) {
            String url = REFERENT_ENDPOINT + "?song_id=" + encode(songId)
                    + "&text_format=plain&per_page=" + REFERENT_PAGE_SIZE + "&page=" + page;
            PlatformHttpTransport.Response response = transport.get(url, HEADERS);
            if (!response.ok()) {
                throw new IOException(LABEL + " referents failed (" + response.status + ")");
            }
            JSONObject root = new JSONObject(response.body);
            JSONObject payload = root.optJSONObject("response");
            JSONArray referents = payload == null ? null : payload.optJSONArray("referents");
            if (referents == null || referents.length() == 0) {
                break;
            }
            for (int index = 0; index < referents.length(); index++) {
                SongAnnotation annotation = toAnnotation(referents.optJSONObject(index));
                if (annotation != null) {
                    annotations.add(annotation);
                }
            }
            if (referents.length() < REFERENT_PAGE_SIZE) {
                break;
            }
            page++;
            if (page > 4) {
                break;
            }
        }
        return annotations;
    }

    private static SongAnnotation toAnnotation(JSONObject referent) {
        if (referent == null) {
            return null;
        }
        String fragment = referent.optString("fragment", "");
        JSONArray annotations = referent.optJSONArray("annotations");
        if (fragment.trim().isEmpty() || annotations == null || annotations.length() == 0) {
            return null;
        }
        JSONObject first = annotations.optJSONObject(0);
        if (first == null) {
            return null;
        }
        JSONObject body = first.optJSONObject("body");
        String plain = body == null ? "" : body.optString("plain", "");
        if (plain.trim().isEmpty()) {
            return null;
        }
        String id = referent.optString("id", "");
        String url = referent.optString("url", "");
        return new SongAnnotation(
                id,
                fragment.trim(),
                plain.trim(),
                url,
                referent.optBoolean("is_description", false),
                first.optInt("votes_total", 0),
                referent.optString("classification", "")
        );
    }

    static List<SongAnnotation> none() {
        return Collections.emptyList();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
