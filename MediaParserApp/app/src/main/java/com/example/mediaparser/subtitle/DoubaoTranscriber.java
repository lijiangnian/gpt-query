package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Volcengine/Doubao BigModel recorded-file ASR asynchronous API client. */
public final class DoubaoTranscriber {
    private static final String HOST="https://openspeech.bytedance.com";
    public interface Listener{void stage(String text);}
    public static final class Result{public final String text,language;public final List<SubtitleSegment> segments;public final List<SubtitleExtractor.Word> words;public final long durationMs;
        Result(String t,String l,List<SubtitleSegment>s,List<SubtitleExtractor.Word>w,long d){text=t;language=l;segments=s;words=w;durationMs=d;}}
    static final class Response{final int http;final String status,message,logId;final JSONObject body;Response(int h,String s,String m,String l,JSONObject b){http=h;status=s;message=m;logId=l;body=b;}}
    private DoubaoTranscriber(){}

    public static Result transcribe(String mediaUrl,DoubaoCredentialStore.Credentials c,String language,Listener listener)throws Exception{
        require(mediaUrl,c);String id=UUID.randomUUID().toString();JSONObject request=new JSONObject().put("model_name","bigmodel").put("enable_itn",true).put("show_utterances",true).put("show_words",true);
        if("zh".equals(language))request.put("language","zh-CN");JSONObject body=new JSONObject().put("user",new JSONObject().put("uid",c.appId.isBlank()?"mediaparser":c.appId)).put("audio",new JSONObject().put("url",mediaUrl)).put("request",request);
        if(listener!=null)listener.stage("提交豆包录音文件识别任务…");Response submit=call("/api/v3/auc/bigmodel/submit",c,id,body);
        if(!accepted(submit))throw apiError("豆包任务提交失败",submit);long deadline=System.currentTimeMillis()+25L*60*60*1000;
        while(true){if(Thread.currentThread().isInterrupted())throw new InterruptedException("豆包任务已取消");if(System.currentTimeMillis()>deadline)throw new IOException("豆包任务等待超过25小时");Thread.sleep(2500);Response q=call("/api/v3/auc/bigmodel/query",c,id,null);if("20000000".equals(q.status)){if(listener!=null)listener.stage("读取豆包分句与词级时间戳…");return parse(q.body);}if(!processing(q))throw apiError("豆包任务失败",q);if(listener!=null)listener.stage("豆包服务端处理 · "+(q.message.isBlank()?q.status:q.message));}
    }
    static Result parse(JSONObject root)throws Exception{
        JSONObject result=root.optJSONObject("result");if(result==null&&root.optJSONObject("data")!=null)result=root.getJSONObject("data").optJSONObject("result");if(result==null)throw new IOException("豆包结果缺少 result");
        String full=result.optString("text","").trim(),lang=result.optString("language","");JSONArray us=result.optJSONArray("utterances");List<SubtitleSegment> segs=new ArrayList<>();List<SubtitleExtractor.Word> words=new ArrayList<>();StringBuilder joined=new StringBuilder();long duration=0;
        if(us!=null)for(int i=0;i<us.length();i++){JSONObject u=us.optJSONObject(i);if(u==null)continue;long a=u.optLong("start_time",-1),b=u.optLong("end_time",-1);String text=u.optString("text","").trim();if(a>=0&&b>a&&!text.isBlank()){segs.add(new SubtitleSegment(a/10,Math.max(a/10+1,b/10),text));if(joined.length()>0)joined.append('\n');joined.append(text);duration=Math.max(duration,b);}JSONArray ws=u.optJSONArray("words");if(ws!=null)for(int j=0;j<ws.length();j++){JSONObject w=ws.optJSONObject(j);if(w==null)continue;long wa=w.optLong("start_time",-1),wb=w.optLong("end_time",-1);String wt=w.optString("text","").trim();if(wa>=0&&wb>=wa&&!wt.isBlank())words.add(new SubtitleExtractor.Word(wt,wa,Math.max(wa+1,wb),"",w.optDouble("confidence",-1)));}}
        JSONObject info=root.optJSONObject("audio_info");if(info!=null)duration=Math.max(duration,info.optLong("duration",0));if(full.isBlank())full=joined.toString();if(full.isBlank())throw new IOException("豆包没有返回可用文字");if(segs.isEmpty())throw new IOException("豆包没有返回可用分句时间戳");SubtitleTimeline.segments(segs,duration);if(!words.isEmpty())SubtitleTimeline.words(words,duration);return new Result(full,lang,segs,words,duration);
    }
    static Response probe(DoubaoCredentialStore.Credentials c)throws Exception{
        if(c==null||!c.configured())throw new IllegalArgumentException("请填写豆包 API Key，或 App ID + Access Token");
        String id=UUID.randomUUID().toString();
        boolean turbo=isTurbo(c.resourceId);
        JSONObject audio=turbo?new JSONObject().put("data",""):new JSONObject().put("url","");
        JSONObject body=new JSONObject().put("user",new JSONObject().put("uid",c.appId.isBlank()?"mediaparser-test":c.appId)).put("audio",audio).put("request",new JSONObject().put("model_name","bigmodel"));
        return call(turbo?"/api/v3/auc/bigmodel/recognize/flash":"/api/v3/auc/bigmodel/submit",c,id,body);
    }
    static boolean isTurbo(String resourceId){return "volc.bigasr.auc_turbo".equalsIgnoreCase(resourceId==null?"":resourceId.trim());}
    private static boolean accepted(Response r){return r.http>=200&&r.http<300&&("20000000".equals(r.status)||"20000001".equals(r.status)||"20000002".equals(r.status)||r.status.isBlank());}
    private static boolean processing(Response r){return r.http>=200&&r.http<300&&("20000001".equals(r.status)||"20000002".equals(r.status)||"20000003".equals(r.status));}
    private static Response call(String path,DoubaoCredentialStore.Credentials creds,String requestId,JSONObject body)throws Exception{HttpURLConnection c=(HttpURLConnection)new URI(HOST+path).toURL().openConnection();try{c.setRequestMethod("POST");c.setConnectTimeout(20000);c.setReadTimeout(120000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Content-Type","application/json; charset=UTF-8");c.setRequestProperty("X-Api-Resource-Id",creds.resourceId);c.setRequestProperty("X-Api-Request-Id",requestId);c.setRequestProperty("X-Api-Sequence","-1");if(!creds.apiKey.isBlank())c.setRequestProperty("X-Api-Key",creds.apiKey);else{c.setRequestProperty("X-Api-App-Key",creds.appId);c.setRequestProperty("X-Api-Access-Key",creds.accessToken);}byte[] data=(body==null?"{}":body.toString()).getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}int http=c.getResponseCode();String text=read(http>=400?c.getErrorStream():c.getInputStream());JSONObject json;try{json=text.isBlank()?new JSONObject():new JSONObject(text);}catch(Exception e){json=new JSONObject().put("raw",text.length()>1000?text.substring(0,1000):text);}return new Response(http,nvl(c.getHeaderField("X-Api-Status-Code")),nvl(c.getHeaderField("X-Api-Message")),nvl(c.getHeaderField("X-Tt-Logid")),json);}finally{c.disconnect();}}
    private static void require(String url,DoubaoCredentialStore.Credentials c){if(c==null||!c.configured())throw new IllegalArgumentException("请先设置豆包语音识别凭证");URI u=URI.create(url);if(!"https".equalsIgnoreCase(u.getScheme()))throw new IllegalArgumentException("豆包文件识别要求公网可访问的 HTTPS 音视频直链");}
    private static IOException apiError(String prefix,Response r){String detail=!r.message.isBlank()?r.message:r.body.optString("message","");return new IOException(prefix+" · HTTP "+r.http+(r.status.isBlank()?"":" · "+r.status)+(detail.isBlank()?"":" · "+detail)+(r.logId.isBlank()?"":" · LogID "+r.logId));}
    private static String read(InputStream in)throws Exception{if(in==null)return"";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[8192];int n;while((n=x.read(b))>=0){out.write(b,0,n);if(out.size()>32*1024*1024)throw new IOException("豆包响应过大");}return out.toString(StandardCharsets.UTF_8.name());}}
    private static String nvl(String x){return x==null?"":x.trim();}
}
