package com.example.mediaparser.subtitle;

import java.util.List;

/** Reject suspect anchors instead of inventing, scaling, sorting or clamping times. */
final class SubtitleTimeline {
    static void duration(long durationMs) {
        if (durationMs <= 0) throw new IllegalStateException("无法确认音频实际时长，不能验证时间轴");
    }
    static void words(List<SubtitleExtractor.Word> words, long durationMs) {
        duration(durationMs);
        long previous = -1;
        for (SubtitleExtractor.Word word : words) {
            if (word.startMs < 0 || word.endMs < word.startMs || word.endMs > durationMs)
                throw new IllegalStateException("字级时间戳倒置或超出音频实际时长（" + durationMs + "毫秒）");
            if (word.startMs < previous) throw new IllegalStateException("字级时间戳乱序");
            if (word.endMs - word.startMs > 12000) throw new IllegalStateException("单个字级时间锚点超过12秒，需复听核对");
            previous = word.startMs;
        }
    }
    static void segments(List<SubtitleSegment> segments, long durationMs) {
        if (durationMs != Long.MAX_VALUE) duration(durationMs);
        long previousEnd = 0;
        for (SubtitleSegment segment : segments) {
            if (segment.startCs < 0 || segment.endCs <= segment.startCs)
                throw new IllegalStateException("字幕包含零时长或倒置时间轴");
            if (segment.endCs > durationMs / 10) throw new IllegalStateException("字幕超出音频实际时长");
            if (segment.startCs < previousEnd) throw new IllegalStateException("字幕时间轴重叠或乱序");
            previousEnd = segment.endCs;
        }
    }
}
