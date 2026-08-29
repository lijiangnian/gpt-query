package com.example.mediaparser.subtitle;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class AsrDocumentTest {
    @Test public void roundTripKeepsUnifiedFields()throws Exception{
        AsrDocument.Word word=new AsrDocument.Word(100,450,"测试","发言人1",.91);
        AsrDocument.Segment segment=new AsrDocument.Segment(1,100,900,"测试内容","发言人1",.91,Collections.singletonList(word));
        AsrDocument source=new AsrDocument("豆包 ASR","zh",1000,Collections.singletonList(segment),Collections.singletonList(word));
        JSONObject json=source.toJson();JSONObject raw=json.getJSONArray("segments").getJSONObject(0);
        assertEquals("豆包 ASR",raw.getString("engine"));assertEquals("zh",raw.getString("language"));assertEquals("发言人1",raw.getString("speaker"));assertTrue(raw.has("confidence"));
        AsrDocument copy=AsrDocument.fromJson(json);assertEquals("测试内容",copy.segments.get(0).text);assertEquals(100,copy.segments.get(0).startMs);assertEquals(1,copy.words.size());
    }

    @Test public void producesSrtAndTimestampTranscript(){
        AsrDocument.Segment segment=new AsrDocument.Segment(1,3210,5120,"会议开始","张三",-1,Collections.emptyList());
        AsrDocument doc=new AsrDocument("local","zh",6000,Collections.singletonList(segment),Collections.emptyList());
        assertTrue(doc.srt().contains("00:00:03,210 --> 00:00:05,120"));assertTrue(doc.timestampText().contains("张三：会议开始"));
    }
}
