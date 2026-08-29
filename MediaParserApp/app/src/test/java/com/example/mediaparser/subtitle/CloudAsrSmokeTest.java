package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;

/** Provider contract fixtures: timestamps, words, engine migration and secret-free alignment export. */
public final class CloudAsrSmokeTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        JSONObject qwen=new JSONObject().put("properties",new JSONObject().put("original_duration_in_milliseconds",22000)).put("transcripts",new JSONArray().put(new JSONObject().put("language","zh")
                .put("sentences",new JSONArray().put(new JSONObject().put("begin_time",120).put("end_time",1880).put("text","AE86，怎样？")
                        .put("words",new JSONArray().put(new JSONObject().put("begin_time",120).put("end_time",600).put("text","AE86"))
                                .put(new JSONObject().put("begin_time",610).put("end_time",1880).put("text","怎样")))))));
        Qwen3AsrTranscriber.Result qr=Qwen3AsrTranscriber.parse(qwen);check(qr.segments.size()==1,"qwen sentence");check(qr.words.size()==2,"qwen words");check(qr.segments.get(0).startCs==12&&qr.segments.get(0).endCs==188,"qwen ms conversion");check(qr.durationMs==22000,"qwen original duration retained");check(qr.lowCoverage(),"qwen sparse result is flagged");
        JSONObject qwenFull=new JSONObject().put("properties",new JSONObject().put("original_duration_in_milliseconds",5000)).put("transcripts",new JSONArray().put(new JSONObject().put("language","zh").put("text","服务端完整全文").put("sentences",new JSONArray().put(new JSONObject().put("begin_time",0).put("end_time",4900).put("text","短句")))));
        Qwen3AsrTranscriber.Result qf=Qwen3AsrTranscriber.parse(qwenFull);check(qf.text.equals("服务端完整全文"),"qwen channel transcript fallback");check(!qf.lowCoverage(),"short result is not over-diagnosed");
        JSONObject qwenTask=new JSONObject().put("output",new JSONObject().put("task_status","SUCCEEDED").put("result",new JSONObject().put("transcription_url","https://result.oss-cn-beijing.aliyuncs.com/task.json")));
        check(Qwen3AsrTranscriber.resultUrl(qwenTask).contains("task.json"),"qwen current task result path");
        check(Qwen3AsrTranscriber.safeResultUri("http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/task.json?x=1").getScheme().equals("https"),"qwen signed HTTP result is upgraded");
        check(Qwen3AsrTranscriber.safeResultUri("https://result-cdn.example.com/task.json").getHost().equals("result-cdn.example.com"),"qwen public CDN result accepted");
        try{Qwen3AsrTranscriber.safeResultUri("http://127.0.0.1/result.json");throw new AssertionError("qwen local result accepted");}catch(java.io.IOException expected){}

        JSONObject doubao=new JSONObject().put("audio_info",new JSONObject().put("duration",2500)).put("result",new JSONObject().put("text","猎豹追羚羊")
                .put("utterances",new JSONArray().put(new JSONObject().put("start_time",300).put("end_time",2300).put("text","猎豹追羚羊")
                        .put("words",new JSONArray().put(new JSONObject().put("start_time",300).put("end_time",700).put("text","猎豹"))
                                .put(new JSONObject().put("start_time",800).put("end_time",2300).put("text","追羚羊"))))));
        DoubaoTranscriber.Result dr=DoubaoTranscriber.parse(doubao);check(dr.durationMs==2500,"doubao duration");check(dr.segments.size()==1&&dr.words.size()==2,"doubao alignments");
        check(!DoubaoTranscriber.isTurbo("volc.bigasr.auc"),"standard resource must use submit probe");
        check(DoubaoTranscriber.isTurbo("volc.bigasr.auc_turbo"),"turbo resource must use flash probe");
        check(SubtitleExtractor.isDoubaoAudioUrlFailure(new java.io.IOException("45000006 [Invalid audio URI] audio download failed")),"doubao anti-hotlink fallback");
        byte[] frame=DoubaoStreamingTranscriber.packet(0x20,0x00,new byte[]{1,2,3});check(frame.length==11&&frame[0]==0x11&&frame[1]==0x20,"doubao binary packet");
        byte[] serverJson="{\"result\":{\"text\":\"完成\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);java.nio.ByteBuffer server=java.nio.ByteBuffer.allocate(12+serverJson.length).order(java.nio.ByteOrder.BIG_ENDIAN);server.put((byte)0x11).put((byte)0x93).put((byte)0x10).put((byte)0).putInt(-1).putInt(serverJson.length).put(serverJson);
        DoubaoStreamingTranscriber.Packet decoded=DoubaoStreamingTranscriber.parsePacket(server.array());check(decoded.terminal&&decoded.json.getJSONObject("result").getString("text").equals("完成"),"doubao terminal response packet");

        SubtitleExtractor.Parsed parsed=new SubtitleExtractor.Parsed(dr.text,"zh",dr.segments,dr.words,dr.durationMs,"fixture");String alignment=SubtitleExtractor.alignmentJson(parsed,"豆包 ASR").toString();check(alignment.contains("segment_id")&&alignment.contains("actual_engine"),"alignment fields");check(!alignment.toLowerCase().contains("api_key")&&!alignment.contains("Authorization"),"alignment is secret-free");
        check(SubtitleProvider.fromSaved("DUAL")==SubtitleProvider.AUTO,"legacy dual preference migrates to auto");
        System.out.println("Cloud ASR fixture tests passed: Qwen3 sentences/words, Doubao sentences/words, alignment security, provider migration");
    }
}
