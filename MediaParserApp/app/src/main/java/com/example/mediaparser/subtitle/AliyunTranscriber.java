package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Alibaba Model Studio Paraformer-v2 async REST client for public media URLs. */
public final class AliyunTranscriber {
    public interface Listener{void stage(String s);}
    public static final class Result{public final String text;public final List<SubtitleSegment> segments;public final List<SubtitleExtractor.Word> words;public final long durationMs;
        Result(String t,List<SubtitleSegment>s,List<SubtitleExtractor.Word>w,long d){text=t;segments=s;words=w;durationMs=d;}}
    private AliyunTranscriber(){}
    public static Result transcribe(String mediaUrl,String key,String workspace,String language,boolean diarization,Listener listener)throws Exception{
        if(key==null||key.isBlank())throw new IllegalArgumentException("请先设置阿里云百炼 API Key");if(workspace==null||!workspace.matches("[A-Za-z0-9-]{3,80}"))throw new IllegalArgumentException("请先设置阿里云 Workspace ID");
        URI media=URI.create(mediaUrl);boolean temporary="oss".equalsIgnoreCase(media.getScheme());if(!temporary&&!"https".equalsIgnoreCase(media.getScheme()))throw new IllegalArgumentException("阿里云文件转写要求 HTTPS 直链或官方 oss:// 临时文件");
        String base="https://"+workspace+".cn-beijing.maas.aliyuncs.com/api/v1";if(listener!=null)listener.stage("提交公开音视频直链…");
        JSONObject params=new JSONObject().put("timestamp_alignment_enabled",true).put("diarization_enabled",diarization);if("zh".equals(language))params.put("language_hints",new JSONArray().put("zh"));
        JSONObject req=new JSONObject().put("model","paraformer-v2").put("input",new JSONObject().put("file_urls",new JSONArray().put(mediaUrl))).put("parameters",params);
        JSONObject started=request(base+"/services/audio/asr/transcription","POST",key,req,true,temporary);String taskId=started.optJSONObject("output")!=null?started.getJSONObject("output").optString("task_id",""):"";
        if(taskId.isBlank())throw apiError(started,"阿里云没有返回任务ID");long deadline=System.currentTimeMillis()+60L*60*1000;JSONObject state;
        while(true){if(Thread.currentThread().isInterrupted())throw new InterruptedException("阿里云识别已取消");if(System.currentTimeMillis()>deadline)throw new IOException("阿里云任务等待超过60分钟");Thread.sleep(2000);state=request(base+"/tasks/"+taskId,"GET",key,null,false,false);JSONObject out=state.optJSONObject("output");String status=out==null?"":out.optString("task_status","");if(listener!=null)listener.stage("阿里云识别中 · "+(status.isBlank()?"等待服务端":status));if("SUCCEEDED".equals(status))break;if("FAILED".equals(status)||"CANCELED".equals(status))throw apiError(state,"阿里云任务失败");}
        JSONObject output=state.getJSONObject("output");JSONArray results=output.optJSONArray("results");if(results==null||results.length()==0)throw apiError(state,"阿里云没有返回转写结果");String resultUrl=results.getJSONObject(0).optString("transcription_url","");URI resultUri=URI.create(resultUrl);String host=resultUri.getHost();if(!"https".equalsIgnoreCase(resultUri.getScheme())||host==null||!(host.endsWith(".aliyuncs.com")||host.endsWith(".aliyuncs.com.cn")))throw new IOException("阿里云返回了不受信任的结果地址");
        if(listener!=null)listener.stage("读取逐句时间轴…");JSONObject transcript=request(resultUrl,"GET","",null,false,false);return parse(transcript);
    }
    static Result parse(JSONObject root)throws Exception{
        JSONArray transcripts=root.optJSONArray("transcripts");if(transcripts==null&&root.optJSONObject("output")!=null)transcripts=root.getJSONObject("output").optJSONArray("transcripts");if(transcripts==null||transcripts.length()==0)throw new IOException("阿里云结果缺少 transcripts");JSONObject tr=transcripts.getJSONObject(0);String full=tr.optString("text","").trim();JSONArray ss=tr.optJSONArray("sentences");List<SubtitleSegment> segs=new ArrayList<>();StringBuilder joined=new StringBuilder();long duration=0;
        List<SubtitleExtractor.Word> words=new ArrayList<>();if(ss!=null)for(int i=0;i<ss.length();i++){JSONObject s=ss.optJSONObject(i);if(s==null)continue;long begin=s.optLong("begin_time",-1),end=s.optLong("end_time",-1);String text=s.optString("text","").trim();if(begin>=0&&end>begin&&!text.isBlank()){segs.add(new SubtitleSegment(begin/10,Math.max(begin/10+1,end/10),text));String speaker=s.has("speaker_id")?"发言人"+(s.optInt("speaker_id")+1):"";words.add(new SubtitleExtractor.Word(text,begin,end,speaker,s.has("confidence")?s.optDouble("confidence"):-1));duration=Math.max(duration,end);if(joined.length()>0)joined.append('\n');joined.append(text);}}
        if(full.isBlank())full=joined.toString();if(full.isBlank())throw new IOException("阿里云没有返回可用文字");long original=root.optJSONObject("properties")!=null?root.getJSONObject("properties").optLong("original_duration_in_milliseconds",0):0;duration=Math.max(duration,original);SubtitleTimeline.segments(segs,duration);return new Result(full,segs,words,duration);
    }
    private static JSONObject request(String url,String method,String key,JSONObject body,boolean async,boolean oss)throws Exception{HttpURLConnection c=(HttpURLConnection)new URI(url).toURL().openConnection();c.setRequestMethod(method);c.setConnectTimeout(20000);c.setReadTimeout(120000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");if(key!=null&&!key.isBlank())c.setRequestProperty("Authorization","Bearer "+key.trim());if(async)c.setRequestProperty("X-DashScope-Async","enable");if(oss)c.setRequestProperty("X-DashScope-OssResourceResolve","enable");if(body!=null){byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=UTF-8");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}int code=c.getResponseCode();String text=read(code>=400?c.getErrorStream():c.getInputStream());c.disconnect();JSONObject json;try{json=new JSONObject(text);}catch(Exception e){throw new IOException("阿里云返回了非JSON响应（HTTP "+code+"）");}if(code<200||code>=300)throw apiError(json,"阿里云 HTTP "+code);return json;}
    private static String read(InputStream in)throws Exception{if(in==null)return "";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=x.read(b))>=0){out.write(b,0,n);if(out.size()>8*1024*1024)throw new IOException("阿里云响应过大");}return out.toString(StandardCharsets.UTF_8.name());}}
    private static IOException apiError(JSONObject j,String fallback){String code=j.optString("code","");String msg=j.optString("message","");JSONObject out=j.optJSONObject("output");if(out!=null){code=out.optString("code",code);msg=out.optString("message",msg);}return new IOException((code.isBlank()?fallback:code)+(msg.isBlank()?"":"："+msg));}
}

