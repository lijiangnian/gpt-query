package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class QwenCoverageTest {
    @Test public void sparseTwentyTwoSecondSongIsNotReportedAsComplete() throws Exception {
        JSONObject result=new JSONObject()
                .put("properties",new JSONObject().put("original_duration_in_milliseconds",22_000))
                .put("transcripts",new JSONArray().put(new JSONObject().put("language","zh")
                        .put("text","抛弃所有委屈，在悲伤中。之间的差距。")
                        .put("sentences",new JSONArray()
                                .put(new JSONObject().put("begin_time",8_360).put("end_time",14_960).put("text","抛弃所有委屈，在悲伤中。"))
                                .put(new JSONObject().put("begin_time",15_800).put("end_time",22_000).put("text","之间的差距。")))));

        Qwen3AsrTranscriber.Result parsed=Qwen3AsrTranscriber.parse(result);

        assertEquals(22_000,parsed.durationMs);
        assertEquals(2,parsed.segments.size());
        assertTrue(parsed.lowCoverage());
        assertTrue(parsed.coverageWarning.contains("只返回2段"));
    }

    @Test public void channelTranscriptIsPreservedWhenSentenceArrayIsShorter() throws Exception {
        JSONObject result=new JSONObject()
                .put("properties",new JSONObject().put("original_duration_in_milliseconds",5_000))
                .put("transcripts",new JSONArray().put(new JSONObject()
                        .put("text","服务端返回的完整全文")
                        .put("sentences",new JSONArray().put(new JSONObject()
                                .put("begin_time",0).put("end_time",4_900).put("text","短句")))));

        Qwen3AsrTranscriber.Result parsed=Qwen3AsrTranscriber.parse(result);

        assertEquals("服务端返回的完整全文",parsed.text);
        assertFalse(parsed.lowCoverage());
    }

    @Test public void outputCarriesLowCoverageSignalForAutomaticFallback() {
        SubtitleOutput output=SubtitleOutput.timed(new SubtitleOutput(
                java.util.Collections.singletonList(new SubtitleSegment(0,100,"测试")),
                "测试","1\n00:00:00,000 --> 00:00:01,000\n测试\n","zh","",""),
                22_000,"Qwen3-ASR 句级时间轴 · 识别覆盖率异常：22秒音频只返回1段");

        assertTrue(SubtitleExtractor.isLowCoverage(output));
    }
}
