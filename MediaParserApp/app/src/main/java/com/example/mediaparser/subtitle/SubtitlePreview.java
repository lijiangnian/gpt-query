package com.example.mediaparser.subtitle;

import java.util.List;

/** The preview is rendered from the same segments and timestamps as the saved SRT. */
public final class SubtitlePreview {
    public static final int PAGE_SIZE = 60;

    private SubtitlePreview() {}

    public static Page first(List<SubtitleSegment> segments, int limit) {
        if (limit < 1) throw new IllegalArgumentException("字幕预览条数必须大于零");
        int total = segments.size();
        int shown = Math.min(total, limit);
        return new Page(SubtitleExtractor.buildSrt(segments.subList(0, shown)), shown, total);
    }

    public static final class Page {
        public final String text;
        public final int shown;
        public final int total;
        public final boolean hasMore;

        private Page(String text, int shown, int total) {
            this.text = text;
            this.shown = shown;
            this.total = total;
            this.hasMore = shown < total;
        }
    }
}
