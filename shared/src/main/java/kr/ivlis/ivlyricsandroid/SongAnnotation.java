package kr.ivlis.ivlyricsandroid;

import java.util.Collections;
import java.util.List;

/** One Genius annotation: the quoted lyric fragment and the explanation written for it. */
final class SongAnnotation {
    final String id;
    final String fragment;
    final String body;
    final String url;
    final boolean description;
    final int votes;
    final String classification;

    /** Line indexes this fragment was located on; empty when it could not be placed reliably. */
    final List<Integer> lineIndexes;

    SongAnnotation(String id, String fragment, String body, String url, boolean description, int votes,
            String classification) {
        this(id, fragment, body, url, description, votes, classification, Collections.emptyList());
    }

    SongAnnotation(String id, String fragment, String body, String url, boolean description, int votes,
            String classification, List<Integer> lineIndexes) {
        this.id = id == null ? "" : id;
        this.fragment = fragment == null ? "" : fragment.trim();
        this.body = body == null ? "" : body.trim();
        this.url = url == null ? "" : url;
        this.description = description;
        this.votes = votes;
        this.classification = classification == null ? "" : classification;
        this.lineIndexes = lineIndexes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(lineIndexes));
    }

    SongAnnotation withLines(List<Integer> indexes) {
        return new SongAnnotation(id, fragment, body, url, description, votes, classification, indexes);
    }

    boolean isPlaced() {
        return !lineIndexes.isEmpty();
    }

    /** A stable key for caching a translated body. */
    String cacheKey() {
        return id + "|" + (body.length() > 64 ? body.substring(0, 64) : body);
    }
}
