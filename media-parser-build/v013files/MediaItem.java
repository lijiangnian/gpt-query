package com.example.mediaparser.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MediaItem {
    public enum Type { VIDEO, IMAGE, AUDIO }
    public enum SaveMode { DIRECT, EXTRACT_AUDIO_TRACK }

    public final Type type;
    public final String label;
    public final String url;
    public final Map<String, String> headers;
    public final SaveMode saveMode;
    public final String preferredExtension;
    public final String mimeType;

    public MediaItem(Type type, String label, String url) {
        this(type, label, url, Collections.emptyMap());
    }

    public MediaItem(Type type, String label, String url, Map<String, String> headers) {
        this(type, label, url, headers, SaveMode.DIRECT, "", "");
    }

    public MediaItem(Type type, String label, String url, Map<String, String> headers,
                     SaveMode saveMode, String preferredExtension, String mimeType) {
        this.type = type;
        this.label = label == null ? "媒体" : label;
        this.url = url == null ? "" : url;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers == null ? Collections.emptyMap() : headers));
        this.saveMode = saveMode == null ? SaveMode.DIRECT : saveMode;
        this.preferredExtension = normalizeExtension(preferredExtension);
        this.mimeType = mimeType == null ? "" : mimeType;
    }

    public static MediaItem directAudio(String label, String url, Map<String, String> headers,
                                        String preferredExtension, String mimeType) {
        return new MediaItem(Type.AUDIO, label, url, headers, SaveMode.DIRECT, preferredExtension, mimeType);
    }

    public static MediaItem audioTrack(String label, String videoUrl, Map<String, String> headers) {
        return new MediaItem(Type.AUDIO, label, videoUrl, headers,
                SaveMode.EXTRACT_AUDIO_TRACK, ".m4a", "audio/mp4");
    }

    public boolean extractsAudioTrack() {
        return saveMode == SaveMode.EXTRACT_AUDIO_TRACK;
    }

    private static String normalizeExtension(String ext) {
        if (ext == null || ext.isBlank()) return "";
        return ext.startsWith(".") ? ext : "." + ext;
    }
}
