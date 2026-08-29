package com.example.mediaparser.subtitle;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ProviderTimelineTest {
    @Test public void groqClampsSmallTailRoundingToRealDuration() throws Exception {
        JSONObject response=new JSONObject("{\"text\":\"Hello world.\",\"words\":[{\"word\":\"Hello\",\"start\":0.1,\"end\":0.5},{\"word\":\"world.\",\"start\":27.4,\"end\":28.1}]}" );
        SubtitleExtractor.Parsed parsed=GroqTranscriber.parse(response,27771);
        assertFalse(parsed.segments.isEmpty());
        assertEquals(2777,parsed.segments.get(parsed.segments.size()-1).endCs);
    }

    @Test public void smallProviderWordBacktrackIsRepaired() {
        java.util.List<SubtitleExtractor.Word> source=java.util.Arrays.asList(
                new SubtitleExtractor.Word("A",1000,1200,""),
                new SubtitleExtractor.Word("B",900,1400,""));
        java.util.List<SubtitleExtractor.Word> fixed=SubtitleTimeline.providerWords(source,2000);
        assertEquals(1000,fixed.get(1).startMs);
    }

    @Test(expected=IllegalStateException.class)
    public void largeProviderBacktrackStillFails() {
        SubtitleTimeline.providerWords(java.util.Arrays.asList(
                new SubtitleExtractor.Word("A",1500,1600,""),
                new SubtitleExtractor.Word("B",500,700,"")),2000);
    }
}
