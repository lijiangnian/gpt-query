package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Official Alibaba Model Studio Qwen3-ASR asynchronous file-transcription client. */
public final class Qwen3AsrTranscriber {
    public interface Listener{void stage(String s);}
    public static final String MODEL="qwen3-asr-flash-filetrans";
    public static final class Result{public final String text,language,coverageWarning;public final List<SubtitleSegment> segments;public final List<SubtitleExtractor.Word> words;public final long durationMs;
        Result(String t,String l,List<SubtitleSegment>s,List<SubtitleExtractor.Word>w,long d,String warning){text=t;language=l;segments=s;words=w;durationMs=d;coverageWarning=warning==null?"":warning;}
        public boolean lowCoverage(){return !coverageWarning.isBlank();}}
    private Qwen3AsrTranscriber(){}
    public static Result transcribe(String mediaUrl,String key,String workspace,String language,Listener listener)throws Exception{
        require(mediaUrl,key,workspace);String base="https://"+workspace+".cn-beijing.maas.aliyuncs.com/api/v1";
        JSONObject parameters=new JSONObject().put("channel_id",new JSONArray().put(0)).put("enable_itn",true).put("enable_words",true);
        if("zh".equals(language))parameters.put("language","zh");
        JSONObject body=new JSONObject().put("model",MODEL).put("input",new JSONObject().put("file_url",mediaUrl)).put("parameters",parameters);
        boolean temporary=mediaUrl.startsWith("oss://");if(listener!=null)listener.stage("提交 Qwen3-ASR 长音频文件任务…");JSONObject started=request(base+"/services/audio/asr/transcription","POST",key,body,true,temporary);
        JSONObject output=started.optJSONObject("output");String taskId=output==null?"":output.optString("task_id","");if(taskId.isBlank())throw error(started,"Qwen3-ASR 没有返回任务 ID");
        long deadline=System.currentTimeMillis()+13L*60*60*1000;JSONObject state;
        while(true){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Qwen3-ASR 任务已取消");if(System.currentTimeMillis()>deadline)throw new IOException("Qwen3-ASR 任务等待超过13小时");Thread.sleep(2500);state=request(base+"/tasks/"+taskId,"GET",key,null,false,false);JSONObject o=state.optJSONObject("output");String status=o==null?"":o.optString("task_status","");if(listener!=null)listener.stage("Qwen3-ASR 服务端处理 · "+(status.isBlank()?"等待":status));if("SUCCEEDED".equals(status))break;if("FAILED".equals(status)||"CANCELED".equals(status))throw error(state,"Qwen3-ASR 任务失败");}
        String url=resultUrl(state);if(url.isBlank())throw error(state,"Qwen3-ASR 任务已完成，但结果中没有 transcription_url");URI resultUri=safeResultUri(url);
        if(listener!=null)listener.stage("读取 Qwen3-ASR 分句与词级时间戳…");return parse(request(resultUri.toString(),"GET","",null,false,false));
    }
    static Result parse(JSONObject root)throws Exception{
        JSONArray transcripts=root.optJSONArray("transcripts");if(transcripts==null&&root.optJSONObject("output")!=null)transcripts=root.getJSONObject("output").optJSONArray("transcripts");if(transcripts==null||transcripts.length()==0)throw new IOException("Qwen3-ASR 结果缺少 transcripts");
        JSONObject properties=root.optJSONObject("properties");long originalDuration=properties==null?0:properties.optLong("original_duration_in_milliseconds",0);
        List<SubtitleSegment> segments=new ArrayList<>();List<SubtitleExtractor.Word> words=new ArrayList<>();StringBuilder all=new StringBuilder();String lang="";long lastEnd=0;
        for(int ti=0;ti<transcripts.length();ti++){JSONObject tr=transcripts.optJSONObject(ti);if(tr==null)continue;if(lang.isBlank())lang=tr.optString("language","");JSONArray ss=tr.optJSONArray("sentences");StringBuilder channelSentences=new StringBuilder();if(ss!=null)for(int i=0;i<ss.length();i++){JSONObject s=ss.optJSONObject(i);if(s==null)continue;if(lang.isBlank())lang=s.optString("language","");long a=s.optLong("begin_time",-1),b=s.optLong("end_time",-1);String text=s.optString("text","").trim();if(a>=0&&b>a&&!text.isBlank()){segments.add(new SubtitleSegment(a/10,Math.max(a/10+1,b/10),text));if(channelSentences.length()>0)channelSentences.append('\n');channelSentences.append(text);lastEnd=Math.max(lastEnd,b);}JSONArray ww=s.optJSONArray("words");if(ww!=null)for(int j=0;j<ww.length();j++){JSONObject w=ww.optJSONObject(j);if(w==null)continue;long wa=w.optLong("begin_time",-1),wb=w.optLong("end_time",-1);String wt=w.optString("text","").trim();if(wa>=0&&wb>=wa&&!wt.isBlank())words.add(new SubtitleExtractor.Word(wt,wa,Math.max(wa+1,wb),""));}}
            // The provider also returns a channel-level transcript. Preserve it when it contains
            // more text than the sentence array, while keeping only real timestamps in SRT.
            String channelText=tr.optString("text","").trim();String chosen=visibleChars(channelText)>visibleChars(channelSentences.toString())?channelText:channelSentences.toString();
            if(!chosen.isBlank()){if(all.length()>0)all.append('\n');all.append(chosen);}
        }
        if(all.length()==0||segments.isEmpty())throw new IOException("Qwen3-ASR 没有返回可用分句");long duration=Math.max(originalDuration,lastEnd);SubtitleTimeline.segments(segments,duration);if(!words.isEmpty())SubtitleTimeline.words(words,duration);String warning=coverageWarning(segments,all.toString(),duration);return new Result(all.toString(),lang,segments,words,duration,warning);
    }
    static String coverageWarning(List<SubtitleSegment> segments,String text,long durationMs){
        if(durationMs<12_000||segments==null||segments.isEmpty())return "";int chars=visibleChars(text),count=segments.size();double seconds=durationMs/1000d,rate=chars/seconds;long first=segments.get(0).startCs*10,last=segments.get(segments.size()-1).endCs*10;
        boolean veryShort=count<=2&&chars<Math.max(18,Math.round(seconds*1.15));boolean edgeGap=count<=3&&rate<1.25&&(first>Math.min(6_000,Math.round(durationMs*.28))||last<Math.round(durationMs*.62));boolean tinyLong=durationMs>=30_000&&rate<.55;
        if(!(veryShort||edgeGap||tinyLong))return "";return "识别覆盖率异常："+Math.round(seconds)+"秒音频只返回"+count+"段、约"+chars+"字，可能漏掉开头或伴奏中的人声";
    }
    private static int visibleChars(String s){if(s==null)return 0;int n=0;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isLetterOrDigit(c)||Character.UnicodeBlock.of(c)==Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)n++;}return n;}
    /** Current file-transcription tasks return output.result.transcription_url. Keep legacy shapes readable. */
    static String resultUrl(JSONObject state){
        if(state==null)return "";JSONObject output=state.optJSONObject("output");if(output==null)return "";
        JSONObject result=output.optJSONObject("result");String url=result==null?"":result.optString("transcription_url","");
        if(url.isBlank())url=output.optString("transcription_url","");
        if(url.isBlank()){JSONArray results=output.optJSONArray("results");if(results!=null&&results.length()>0){JSONObject first=results.optJSONObject(0);if(first!=null)url=first.optString("transcription_url","");}}
        return url.trim();
    }
    /** The URL is delivered by the authenticated task API. Accept public CDNs, but never local/private targets. */
    static URI safeResultUri(String raw)throws IOException{
        final URI uri;try{uri=URI.create(raw==null?"":raw.trim());}catch(Exception e){throw new IOException("Qwen3-ASR 返回了格式错误的结果地址");}
        String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT),host=uri.getHost();
        if(host==null||host.isBlank()||!("https".equals(scheme)||"http".equals(scheme))||uri.getUserInfo()!=null||isPrivateHost(host))
            throw new IOException("Qwen3-ASR 结果地址被安全校验拒绝（"+(scheme.isBlank()?"未知协议":scheme)+"://"+(host==null?"未知主机":host)+"）");
        // Some signed OSS links are emitted as HTTP. OSS signatures are path/query based, so upgrade them before download.
        if("http".equals(scheme)){try{return new URI("https",null,host,uri.getPort()==80?-1:uri.getPort(),uri.getPath(),uri.getQuery(),uri.getFragment());}catch(Exception e){throw new IOException("Qwen3-ASR 结果地址无法升级为 HTTPS");}}
        return uri;
    }
    private static boolean isPrivateHost(String raw){String h=raw.toLowerCase(Locale.ROOT);if(h.equals("localhost")||h.endsWith(".localhost")||h.endsWith(".local")||h.equals("::1")||h.contains(":"))return true;
        if(!h.matches("\\d{1,3}(?:\\.\\d{1,3}){3}"))return false;String[] p=h.split("\\.");int[] n=new int[4];try{for(int i=0;i<4;i++){n[i]=Integer.parseInt(p[i]);if(n[i]>255)return true;}}catch(Exception e){return true;}
        return n[0]==0||n[0]==10||n[0]==127||n[0]>=224||(n[0]==169&&n[1]==254)||(n[0]==172&&n[1]>=16&&n[1]<=31)||(n[0]==192&&n[1]==168);
    }
    private static void require(String url,String key,String ws){if(key==null||key.isBlank())throw new IllegalArgumentException("请先设置阿里云百炼 API Key");if(ws==null||!ws.matches("[A-Za-z0-9-]{3,80}"))throw new IllegalArgumentException("请先设置北京地域 Workspace ID");URI u=URI.create(url);if(!"https".equalsIgnoreCase(u.getScheme())&&!"oss".equalsIgnoreCase(u.getScheme()))throw new IllegalArgumentException("Qwen3-ASR 文件转写要求 HTTPS 直链或官方 oss:// 临时文件");}
    private static JSONObject request(String url,String method,String key,JSONObject body,boolean async,boolean oss)throws Exception{HttpURLConnection c=(HttpURLConnection)new URI(url).toURL().openConnection();try{c.setRequestMethod(method);c.setConnectTimeout(20000);c.setReadTimeout(120000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","MediaParser/0.4 Android");if(key!=null&&!key.isBlank())c.setRequestProperty("Authorization","Bearer "+key.trim());if(async)c.setRequestProperty("X-DashScope-Async","enable");if(oss)c.setRequestProperty("X-DashScope-OssResourceResolve","enable");if(body!=null){byte[] d=body.toString().getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=UTF-8");c.setFixedLengthStreamingMode(d.length);try(OutputStream out=c.getOutputStream()){out.write(d);}}int code=c.getResponseCode();String text=read(code>=400?c.getErrorStream():c.getInputStream());JSONObject j;try{j=new JSONObject(text);}catch(Exception e){throw new IOException("Qwen3-ASR 返回非 JSON（HTTP "+code+"）");}if(code<200||code>=300)throw error(j,"Qwen3-ASR HTTP "+code);return j;}finally{c.disconnect();}}
    private static String read(InputStream in)throws Exception{if(in==null)return"";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[8192];int n;while((n=x.read(b))>=0){out.write(b,0,n);if(out.size()>32*1024*1024)throw new IOException("Qwen3-ASR 响应过大");}return out.toString(StandardCharsets.UTF_8.name());}}
    private static IOException error(JSONObject j,String fallback){String code=j.optString("code","");String msg=j.optString("message","");JSONObject o=j.optJSONObject("output");if(o!=null){code=o.optString("code",code);msg=o.optString("message",msg);}return new IOException((code.isBlank()?fallback:code)+(msg.isBlank()?"":"："+msg));}
}
