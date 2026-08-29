package com.example.mediaparser.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ParseResult {
    public final String platform;
    public final String title;
    public final String author;
    public final String description;
    public final String coverUrl;
    public final String sourceUrl;
    public final List<MediaItem> media;

    private ParseResult(Builder b) {
        this.platform = b.platform;
        this.title = b.title;
        this.author = b.author;
        this.description = b.description;
        this.coverUrl = b.coverUrl;
        this.sourceUrl = b.sourceUrl;
        this.media = Collections.unmodifiableList(new ArrayList<>(b.media));
    }

    public boolean hasMedia() {
        return !media.isEmpty();
    }

    public static Builder builder(String platform, String sourceUrl) {
        return new Builder(platform, sourceUrl);
    }

    public static final class Builder {
        private final String platform;
        private final String sourceUrl;
        private String title = "";
        private String author = "";
        private String description = "";
        private String coverUrl = "";
        private final List<MediaItem> media = new ArrayList<>();

        private Builder(String platform, String sourceUrl) {
            this.platform = platform;
            this.sourceUrl = sourceUrl;
        }

        public Builder title(String v) { title = n(v); return this; }
        public Builder author(String v) { author = n(v); return this; }
        public Builder description(String v) { description = n(v); return this; }
        public Builder coverUrl(String v) { coverUrl = n(v); return this; }
        public Builder add(MediaItem item) { if (item != null && !item.url.isBlank()) media.add(item); return this; }
        public ParseResult build() { return new ParseResult(this); }
        private static String n(String s) { return s == null ? "" : s; }
    }
}
