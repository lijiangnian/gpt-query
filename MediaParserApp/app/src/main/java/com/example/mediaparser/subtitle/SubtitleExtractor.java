package com.example.mediaparser.subtitle;

import android.content.Context;
import android.media.MediaMetadataRetriever;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.util.FileSaver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class SubtitleExtractor {
    private static final String MODEL = "gemini-3.5-transcribe";
    private static final String API = "https://generativelanguage.googleapis.com";

    private SubtitleExtractor() {}

    public static String errorMessage(Throwable error, String apiKey) {
        return GeminiHttp.redact(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), apiKey);
    }

    public interface Listener {
        default void onPhase(SubtitleProgress.Phase phase, SubtitleProgress.State state, String detail) {}
        void onStage(String text);
        void onSourceProgress(int percent, long downloaded, long total);
        void onUploadProgress(int percent, long uploaded, long total);
        void onTranscribeStart(long audioDurationMs);
    }

    public static SubtitleOutput extract(Context context, MediaItem source, String title,
                                         String apiKey, Listener listener) throws Exception {
        return extract(context, source, title, SubtitleProvider.GEMINI, apiKey, listener);
    }

    public static SubtitleOutput extract(Context context, MediaItem source, String title,
                                         SubtitleProvider provider, String apiKey, Listener listener) throws Exception {
        return extract(context, source, title, provider, apiKey, TranscriptionOptions.defaults(), listener);
    }

    public static SubtitleOutput extract(Context context, MediaItem source, String title,
                                         SubtitleProvider provider, String apiKey, TranscriptionOptions options,
                                         Listener listener) throws Exception {
        if (provider == SubtitleProvider.AUTO) throw new IllegalArgumentException("自动选择需指定主引擎");
        if (options == null) throw new IllegalArgumentException("缺少识别设置");
        if (provider == null) throw new IllegalArgumentException("请选择字幕路线");
        if (source == null || source.url.isBlank()) throw new IllegalArgumentException("没有可用于字幕识别的音视频");
        String key = apiKey == null ? "" : apiKey.trim();
        if (provider == SubtitleProvider.LOCAL) return extractLocal(context,source,title,options,listener);
        if (provider == SubtitleProvider.ALIYUN) return extractAliyun(context,source,title,key,options,listener);
        if (provider == SubtitleProvider.QWEN3) return extractQwen3(context,source,title,key,options,listener);
        if (provider == SubtitleProvider.DOUBAO) return extractDoubao(context,source,title,options,listener);
        if (key.isBlank()) throw new IllegalArgumentException("请先设置 " + provider.label + " API Key");

        if (listener != null) listener.onStage("正在准备音频…");
        File audio = FileSaver.prepareAudioForCloud(context, source, (done, total) -> {
            if (listener != null && total > 0) {
                int p = (int) Math.min(100L, done * 100L / total);
                listener.onSourceProgress(p, done, total);
            }
        });

        try {
            long duration = audioDurationMs(audio);
            SubtitleTimeline.duration(duration);
            if (duration > 30L*60*1000) throw new IllegalStateException("生成带时间轴字幕单次最多支持30分钟音频");
            phase(listener, SubtitleProgress.Phase.PREPARE, SubtitleProgress.State.COMPLETE, "音频已准备");
            SubtitleProgress.Phase phase = provider == SubtitleProvider.GROQ ? SubtitleProgress.Phase.GROQ : SubtitleProgress.Phase.GEMINI;
            try {
                Parsed parsed = transcribeAudio(audio,provider,key,options,scoped(listener,phase));
                SubtitleOutput output = saveRaw(context,title,"_"+provider.label+"原稿",parsed,provider.label);
                phase(listener,phase,output.hasTiming()?SubtitleProgress.State.COMPLETE:SubtitleProgress.State.WARNING,
                        SubtitleProgress.rawDetail(output));
                return output;
            } catch (Exception e) { phase(listener,phase,SubtitleProgress.State.FAILED,errorMessage(e,key)); throw e; }
        } finally { audio.delete(); }
    }

    private static SubtitleOutput extractLocal(Context context,MediaItem source,String title,TranscriptionOptions options,Listener listener)throws Exception{
        LocalModelManager.ModelSpec spec=LocalModelManager.selected(context);
        if(!LocalModelManager.isInstalled(context,spec))throw new IllegalStateException("请先在本地模型管理下载并选择 "+spec.name);
        if(listener!=null)listener.onStage("准备本地音频 · 不上传云端…");File audio=FileSaver.prepareAudioForCloud(context,source,(done,total)->{if(listener!=null&&total>0)listener.onSourceProgress((int)Math.min(100,done*100/total),done,total);});
        try{phase(listener,SubtitleProgress.Phase.PREPARE,SubtitleProgress.State.COMPLETE,"音频已准备 · 全程本机处理");phase(listener,SubtitleProgress.Phase.LOCAL,SubtitleProgress.State.RUNNING,"加载 "+spec.name+"…");LocalTranscriber.Result r=LocalTranscriber.run(context,audio,spec,options.language,(stage,done,total)->phase(listener,SubtitleProgress.Phase.LOCAL,SubtitleProgress.State.RUNNING,stage+(total>0?" "+done+"/"+total:"")));
            List<SubtitleSegment> segs=new ArrayList<>();for(LocalTranscriber.Segment s:r.segments)segs.add(new SubtitleSegment(s.startMs/10,Math.max(s.startMs/10+1,s.endMs/10),s.text));List<Word> words=new ArrayList<>();for(LocalTranscriber.Word w:r.words)if(!w.text.isBlank()&&w.endMs>=w.startMs)words.add(new Word(w.text,w.startMs,w.endMs,""));Parsed p=new Parsed(r.text,r.language,segs,words,r.durationMs,r.timingNote);SubtitleOutput out=saveRaw(context,title,"_本地_"+spec.name,p,"本地 · "+spec.name);phase(listener,SubtitleProgress.Phase.LOCAL,SubtitleProgress.State.COMPLETE,SubtitleProgress.rawDetail(out));return out;
        }catch(Exception e){phase(listener,SubtitleProgress.Phase.LOCAL,SubtitleProgress.State.FAILED,errorMessage(e,""));throw e;}finally{audio.delete();}
    }

    private static SubtitleOutput extractAliyun(Context context,MediaItem source,String title,String key,TranscriptionOptions options,Listener listener)throws Exception{
        String workspace=AliyunSettings.workspace(context);phase(listener,SubtitleProgress.Phase.PREPARE,SubtitleProgress.State.COMPLETE,source.isLocal()?"本地音频将上传到阿里官方临时存储 · 48小时自动清理":"使用解析后的公开直链 · 音视频由阿里云服务器读取");phase(listener,SubtitleProgress.Phase.ALIYUN,SubtitleProgress.State.RUNNING,"提交 Paraformer-v2…");
        File local=null;try{String mediaUrl=source.url;if(source.isLocal()){local=FileSaver.prepareAudioForCloud(context,source,(done,total)->{if(listener!=null&&total>0)listener.onSourceProgress((int)Math.min(100,done*100/total),done,total);});mediaUrl=AliyunTemporaryUpload.upload(local,key,"paraformer-v2",s->phase(listener,SubtitleProgress.Phase.ALIYUN,SubtitleProgress.State.RUNNING,s));}AliyunTranscriber.Result r=AliyunTranscriber.transcribe(mediaUrl,key,workspace,options.language,options.diarization,s->phase(listener,SubtitleProgress.Phase.ALIYUN,SubtitleProgress.State.RUNNING,s));Parsed p=new Parsed(r.text,options.language,r.segments,r.words,r.durationMs,"阿里云句级时间轴"+(options.diarization?" · 已请求说话人分离":""));SubtitleOutput out=saveRaw(context,title,"_阿里云Paraformer原稿",p,"阿里云 Paraformer-v2");phase(listener,SubtitleProgress.Phase.ALIYUN,SubtitleProgress.State.COMPLETE,SubtitleProgress.rawDetail(out));return out;
        }catch(Exception e){phase(listener,SubtitleProgress.Phase.ALIYUN,SubtitleProgress.State.FAILED,errorMessage(e,key));throw e;}
        finally{if(local!=null)local.delete();}
    }

    private static SubtitleOutput extractQwen3(Context context,MediaItem source,String title,String key,TranscriptionOptions options,Listener listener)throws Exception{
        String workspace=AliyunSettings.workspace(context);phase(listener,SubtitleProgress.Phase.PREPARE,SubtitleProgress.State.COMPLETE,source.isLocal()?"本地音频将上传到阿里官方临时存储 · 单文件1GB内 · 48小时自动清理":"使用解析后的公网直链 · 最长支持12小时/2GB（以官方当前限制为准）");phase(listener,SubtitleProgress.Phase.QWEN3,SubtitleProgress.State.RUNNING,"提交 Qwen3-ASR 文件转写…");
        File local=null;try{String mediaUrl=source.url;if(source.isLocal()){local=FileSaver.prepareAudioForCloud(context,source,(done,total)->{if(listener!=null&&total>0)listener.onSourceProgress((int)Math.min(100,done*100/total),done,total);});mediaUrl=AliyunTemporaryUpload.upload(local,key,Qwen3AsrTranscriber.MODEL,s->phase(listener,SubtitleProgress.Phase.QWEN3,SubtitleProgress.State.RUNNING,s));}Qwen3AsrTranscriber.Result r=Qwen3AsrTranscriber.transcribe(mediaUrl,key,workspace,options.language,s->phase(listener,SubtitleProgress.Phase.QWEN3,SubtitleProgress.State.RUNNING,s));String warning="Qwen3-ASR 句级时间轴"+(r.words.isEmpty()?" · 接口未返回词级时间戳":" · 已保存词级时间戳")+(r.lowCoverage()?" · "+r.coverageWarning:"");Parsed p=new Parsed(r.text,r.language,r.segments,r.words,r.durationMs,warning);SubtitleOutput out=saveRaw(context,title,"_Qwen3-ASR原稿",p,"阿里云 Qwen3-ASR");phase(listener,SubtitleProgress.Phase.QWEN3,r.lowCoverage()?SubtitleProgress.State.WARNING:SubtitleProgress.State.COMPLETE,SubtitleProgress.rawDetail(out));return out;
        }catch(Exception e){phase(listener,SubtitleProgress.Phase.QWEN3,SubtitleProgress.State.FAILED,errorMessage(e,key));throw e;}
        finally{if(local!=null)local.delete();}
    }

    private static SubtitleOutput extractDoubao(Context context,MediaItem source,String title,TranscriptionOptions options,Listener listener)throws Exception{
        DoubaoCredentialStore.Credentials creds=DoubaoCredentialStore.load(context);phase(listener,SubtitleProgress.Phase.PREPARE,SubtitleProgress.State.COMPLETE,source.isLocal()?"本地文件使用豆包流式直传 · 不需要公网 URL":"使用解析后的公网直链 · 豆包服务端读取音视频");phase(listener,SubtitleProgress.Phase.DOUBAO,SubtitleProgress.State.RUNNING,"提交豆包录音文件识别…");
        try{DoubaoTranscriber.Result r;
            if(source.isLocal()){
                File audio=FileSaver.prepareAudioForCloud(context,source,(done,total)->{if(listener!=null&&total>0)listener.onSourceProgress((int)Math.min(100,done*100/total),done,total);});
                try{r=DoubaoStreamingTranscriber.transcribe(audio,context.getCacheDir(),creds,options.language,s->phase(listener,SubtitleProgress.Phase.DOUBAO,SubtitleProgress.State.RUNNING,s));}finally{audio.delete();}
            }else try{r=DoubaoTranscriber.transcribe(source.url,creds,options.language,s->phase(listener,SubtitleProgress.Phase.DOUBAO,SubtitleProgress.State.RUNNING,s));}
            catch(Exception first){if(!isDoubaoAudioUrlFailure(first))throw first;
                phase(listener,SubtitleProgress.Phase.DOUBAO,SubtitleProgress.State.WARNING,"标准版无法下载防盗链音频，自动切换流式小时版直传");
                File audio=FileSaver.prepareAudioForCloud(context,source,(done,total)->{if(listener!=null&&total>0)listener.onSourceProgress((int)Math.min(100,done*100/total),done,total);});
                try{r=DoubaoStreamingTranscriber.transcribe(audio,context.getCacheDir(),creds,options.language,s->phase(listener,SubtitleProgress.Phase.DOUBAO,SubtitleProgress.State.RUNNING,s));}finally{audio.delete();}
            }
            Parsed p=new Parsed(r.text,r.language,r.segments,r.words,r.durationMs,"豆包句级时间轴"+(r.words.isEmpty()?" · 接口未返回词级时间戳":" · 已保存词级时间戳"));SubtitleOutput out=saveRaw(context,title,"_豆包ASR原稿",p,"豆包 ASR（标准版/流式直传回退）");phase(listener,SubtitleProgress.Phase.DOUBAO,SubtitleProgress.State.COMPLETE,SubtitleProgress.rawDetail(out));return out;
        }catch(Exception e){phase(listener,SubtitleProgress.Phase.DOUBAO,SubtitleProgress.State.FAILED,errorMessage(e,creds.apiKey+creds.accessToken));throw e;}
    }
    static boolean isDoubaoAudioUrlFailure(Throwable e){String m=e==null||e.getMessage()==null?"":e.getMessage().toLowerCase(Locale.ROOT);return m.contains("45000006")||m.contains("invalid audio uri")||m.contains("audio download failed");}

    /** AUTO tries the requested primary first, then configured fallbacks. Cloud calls require task permission. */
    public static SubtitleOutput extractAuto(Context context,MediaItem source,String title,SubtitleProvider primary,boolean cloudPermitted,TranscriptionOptions options,Listener listener)throws Exception{
        if(primary==null||primary==SubtitleProvider.AUTO)primary=SubtitleProvider.LOCAL;
        LinkedHashSet<SubtitleProvider> order=new LinkedHashSet<>();order.add(primary);order.add(SubtitleProvider.LOCAL);order.add(SubtitleProvider.QWEN3);order.add(SubtitleProvider.DOUBAO);order.add(SubtitleProvider.ALIYUN);order.add(SubtitleProvider.GROQ);order.add(SubtitleProvider.GEMINI);
        StringBuilder failures=new StringBuilder();int attempted=0;SubtitleOutput partial=null;
        for(SubtitleProvider p:order){if(p.isCloud()&&!cloudPermitted){phase(listener,phaseFor(p),SubtitleProgress.State.SKIPPED,"未获得本次潜在付费调用许可");continue;}String missing=missingConfiguration(context,p);if(!missing.isBlank()){phase(listener,phaseFor(p),SubtitleProgress.State.SKIPPED,missing);continue;}attempted++;phase(listener,SubtitleProgress.Phase.AUTO,SubtitleProgress.State.RUNNING,"尝试 "+p.label+(p==primary?"（主引擎）":"（备用）"));try{SubtitleOutput result=extract(context,source,title,p,keyFor(context,p),options,listener);if(isLowCoverage(result)){if(partial==null)partial=result;if(failures.length()>0)failures.append('\n');failures.append(p.label).append("：疑似漏识别（原稿已保留）");phase(listener,SubtitleProgress.Phase.AUTO,SubtitleProgress.State.RUNNING,p.label+"覆盖率异常，继续备用引擎");continue;}phase(listener,SubtitleProgress.Phase.AUTO,SubtitleProgress.State.COMPLETE,"实际使用："+result.actualEngine);return result;}catch(Exception e){String m=errorMessage(e,keyFor(context,p));if(failures.length()>0)failures.append('\n');failures.append(p.label).append("：").append(m);phase(listener,SubtitleProgress.Phase.AUTO,SubtitleProgress.State.RUNNING,p.label+"失败，继续备用引擎");}}
        if(partial!=null){phase(listener,SubtitleProgress.Phase.AUTO,SubtitleProgress.State.WARNING,"备用引擎均不可用；保留低覆盖原稿："+partial.actualEngine);return partial;}if(attempted==0)throw new IllegalStateException("没有可执行的引擎。请安装本地模型、配置 API，或授权本次云端调用。");throw new IllegalStateException("所有可用 ASR 引擎均失败：\n"+failures);
    }
    static boolean isLowCoverage(SubtitleOutput output){return output!=null&&output.timingWarning.contains("识别覆盖率异常");}
    private static String missingConfiguration(Context c,SubtitleProvider p){if(p==SubtitleProvider.LOCAL)return LocalModelManager.isInstalled(c,LocalModelManager.selected(c))?"":"本地模型未安装";if(p==SubtitleProvider.QWEN3||p==SubtitleProvider.ALIYUN)return AliyunKeyStore.hasKey(c)&&!AliyunSettings.workspace(c).isBlank()?"":"阿里云 Key/Workspace 未配置";if(p==SubtitleProvider.DOUBAO)return DoubaoCredentialStore.load(c).configured()?"":"豆包凭证未配置";if(p==SubtitleProvider.GROQ)return GroqKeyStore.hasKey(c)?"":"Groq Key 未配置";if(p==SubtitleProvider.GEMINI)return GeminiKeyStore.hasKey(c)?"":"Gemini Key 未配置";return "不支持的引擎";}
    private static String keyFor(Context c,SubtitleProvider p){if(p==SubtitleProvider.QWEN3||p==SubtitleProvider.ALIYUN)return AliyunKeyStore.load(c);if(p==SubtitleProvider.GROQ)return GroqKeyStore.load(c);if(p==SubtitleProvider.GEMINI)return GeminiKeyStore.load(c);return p==SubtitleProvider.LOCAL?"local":"doubao";}
    private static SubtitleProgress.Phase phaseFor(SubtitleProvider p){switch(p){case LOCAL:return SubtitleProgress.Phase.LOCAL;case ALIYUN:return SubtitleProgress.Phase.ALIYUN;case QWEN3:return SubtitleProgress.Phase.QWEN3;case DOUBAO:return SubtitleProgress.Phase.DOUBAO;case GROQ:return SubtitleProgress.Phase.GROQ;case GEMINI:return SubtitleProgress.Phase.GEMINI;default:return SubtitleProgress.Phase.AUTO;}}

    static Parsed transcribeAudio(File audio, SubtitleProvider provider, String key,
                                  TranscriptionOptions options, Listener listener) throws Exception {
        String mime = AudioMime.detect(audio);
        long durationMs = audioDurationMs(audio);
        if (provider == SubtitleProvider.GROQ)
            return parseSafe(GroqTranscriber.transcribe(key, audio, mime, durationMs, options, listener), provider, durationMs);
        if (provider != SubtitleProvider.GEMINI) throw new IllegalArgumentException("请选择单个转写服务");
        UploadedFile uploaded = uploadFile(key, audio, mime, listener);
        try {
            waitUntilActive(key, uploaded, listener);
            if (listener != null) { listener.onStage("Gemini 正在生成字幕…"); listener.onTranscribeStart(durationMs); }
            return parseSafe(transcribe(key, uploaded.uri, uploaded.mimeType, options, listener), provider, durationMs);
        } finally { deleteRemoteFile(key, uploaded.name); }
    }

    static String responseText(JSONObject root, SubtitleProvider provider) {
        if (provider == SubtitleProvider.GROQ) return clean(root.optString("text",""));
        String text=clean(root.optString("output_text",""));
        if (!text.isBlank()) return text;
        StringBuilder full=new StringBuilder(); JSONArray steps=root.optJSONArray("steps");
        if (steps!=null) for(int i=0;i<steps.length();i++) {
            JSONObject step=steps.optJSONObject(i); if(step==null)continue;
            JSONArray parts=step.optJSONArray("content"); if(parts==null)continue;
            for(int j=0;j<parts.length();j++) {
                JSONObject part=parts.optJSONObject(j); if(part==null)continue;
                String t=clean(part.optString("text","")); if(!t.isBlank()){if(full.length()>0)full.append('\n');full.append(t);}
            }
        }
        return clean(full.toString());
    }

    static Parsed parseSafe(JSONObject response, SubtitleProvider provider, long duration) throws Exception {
        try {
            Parsed parsed=provider==SubtitleProvider.GROQ?GroqTranscriber.parse(response,duration):parseInteraction(response,duration);
            SubtitleTimeline.words(parsed.words,duration);
            SubtitleTimeline.segments(parsed.segments,duration);
            if(parsed.fullText.isBlank()) throw new IllegalStateException("接口没有返回可用文字");
            return new Parsed(parsed.fullText,parsed.language,parsed.segments,parsed.words,duration,parsed.timingWarning);
        } catch (IllegalStateException | java.io.IOException e) {
            String full=responseText(response,provider);
            if(full.isBlank()) throw e;
            return new Parsed(full,"",java.util.Collections.emptyList(),java.util.Collections.emptyList(),duration,
                    "时间轴不合格，未导出SRT："+e.getMessage());
        }
    }

    static SubtitleOutput rawOutput(Parsed parsed) {
        return SubtitleOutput.timed(new SubtitleOutput(parsed.segments,parsed.fullText,buildSrt(parsed.segments),parsed.language,"","",parsed.words),parsed.durationMs,parsed.timingWarning);
    }
    private static SubtitleOutput saveRaw(Context context,String title,String suffix,Parsed parsed) throws Exception { return saveRaw(context,title,suffix,parsed,suffix); }
    private static SubtitleOutput saveRaw(Context context,String title,String suffix,Parsed parsed,String engine) throws Exception {
        if(parsed.fullText.isBlank()) throw new IllegalStateException("接口没有返回可用文字");
        String srt=buildSrt(parsed.segments);
        String txtUri=SubtitleSaver.saveText(context,title,suffix,".txt","text/plain",parsed.fullText);
        String srtUri=srt.isBlank()?"":SubtitleSaver.saveText(context,title,suffix,".srt","application/x-subrip",srt);
        String jsonUri=SubtitleSaver.saveText(context,title,suffix+"_alignment",".json","application/json",alignmentJson(parsed,engine).toString(2));
        return SubtitleOutput.timed(new SubtitleOutput(parsed.segments,parsed.fullText,srt,parsed.language,srtUri,txtUri,parsed.words,jsonUri,engine),parsed.durationMs,parsed.timingWarning);
    }
    static JSONObject alignmentJson(Parsed p,String engine)throws Exception{JSONObject root=new JSONObject().put("schema","mediaparser-asr-alignment-v1").put("actual_engine",engine).put("language",p.language).put("duration_ms",p.durationMs).put("timing_warning",p.timingWarning);JSONArray segs=new JSONArray();for(int i=0;i<p.segments.size();i++){SubtitleSegment s=p.segments.get(i);segs.put(new JSONObject().put("segment_id",i+1).put("start_ms",s.startCs*10).put("end_ms",s.endCs*10).put("text",s.text));}JSONArray words=new JSONArray();for(Word w:p.words){JSONObject x=new JSONObject().put("start_ms",w.startMs).put("end_ms",w.endMs).put("text",w.text).put("speaker",w.speaker);if(w.confidence>=0)x.put("confidence",w.confidence);words.put(x);}return root.put("segments",segs).put("words",words);}
    private static void phase(Listener listener,SubtitleProgress.Phase phase,SubtitleProgress.State state,String detail) {
        if(listener!=null)listener.onPhase(phase,state,detail);
    }
    private static Listener scoped(Listener listener,SubtitleProgress.Phase phase) {
        phase(listener,phase,SubtitleProgress.State.RUNNING,"连接服务…");
        return new Listener() {
            public void onStage(String text){phase(listener,phase,SubtitleProgress.State.RUNNING,text);}
            public void onSourceProgress(int percent,long done,long total){}
            public void onUploadProgress(int percent,long done,long total){onStage("上传 "+percent+"%（"+done/1024+" / "+total/1024+" KB）");}
            public void onTranscribeStart(long duration){onStage("AI识别中 · 服务端未提供百分比");}
        };
    }

    /** Both providers get their own attempt; a failure must not suppress the other original. */
    private static UploadedFile uploadFile(String key, File file, String mimeType, Listener listener) throws Exception {
        long size = file.length();
        HttpURLConnection start = open(API + "/upload/v1beta/files", "POST", key);
        String uploadUrl;
        try {
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable");
        start.setRequestProperty("X-Goog-Upload-Command", "start");
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", Long.toString(size));
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mimeType);
        start.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        start.setDoOutput(true);
        byte[] meta = "{\"file\":{\"display_name\":\"MediaParser subtitle audio\"}}".getBytes(StandardCharsets.UTF_8);
        start.setFixedLengthStreamingMode(meta.length);
        try (OutputStream out = start.getOutputStream()) { out.write(meta); }
        int startCode = start.getResponseCode();
        if (startCode < 200 || startCode >= 300) throw GeminiHttp.error("初始化音频上传", startCode, readBody(start), key, start.getHeaderField("Retry-After"));
        uploadUrl = start.getHeaderField("X-Goog-Upload-URL");
        } finally {
            start.disconnect();
        }
        if (uploadUrl == null || uploadUrl.isBlank()) throw new IllegalStateException("Gemini 没有返回上传地址");
        URI uploadUri = URI.create(uploadUrl);
        if (!"https".equalsIgnoreCase(uploadUri.getScheme())
                || !"generativelanguage.googleapis.com".equalsIgnoreCase(uploadUri.getHost())) {
            throw new IllegalStateException("Gemini 返回了非官方上传地址，已停止上传");
        }

        HttpURLConnection upload = (HttpURLConnection) uploadUri.toURL().openConnection();
        int code;
        String body;
        try {
        upload.setRequestMethod("POST");
        upload.setInstanceFollowRedirects(false);
        upload.setConnectTimeout(15_000);
        upload.setReadTimeout(120_000);
        upload.setDoOutput(true);
        upload.setRequestProperty("Content-Length", Long.toString(size));
        upload.setRequestProperty("Content-Type", mimeType);
        upload.setRequestProperty("X-Goog-Upload-Offset", "0");
        upload.setRequestProperty("X-Goog-Upload-Command", "upload, finalize");
        upload.setFixedLengthStreamingMode(size);
        long sent = 0L;
        try (FileInputStream in = new FileInputStream(file); OutputStream out = upload.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                sent += n;
                if (listener != null) listener.onUploadProgress((int) Math.min(100L, sent * 100L / Math.max(1L, size)), sent, size);
            }
            out.flush();
        }
        code = upload.getResponseCode();
        body = readBody(upload);
        } finally {
            upload.disconnect();
        }
        if (code < 200 || code >= 300) throw GeminiHttp.error("上传音频", code, body, key, null);
        JSONObject root = new JSONObject(body);
        JSONObject f = root.optJSONObject("file");
        if (f == null) f = root;
        String name = firstNonBlank(f.optString("name"), root.optString("name"));
        String uri = firstNonBlank(f.optString("uri"), root.optString("uri"));
        String mime = firstNonBlank(f.optString("mimeType"), f.optString("mime_type"), mimeType);
        String state = firstNonBlank(f.optString("state"), root.optString("state"));
        if (name.isBlank() || uri.isBlank()) throw new IllegalStateException("Gemini 文件上传完成，但缺少文件标识");
        return new UploadedFile(name, uri, mime, state);
    }

    private static void waitUntilActive(String key, UploadedFile file, Listener listener) throws Exception {
        String state = file.state;
        if ("ACTIVE".equalsIgnoreCase(state)) return;
        for (int i = 0; i < 45; i++) {
            if ("FAILED".equalsIgnoreCase(state)) throw new IllegalStateException("Gemini 处理上传音频失败");
            if (listener != null) listener.onStage("Gemini 正在处理上传音频…");
            Thread.sleep(1000L);
            JSONObject f = GeminiHttp.json(API + "/v1beta/" + file.name, "GET", key, null,
                    "处理上传音频", listener == null ? null : listener::onStage);
            state = f.optString("state", "");
            if ("ACTIVE".equalsIgnoreCase(state)) return;
        }
        throw new IllegalStateException("Gemini 处理音频超时，请稍后重试");
    }

    static JSONObject transcriptionRequest(String fileUri, String mimeType, TranscriptionOptions options) throws Exception {
        JSONObject tc = TranscriptionOptions.geminiConfig(options);
        JSONObject gc = new JSONObject();
        gc.put("transcription_config", tc);
        JSONObject audio = new JSONObject();
        audio.put("type", "audio");
        audio.put("uri", fileUri);
        audio.put("mime_type", mimeType.isBlank() ? "audio/mp4" : mimeType);
        JSONObject req = new JSONObject();
        req.put("model", MODEL);
        req.put("input", new JSONArray().put(audio));
        req.put("generation_config", gc);
        return req;
    }

    private static JSONObject transcribe(String key, String fileUri, String mimeType, Listener listener) throws Exception {
        return transcribe(key, fileUri, mimeType, TranscriptionOptions.defaults(), listener);
    }

    private static JSONObject transcribe(String key, String fileUri, String mimeType,
                                        TranscriptionOptions options, Listener listener) throws Exception {
        JSONObject req = transcriptionRequest(fileUri, mimeType, options);
        JSONObject result = GeminiHttp.json(API + "/v1beta/interactions", "POST", key, req,
                "Gemini 转写", listener == null ? null : listener::onStage);
        String status = result.optString("status", "");
        String id = result.optString("id", "");
        if ("completed".equalsIgnoreCase(status)) return result;
        if ((!"in_progress".equalsIgnoreCase(status) && !"created".equalsIgnoreCase(status)) || id.isBlank()) {
            throw new IllegalStateException("Gemini 转写未完成：" + status + "；"
                    + GeminiHttp.redact(result.optString("error", "缺少有效完成状态或任务 ID"), key));
        }

        for (int i = 0; i < 120; i++) {
            Thread.sleep(1000L);
            result = GeminiHttp.json(API + "/v1beta/interactions/" + interactionId(id), "GET", key, null,
                    "查询转写结果", listener == null ? null : listener::onStage);
            status = result.optString("status", "");
            if ("completed".equalsIgnoreCase(status)) return result;
            if (!status.isBlank() && !"in_progress".equalsIgnoreCase(status) && !"created".equalsIgnoreCase(status)) {
                throw new IllegalStateException("Gemini 转写结束状态：" + status);
            }
        }
        throw new IllegalStateException("Gemini 转写等待超时，请稍后重试");
    }

    static Parsed parseInteraction(JSONObject root, long durationMs) {
        String full = clean(root.optString("output_text", ""));
        List<Word> words = new ArrayList<>();
        String language = "";
        JSONArray steps = root.optJSONArray("steps");
        StringBuilder fallbackText = new StringBuilder();
        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                JSONArray content = step.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part == null) continue;
                    String partText = clean(part.optString("text", ""));
                    if (!partText.isBlank()) {
                        if (fallbackText.length() > 0) fallbackText.append('\n');
                        fallbackText.append(partText);
                    }
                    JSONArray annotations = part.optJSONArray("annotations");
                    if (annotations == null) continue;
                    for (int k = 0; k < annotations.length(); k++) {
                        JSONObject a = annotations.optJSONObject(k);
                        if (a == null || !"word_info".equals(a.optString("type"))) continue;
                        String text = a.optString("text", "");
                        long start = parseOffsetMs(a.optString("start_offset", ""));
                        long end = parseOffsetMs(a.optString("end_offset", ""));
                        String speaker = a.optString("speaker", "");
                        if (!text.isBlank()) words.add(new Word(text, start, end, speaker));
                    }
                }
            }
        }
        if (full.isBlank()) full = clean(fallbackText.toString());
        if (words.isEmpty() && !full.isBlank()) {
            throw new IllegalStateException("Gemini 未返回字级时间戳；未生成估算时间轴，请重试或手动切换 Groq");
        }
        SubtitleTimeline.words(words,durationMs);
        List<SubtitleSegment> segments = groupWords(words);
        SubtitleTimeline.segments(segments,durationMs);
        return new Parsed(full, language, segments, words,durationMs,"");
    }

    static List<SubtitleSegment> groupWords(List<Word> words) {
        return SubtitleSegmenter.group(words);
    }

    static String buildSrt(List<SubtitleSegment> segments) {
        SubtitleTimeline.segments(segments,Long.MAX_VALUE);
        StringBuilder srt = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            SubtitleSegment s = segments.get(i);
            srt.append(i + 1).append('\n')
                    .append(formatSrtTime(s.startCs)).append(" --> ").append(formatSrtTime(s.endCs)).append('\n')
                    .append(s.text).append("\n\n");
        }
        return srt.toString();
    }

    static void appendWord(StringBuilder out, String word) {
        String w = word == null ? "" : word.trim();
        if (w.isBlank()) return;
        if (out.length() > 0 && needsSpace(out.charAt(out.length() - 1), w.charAt(0))) out.append(' ');
        out.append(w);
    }

    private static boolean needsSpace(char a, char b) {
        if (isCjk(a) || isCjk(b)) return false;
        if (",.!?;:)]}%。，！？；：、".indexOf(b) >= 0) return false;
        if ("([{$".indexOf(a) >= 0) return false;
        return true;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    private static boolean endsSentence(String s) {
        String t = s == null ? "" : s.trim();
        return !t.isEmpty() && ".!?。！？".indexOf(t.charAt(t.length() - 1)) >= 0;
    }

    private static long parseOffsetMs(String raw) {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            String s = raw.trim();
            if (s.endsWith("s")) s = s.substring(0, s.length() - 1);
            double value = Double.parseDouble(s);
            if (!Double.isFinite(value) || value < 0 || value > 24 * 60 * 60) throw new IllegalArgumentException();
            return Math.round(value * 1000.0);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini 返回的字级时间戳无效");
        }
    }

    private static long audioDurationMs(File file) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(file.getAbsolutePath());
            String value = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private static HttpURLConnection open(String url, String method, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(180_000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("x-goog-api-key", key);
        c.setRequestProperty("User-Agent", "MediaParser/0.1.11 Android");
        return c;
    }

    private static String readBody(HttpURLConnection c) throws java.io.IOException {
        return GeminiHttp.readBody(c, c.getResponseCode());
    }

    private static String interactionId(String raw) {
        if (raw == null) return "";
        String id = raw.trim();
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static void deleteRemoteFile(String key, String name) {
        try {
            HttpURLConnection c = open(API + "/v1beta/" + name, "DELETE", key);
            c.setReadTimeout(15_000);
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {}
    }

    private static String suffix(String message) { return message == null || message.isBlank() ? "" : "：" + message; }
    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\u0000', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\\n\\n").trim();
    }
    private static String formatSrtTime(long centiseconds) {
        long ms = Math.max(0L, centiseconds) * 10L;
        long hours = ms / 3_600_000L;
        ms %= 3_600_000L;
        long minutes = ms / 60_000L;
        ms %= 60_000L;
        long seconds = ms / 1_000L;
        long millis = ms % 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    static final class Parsed {
        final long durationMs;
        final String timingWarning;
        final List<Word> words;
        final String fullText;
        final String language;
        final List<SubtitleSegment> segments;
        Parsed(String fullText, String language, List<SubtitleSegment> segments) {
            this(fullText, language, segments, java.util.Collections.emptyList());
        }
        Parsed(String fullText, String language, List<SubtitleSegment> segments, List<Word> words) {
            this(fullText,language,segments,words,0,"");
        }
        Parsed(String fullText,String language,List<SubtitleSegment> segments,List<Word> words,long durationMs,String warning) {
            this.durationMs=durationMs; this.timingWarning=warning;
            this.words = new ArrayList<>(words);
            this.fullText = fullText;
            this.language = language;
            this.segments = segments;
        }
    }

    static final class Word {
        final String text;
        final long startMs;
        final long endMs;
        final String speaker;
        final double confidence;
        Word(String text, long startMs, long endMs, String speaker) {
            this(text,startMs,endMs,speaker,-1d);
        }
        Word(String text, long startMs, long endMs, String speaker,double confidence) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
            this.speaker = speaker == null ? "" : speaker;
            this.confidence=confidence;
        }
    }

    private static final class UploadedFile {
        final String name;
        final String uri;
        final String mimeType;
        final String state;
        UploadedFile(String name, String uri, String mimeType, String state) {
            this.name = name;
            this.uri = uri;
            this.mimeType = mimeType;
            this.state = state;
        }
    }
}

