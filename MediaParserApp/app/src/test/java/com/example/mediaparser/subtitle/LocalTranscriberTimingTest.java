package com.example.mediaparser.subtitle;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class LocalTranscriberTimingTest {
    @Test public void longTranscriptIsSplitIntoReadableSubtitleLines(){
        List<String> parts=LocalTranscriber.splitSubtitleText("第一句话很短。第二句话没有句号但是非常非常长需要按照逗号，切成可以阅读的字幕文本");
        assertTrue(parts.size()>=3);for(String part:parts)assertTrue(part.length()<=22);
    }

    @Test public void modelTokenTimestampsDrivePhraseBoundaries(){
        List<LocalTranscriber.Segment> out=new ArrayList<>();List<LocalTranscriber.Word> words=new ArrayList<>();String[] tokens={"第","一","句","。","第","二","句","。"};
        LocalTranscriber.Timing timing=LocalTranscriber.appendTimedSegments(out,words,10_000,20_000,"第一句。第二句。",tokens,new float[]{0f,.2f,.4f,.6f,6f,6.2f,6.4f,6.6f},new float[0]);
        assertTrue(timing.token);assertEquals(2,out.size());assertEquals(10_000,out.get(0).startMs);assertTrue(out.get(0).endMs<16_000);assertEquals(16_000,out.get(1).startMs);assertEquals(8,words.size());
    }

    @Test public void missingTokenTimingUsesMonotonicSpeechRangeSubdivision(){
        List<LocalTranscriber.Segment> out=new ArrayList<>();
        LocalTranscriber.Timing timing=LocalTranscriber.appendTimedSegments(out,1_000,7_000,"甲乙丙。甲乙丙丁戊。",new float[0],new float[0]);
        assertFalse(timing.token);assertEquals(2,out.size());assertEquals(1_000,out.get(0).startMs);assertEquals(7_000,out.get(1).endMs);assertEquals(out.get(0).endMs,out.get(1).startMs);
    }

    @Test public void realTokenSilenceCreatesSeparateSubtitleSegmentsAndWords(){
        String[] tokens={"来","家","对","大","我","呀","我","操","是","随","你","这","辆"," g","d","阿","的","马","亚"};
        float[] ts={1.56f,1.80f,2.28f,2.46f,2.64f,2.82f,3.90f,4.08f,11.34f,11.58f,11.82f,11.94f,12.06f,12.24f,12.36f,12.54f,12.66f,12.78f,12.90f};
        List<LocalTranscriber.Segment> out=new ArrayList<>();List<LocalTranscriber.Word> words=new ArrayList<>();
        LocalTranscriber.Timing timing=LocalTranscriber.appendTimedSegments(out,words,10_000,23_000,"来家对大我呀我操是随你这辆 gd阿的马亚",tokens,ts,new float[0]);
        assertTrue(timing.token);assertEquals(3,out.size());assertEquals(19,words.size());assertEquals(11_560,out.get(0).startMs);assertTrue(out.get(1).endMs<20_000);assertEquals(21_340,out.get(2).startMs);
    }
}
