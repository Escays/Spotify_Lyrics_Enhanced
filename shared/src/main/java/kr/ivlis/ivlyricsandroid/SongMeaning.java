package kr.ivlis.ivlyricsandroid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything Genius has for one recording: a description, the annotations that could be placed on the
 * displayed lyrics, and the annotations that could not. Unplaced annotations are still shown in the
 * panel with their quoted fragment, because dropping them would hide real explanations and hanging
 * them on the wrong line would be worse.
 */
final class SongMeaning {
    static final SongMeaning EMPTY = new SongMeaning("", "", "", "", "", Collections.emptyList(),
            Collections.emptyList(), GeniusClient.LABEL);

    final String songId;
    final String title;
    final String artist;
    final String url;
    final String description;
    final List<SongAnnotation> placedAnnotations;
    final List<SongAnnotation> unplacedAnnotations;
    final String sourceLabel;

    SongMeaning(String songId, String title, String artist, String url, String description,
            List<SongAnnotation> placed, List<SongAnnotation> unplaced, String sourceLabel) {
        this.songId = songId == null ? "" : songId;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.url = url == null ? "" : url;
        this.description = description == null ? "" : description;
        this.placedAnnotations = Collections.unmodifiableList(
                placed == null ? new ArrayList<>() : new ArrayList<>(placed));
        this.unplacedAnnotations = Collections.unmodifiableList(
                unplaced == null ? new ArrayList<>() : new ArrayList<>(unplaced));
        this.sourceLabel = sourceLabel == null || sourceLabel.isEmpty() ? GeniusClient.LABEL : sourceLabel;
    }

    boolean hasDescription() {
        return !description.trim().isEmpty();
    }

    boolean hasAnnotations() {
        return !placedAnnotations.isEmpty() || !unplacedAnnotations.isEmpty();
    }

    boolean isEmpty() {
        return !hasDescription() && !hasAnnotations();
    }

    List<SongAnnotation> allAnnotations() {
        List<SongAnnotation> all = new ArrayList<>(placedAnnotations);
        all.addAll(unplacedAnnotations);
        return all;
    }

    /** The annotations attached to one lyric line, in display order. */
    List<SongAnnotation> annotationsForLine(int lineIndex) {
        List<SongAnnotation> matches = new ArrayList<>();
        for (SongAnnotation annotation : placedAnnotations) {
            if (annotation.lineIndexes.contains(lineIndex)) {
                matches.add(annotation);
            }
        }
        return matches;
    }

    /** True when any annotation sits on the given line, which is what the dashed underline marks. */
    boolean hasAnnotationOnLine(int lineIndex) {
        for (SongAnnotation annotation : placedAnnotations) {
            if (annotation.lineIndexes.contains(lineIndex)) {
                return true;
            }
        }
        return false;
    }

    SongMeaning withTranslations(SongMeaning translated) {
        return translated == null ? this : translated;
    }
}
