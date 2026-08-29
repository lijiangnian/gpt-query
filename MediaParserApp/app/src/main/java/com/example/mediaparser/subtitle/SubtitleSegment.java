package com.example.mediaparser.subtitle;

public final class SubtitleSegment {
    public final long startCs;
    public final long endCs;
    public final String text;

    public SubtitleSegment(long startCs, long endCs, String text) {
        this.startCs = Math.max(0L, startCs);
        this.endCs = Math.max(this.startCs, endCs);
        this.text = text == null ? "" : text.trim();
    }
}
