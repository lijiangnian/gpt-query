package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider-neutral ASR result used by the editor, benchmark, screenshots and exports. */
public final class AsrDocument {
    public final String engine;
    public final String language;
    public final long durationMs;
    public final List<Segment> segments;
    public final List<Word> words;

    public AsrDocument(String engine,String language,long durationMs,List<Segment> segments,List<Word> words){
        this.engine=n(engine);this.language=n(language);this.durationMs=Math.max(0,durationMs);
        this.segments=Collections.unmodifiableList(new ArrayList<>(segments));
        this.words=Collections.unmodifiableList(new ArrayList<>(words));
    }

    public static AsrDocument from(SubtitleOutput output){
        ArrayList<Word> words=new ArrayList<>();
        for(SubtitleExtractor.Word w:output.timedWords)
            words.add(new Word(w.startMs,w.endMs,w.text,w.speaker,w.confidence));
        ArrayList<Segment> segments=new ArrayList<>();
        for(int i=0;i<output.segments.size();i++){
            SubtitleSegment s=output.segments.get(i);long start=s.startCs*10,end=s.endCs*10;
            ArrayList<Word> inside=new ArrayList<>();
            for(Word w:words)if(w.endMs>=start&&w.startMs<=end)inside.add(w);
            segments.add(new Segment(i+1,start,end,s.text,speaker(inside),confidence(inside),inside));
        }
        return new AsrDocument(output.actualEngine,output.detectedLanguage,output.durationMs,segments,words);
    }

    public String plainText(){StringBuilder b=new StringBuilder();for(Segment s:segments){if(b.length()>0)b.append('\n');b.append(s.text);}return b.toString();}
    public String timestampText(){StringBuilder b=new StringBuilder();for(Segment s:segments){if(b.length()>0)b.append('\n');b.append(clock(s.startMs)).append("  ");if(!s.speaker.isBlank())b.append(s.speaker).append("：");b.append(s.text);}return b.toString();}
    public String srt(){StringBuilder b=new StringBuilder();for(int i=0;i<segments.size();i++){Segment s=segments.get(i);b.append(i+1).append('\n').append(srtTime(s.startMs)).append(" --> ").append(srtTime(s.endMs)).append('\n').append(s.text).append("\n\n");}return b.toString();}

    public JSONObject toJson()throws Exception{
        JSONObject root=new JSONObject().put("schema","mediaparser-asr-v2").put("engine",engine).put("language",language).put("duration_ms",durationMs);
        JSONArray ss=new JSONArray();for(Segment s:segments)ss.put(s.toJson().put("engine",engine).put("language",language));JSONArray ww=new JSONArray();for(Word w:words)ww.put(w.toJson());
        return root.put("segments",ss).put("words",ww);
    }

    public static AsrDocument fromJson(JSONObject root)throws Exception{
        ArrayList<Word> words=new ArrayList<>();JSONArray ww=root.optJSONArray("words");if(ww!=null)for(int i=0;i<ww.length();i++)words.add(Word.fromJson(ww.getJSONObject(i)));
        ArrayList<Segment> segments=new ArrayList<>();JSONArray ss=root.optJSONArray("segments");if(ss!=null)for(int i=0;i<ss.length();i++)segments.add(Segment.fromJson(ss.getJSONObject(i)));
        return new AsrDocument(root.optString("engine"),root.optString("language"),root.optLong("duration_ms"),segments,words);
    }

    public static final class Segment{
        public final int id;public final long startMs,endMs;public final String text,speaker;public final double confidence;public final List<Word> words;
        public Segment(int id,long startMs,long endMs,String text,String speaker,double confidence,List<Word> words){this.id=id;this.startMs=Math.max(0,startMs);this.endMs=Math.max(this.startMs+1,endMs);this.text=n(text);this.speaker=n(speaker);this.confidence=confidence;this.words=Collections.unmodifiableList(new ArrayList<>(words));}
        JSONObject toJson()throws Exception{JSONObject x=new JSONObject().put("segment_id",id).put("start_ms",startMs).put("end_ms",endMs).put("text",text).put("speaker",speaker).put("engine","");if(confidence>=0)x.put("confidence",confidence);JSONArray a=new JSONArray();for(Word w:words)a.put(w.toJson());return x.put("words",a);}
        static Segment fromJson(JSONObject x)throws Exception{ArrayList<Word>w=new ArrayList<>();JSONArray a=x.optJSONArray("words");if(a!=null)for(int i=0;i<a.length();i++)w.add(Word.fromJson(a.getJSONObject(i)));return new Segment(x.optInt("segment_id"),x.optLong("start_ms"),x.optLong("end_ms"),x.optString("text"),x.optString("speaker"),x.has("confidence")?x.optDouble("confidence"):-1,w);}
    }

    public static final class Word{
        public final long startMs,endMs;public final String text,speaker;public final double confidence;
        public Word(long startMs,long endMs,String text,String speaker,double confidence){this.startMs=Math.max(0,startMs);this.endMs=Math.max(this.startMs+1,endMs);this.text=n(text);this.speaker=n(speaker);this.confidence=confidence;}
        JSONObject toJson()throws Exception{JSONObject x=new JSONObject().put("start_ms",startMs).put("end_ms",endMs).put("text",text).put("speaker",speaker);if(confidence>=0)x.put("confidence",confidence);return x;}
        static Word fromJson(JSONObject x){return new Word(x.optLong("start_ms"),x.optLong("end_ms"),x.optString("text"),x.optString("speaker"),x.has("confidence")?x.optDouble("confidence"):-1);}
    }

    private static String speaker(List<Word> words){Map<String,Integer> c=new LinkedHashMap<>();for(Word w:words)if(!w.speaker.isBlank())c.put(w.speaker,c.getOrDefault(w.speaker,0)+1);String best="";int n=0;for(Map.Entry<String,Integer>e:c.entrySet())if(e.getValue()>n){best=e.getKey();n=e.getValue();}return best;}
    private static double confidence(List<Word> words){double sum=0;int n=0;for(Word w:words)if(w.confidence>=0){sum+=w.confidence;n++;}return n==0?-1:sum/n;}
    private static String n(String s){return s==null?"":s.trim();}
    private static String clock(long ms){long h=ms/3600000;ms%=3600000;long m=ms/60000;ms%=60000;return String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",h,m,ms/1000);}
    private static String srtTime(long ms){long h=ms/3600000;ms%=3600000;long m=ms/60000;ms%=60000;return String.format(java.util.Locale.ROOT,"%02d:%02d:%02d,%03d",h,m,ms/1000,ms%1000);}
}
