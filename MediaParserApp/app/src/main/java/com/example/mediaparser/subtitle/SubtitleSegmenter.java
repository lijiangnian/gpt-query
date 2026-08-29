package com.example.mediaparser.subtitle;

import java.util.ArrayList;
import java.util.List;

/** Local fallback: prefer punctuation/pauses near the size limit; AI review can add semantic breaks. */
final class SubtitleSegmenter {
    static List<SubtitleSegment> group(List<SubtitleExtractor.Word> words) {
        List<SubtitleSegment> segments = new ArrayList<>();
        for (int start = 0; start < words.size();) {
            StringBuilder text = new StringBuilder();
            int stop = words.size();
            long maxEnd = words.get(start).endMs;
            for (int i = start; i < words.size(); i++) {
                SubtitleExtractor.Word word = words.get(i);
                if (i > start) {
                    SubtitleExtractor.Word previous = words.get(i - 1);
                    boolean speaker = !previous.speaker.isBlank() && !word.speaker.isBlank() && !previous.speaker.equals(word.speaker);
                    if (speaker || word.startMs - maxEnd >= 700) { stop = i; break; }
                }
                SubtitleExtractor.appendWord(text, word.text);
                maxEnd = Math.max(maxEnd, word.endMs);
                long duration = maxEnd - words.get(start).startMs;
                if (ends(word.text, ".!?。！？") && duration >= 900) { stop = i + 1; break; }
                if (duration >= 4500 || text.length() >= 34) {
                    stop = i + 1;
                    // Look back instead of cutting immediately in the middle of a short clause.
                    for (int k = i - 1; k >= start; k--) {
                        if (words.get(k).endMs - words.get(start).startMs < 1500) break;
                        if (ends(words.get(k).text, ",;:，；：。！？.!?") || words.get(k + 1).startMs - words.get(k).endMs >= 180) {
                            stop = k + 1; break;
                        }
                    }
                    break;
                }
            }
            text.setLength(0);
            maxEnd = words.get(start).endMs;
            for (int i = start; i < stop; i++) {
                SubtitleExtractor.appendWord(text, words.get(i).text);
                maxEnd = Math.max(maxEnd, words.get(i).endMs);
            }
            segments.add(new SubtitleSegment(words.get(start).startMs / 10, maxEnd / 10, text.toString()));
            start = stop;
        }
        return segments;
    }
    private static boolean ends(String text, String punctuation) {
        return !text.isEmpty() && punctuation.indexOf(text.charAt(text.length() - 1)) >= 0;
    }
}
