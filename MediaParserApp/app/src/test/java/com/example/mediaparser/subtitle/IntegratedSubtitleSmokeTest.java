package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.List;

public final class IntegratedSubtitleSmokeTest {
    public static void main(String[] args)throws Exception{
        JSONObject result=new JSONObject().put("properties",new JSONObject().put("original_duration_in_milliseconds",4200)).put("transcripts",new JSONArray().put(new JSONObject().put("text","你好，AE86。").put("sentences",new JSONArray()
                .put(new JSONObject().put("begin_time",100).put("end_time",1600).put("text","你好，"))
                .put(new JSONObject().put("begin_time",1800).put("end_time",4000).put("text","AE86。")))));
        AliyunTranscriber.Result parsed=AliyunTranscriber.parse(result);
        check(parsed.segments.size()==2,"Ali sentence count");check(parsed.durationMs==4200,"Ali duration");check(parsed.segments.get(1).startCs==180,"Ali ms to cs");
        check(LocalModelManager.CATALOG.size()==3,"model count");check(LocalModelManager.FUNASR.files.get(0).sha256.length()==64,"model hash");check(LocalModelManager.PARAFORMER.files.get(0).bytes>1_000_000_000L,"Paraformer warning size");

        File pcm=File.createTempFile("vad-test",".s16le");try(FileOutputStream out=new FileOutputStream(pcm)){for(int sec=0;sec<5;sec++)for(int i=0;i<16000;i++){double amp=(sec==1||sec==3)?0.2:0;short v=(short)(Math.sin(2*Math.PI*440*i/16000.0)*amp*32767);out.write(v&255);out.write((v>>>8)&255);}}
        Method vad=LocalTranscriber.class.getDeclaredMethod("vad",File.class,long.class);vad.setAccessible(true);List<?> ranges=(List<?>)vad.invoke(null,pcm,5L*16000);pcm.delete();check(ranges.size()==2,"VAD separates one-second gaps");
        System.out.println("Integrated subtitle smoke tests PASS: Ali timeline, verified model catalog, synthetic PCM VAD (2 groups)");
    }
    private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
}

