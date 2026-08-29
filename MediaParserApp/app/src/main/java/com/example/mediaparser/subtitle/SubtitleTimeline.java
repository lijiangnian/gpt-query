package com.example.mediaparser.subtitle;

import java.util.ArrayList;
import java.util.List;

/** Reject suspect anchors instead of inventing, scaling, sorting or clamping times. */
final class SubtitleTimeline {
    private static final long WORD_BACKTRACK_TOLERANCE_MS=300;
    private static final long TAIL_TOLERANCE_MS=2000;
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

    /** Repair only small provider rounding/ordering defects; reject large or unrelated timelines. */
    static List<SubtitleExtractor.Word> providerWords(List<SubtitleExtractor.Word> source,long durationMs){
        duration(durationMs);ArrayList<SubtitleExtractor.Word> out=new ArrayList<>();long previous=0;
        for(SubtitleExtractor.Word word:source){
            long start=word.startMs,end=word.endMs;
            if(start<0||end<0||start>durationMs+TAIL_TOLERANCE_MS||end>durationMs+TAIL_TOLERANCE_MS)
                throw new IllegalStateException("字级时间戳超出可修复范围");
            if(start<previous){if(previous-start>WORD_BACKTRACK_TOLERANCE_MS)throw new IllegalStateException("字级时间戳明显乱序");start=previous;}
            if(end<start){if(start-end>WORD_BACKTRACK_TOLERANCE_MS)throw new IllegalStateException("字级时间戳明显倒置");end=start;}
            start=Math.min(start,Math.max(0,durationMs-1));end=Math.min(end,durationMs);
            if(end<=start)end=Math.min(durationMs,start+10);
            if(end<=start)throw new IllegalStateException("字级时间戳位于音频结束之后");
            out.add(new SubtitleExtractor.Word(word.text,start,end,word.speaker,word.confidence));previous=start;
        }
        words(out,durationMs);return out;
    }

    static List<SubtitleSegment> providerSegments(List<SubtitleSegment> source,long durationMs){
        duration(durationMs);ArrayList<SubtitleSegment> out=new ArrayList<>();long previousEnd=0,maxCs=durationMs/10,toleranceCs=TAIL_TOLERANCE_MS/10;
        for(SubtitleSegment segment:source){
            long start=segment.startCs,end=segment.endCs;
            if(start<0||end<=0||start>maxCs+toleranceCs||end>maxCs+toleranceCs)
                throw new IllegalStateException("句级时间戳超出可修复范围");
            if(start<previousEnd){if(previousEnd-start>30)throw new IllegalStateException("句级时间戳明显重叠或乱序");start=previousEnd;}
            start=Math.min(start,Math.max(0,maxCs-1));end=Math.min(end,maxCs);
            if(end<=start)end=Math.min(maxCs,start+1);
            if(end<=start)throw new IllegalStateException("句级时间戳位于音频结束之后");
            SubtitleSegment fixed=new SubtitleSegment(start,end,segment.text);out.add(fixed);previousEnd=fixed.endCs;
        }
        segments(out,durationMs);return out;
    }
}
