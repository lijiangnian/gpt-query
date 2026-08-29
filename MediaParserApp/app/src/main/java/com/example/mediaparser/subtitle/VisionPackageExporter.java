package com.example.mediaparser.subtitle;

import android.content.*;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.util.FileSaver;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Local-only hard-subtitle pack. Nothing is uploaded and no credential is exported. */
public final class VisionPackageExporter {
    public interface Progress{void stage(String text);}
    public static final class Result{public final int frames;public final List<String> locations;public final boolean anchorGuided;Result(int f,List<String>l,boolean a){frames=f;locations=l;anchorGuided=a;}}
    private static final int BATCH=100,MAX_FRAMES=3600;
    private VisionPackageExporter(){}

    public static Result export(Context context,MediaItem source,String title,SubtitleOutput guide,Progress progress)throws Exception{
        if(source==null||source.url.isBlank())throw new IllegalArgumentException("没有可截图的视频来源");if(source.type==MediaItem.Type.AUDIO&&!source.extractsAudioTrack())throw new IllegalArgumentException("这个来源只有音频，无法制作画面字幕包");
        if(progress!=null)progress.stage("下载视频用于本机取帧…");File video=FileSaver.downloadToCache(context,source,".mp4");MediaMetadataRetriever retriever=new MediaMetadataRetriever();List<String> saved=new ArrayList<>();int total=0;boolean guided=guide!=null&&guide.hasTiming();
        try{retriever.setDataSource(video.getAbsolutePath());long duration=parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));if(duration<=0)throw new IOException("无法读取视频时长");List<Candidate> candidates=guided?guidedTimes(guide,duration):fixedTimes(duration);ArrayList<Frame> batch=new ArrayList<>();Map<Integer,Boolean> anchorInk=new HashMap<>();long lastHash=0;boolean hasHash=false;int visited=0;
            for(Candidate candidate:candidates){if(Thread.currentThread().isInterrupted())throw new InterruptedException("截图包制作已取消");if(candidate.expanded&&Boolean.TRUE.equals(anchorInk.get(candidate.segmentId)))continue;Bitmap raw=retriever.getFrameAtTime(candidate.ms*1000,MediaMetadataRetriever.OPTION_CLOSEST);if(raw==null)continue;Bitmap crop=null,scaled=null;try{int y=Math.max(0,(int)(raw.getHeight()*0.65));crop=Bitmap.createBitmap(raw,0,y,raw.getWidth(),raw.getHeight()-y);int w=Math.min(960,crop.getWidth()),h=Math.max(1,crop.getHeight()*w/crop.getWidth());scaled=Bitmap.createScaledBitmap(crop,w,h,true);boolean likely=hasSubtitleInk(scaled);if(!candidate.expanded&&likely)anchorInk.put(candidate.segmentId,true);long hash=dHash(scaled);if(hasHash&&Long.bitCount(hash^lastHash)<=2)continue;lastHash=hash;hasHash=true;ByteArrayOutputStream jpg=new ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,68,jpg);batch.add(new Frame(candidate.ms,candidate.segmentId,candidate.expanded?"expanded_250ms":"anchor",likely,jpg.toByteArray()));total++;visited++;if(progress!=null&&visited%10==0)progress.stage("提取并去重字幕画面 "+visited+" / "+candidates.size());if(batch.size()>=BATCH){saved.add(saveZip(context,title,saved.size()+1,batch,guide,guided,duration));batch.clear();}}
                finally{if(scaled!=null&&scaled!=crop)scaled.recycle();if(crop!=null&&crop!=raw)crop.recycle();raw.recycle();}if(total>=MAX_FRAMES)break;}
            if(!batch.isEmpty())saved.add(saveZip(context,title,saved.size()+1,batch,guide,guided,duration));if(saved.isEmpty())throw new IOException("未能截取到可用画面");if(progress!=null)progress.stage("已生成 "+saved.size()+" 个ZIP，共 "+total+" 张去重截图");return new Result(total,saved,guided);
        }finally{retriever.release();video.delete();}
    }

    /** Three anchors per ASR segment; expanded ±2 s/250 ms candidates are consumed only when anchors look blank. */
    private static List<Candidate> guidedTimes(SubtitleOutput o,long duration){ArrayList<Candidate> list=new ArrayList<>();int id=0;for(SubtitleSegment s:o.segments){id++;long a=s.startCs*10,b=s.endCs*10;if(b<=a)continue;LinkedHashSet<Long> anchors=new LinkedHashSet<>(Arrays.asList(clamp(a+150,duration),clamp((a+b)/2,duration),clamp(b-150,duration)));for(long t:anchors)list.add(new Candidate(t,id,false));for(long t=Math.max(0,a-2000);t<=Math.min(duration-1,b+2000);t+=250)if(!anchors.contains(t))list.add(new Candidate(t,id,true));if(list.size()>=MAX_FRAMES*5)break;}return list;}
    private static List<Candidate> fixedTimes(long duration){ArrayList<Candidate> list=new ArrayList<>();for(long t=250;t<duration&&list.size()<MAX_FRAMES;t+=500)list.add(new Candidate(t,0,false));return list;}
    private static boolean hasSubtitleInk(Bitmap b){int hits=0,samples=0;for(int y=2;y<b.getHeight()-2;y+=2)for(int x=2;x<b.getWidth()-2;x+=2){int g=gray(b.getPixel(x,y));int contrast=Math.max(Math.abs(g-gray(b.getPixel(x+2,y))),Math.abs(g-gray(b.getPixel(x,y+2))));if((g>175&&contrast>55)||(g<80&&contrast>70))hits++;samples++;}return samples>0&&hits/(double)samples>0.012;}
    private static long clamp(long v,long duration){return Math.max(0,Math.min(Math.max(0,duration-1),v));}
    private static long dHash(Bitmap b){Bitmap x=Bitmap.createScaledBitmap(b,9,8,true);long h=0;for(int y=0;y<8;y++)for(int col=0;col<8;col++){int a=gray(x.getPixel(col,y)),c=gray(x.getPixel(col+1,y));h=(h<<1)|(a>c?1:0);}x.recycle();return h;}
    private static int gray(int c){return (((c>>16)&255)*30+((c>>8)&255)*59+(c&255)*11)/100;}

    private static String saveZip(Context c,String title,int part,List<Frame> frames,SubtitleOutput guide,boolean guided,long duration)throws Exception{
        String safe=(title==null?"media":title).replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+","_");if(safe.length()>36)safe=safe.substring(0,36);String name=safe+"_硬字幕网页校对包_P"+String.format(Locale.ROOT,"%02d",part)+"_"+System.currentTimeMillis()+".zip";ContentResolver resolver=c.getContentResolver();ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,"application/zip");values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);values.put(MediaStore.MediaColumns.IS_PENDING,1);Uri uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("系统无法创建ZIP");
        try(OutputStream raw=resolver.openOutputStream(uri);ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(raw))){JSONObject manifest=new JSONObject().put("schema","mediaparser-hard-subtitle-pack-v2").put("part",part).put("duration_ms",duration).put("actual_asr_engine",guide==null?"":guide.actualEngine).put("anchor_guided",guided).put("crop","bottom_35_percent").put("expanded_search","when anchor has no likely subtitle: ASR range +/-2000ms every 250ms").put("note","图片时间是定位证据；清晰画面原字幕优先于 ASR 初稿");JSONArray arr=new JSONArray();for(Frame f:frames){JSONObject x=new JSONObject().put("file",fileName(f)).put("time_ms",f.ms).put("asr_segment_id",f.segmentId).put("search_type",f.kind).put("likely_subtitle_ink",f.likelyInk);if(guide!=null&&f.segmentId>0&&f.segmentId<=guide.segments.size()){SubtitleSegment s=guide.segments.get(f.segmentId-1);x.put("asr_start_ms",s.startCs*10).put("asr_end_ms",s.endCs*10).put("asr_text",s.text);}arr.put(x);}manifest.put("frames",arr);entry(zip,"manifest.json",manifest.toString(2).getBytes(StandardCharsets.UTF_8));entry(zip,"PROMPT.txt",prompt().getBytes(StandardCharsets.UTF_8));if(guide!=null){entry(zip,"asr_original.txt",guide.fullText.getBytes(StandardCharsets.UTF_8));if(!guide.srt.isBlank())entry(zip,"asr_original.srt",guide.srt.getBytes(StandardCharsets.UTF_8));entry(zip,"asr_alignment.json",alignment(guide).toString(2).getBytes(StandardCharsets.UTF_8));}for(Frame f:frames)entry(zip,"frames/"+fileName(f),f.jpg);}
        catch(Exception e){resolver.delete(uri,null,null);throw e;}ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);resolver.update(uri,done,null,null);return uri.toString();
    }
    static JSONObject alignment(SubtitleOutput guide)throws Exception{JSONObject root=new JSONObject().put("schema","mediaparser-asr-alignment-v1").put("actual_engine",guide.actualEngine).put("language",guide.detectedLanguage).put("duration_ms",guide.durationMs);JSONArray segs=new JSONArray();for(int i=0;i<guide.segments.size();i++){SubtitleSegment s=guide.segments.get(i);segs.put(new JSONObject().put("segment_id",i+1).put("start_ms",s.startCs*10).put("end_ms",s.endCs*10).put("text",s.text));}JSONArray words=new JSONArray();for(SubtitleExtractor.Word w:guide.timedWords){JSONObject x=new JSONObject().put("start_ms",w.startMs).put("end_ms",w.endMs).put("text",w.text).put("speaker",w.speaker);if(w.confidence>=0)x.put("confidence",w.confidence);words.put(x);}return root.put("segments",segs).put("words",words);}
    private static String prompt(){return "这是 MediaParser 导出的网页人工校对包。请按 manifest.json 的 asr_segment_id 与 time_ms 查看 frames。ASR 文字只是初稿；只要画面中的硬字幕清晰可辨，必须以画面原字幕为准，不要用 ASR 错字覆盖它。合并连续重复截图，保留原意、人名、数字、英文和标点。时间轴优先沿用 asr_original.srt；expanded_250ms 图片用于寻找遗漏字幕和估计边界。输出：corrected.txt、合法 corrected.srt、不确定项清单。不要凭常识补写画面和声音都无法确认的内容。";}
    private static String fileName(Frame f){return String.format(Locale.ROOT,"seg%05d_%012dms_%s.jpg",f.segmentId,f.ms,f.kind);}
    private static void entry(ZipOutputStream z,String n,byte[] b)throws Exception{z.putNextEntry(new ZipEntry(n));z.write(b);z.closeEntry();}
    private static long parseLong(String s){try{return Long.parseLong(s);}catch(Exception e){return 0;}}
    private static final class Candidate{final long ms;final int segmentId;final boolean expanded;Candidate(long m,int id,boolean e){ms=m;segmentId=id;expanded=e;}}
    private static final class Frame{final long ms;final int segmentId;final String kind;final boolean likelyInk;final byte[] jpg;Frame(long m,int id,String k,boolean likely,byte[]j){ms=m;segmentId=id;kind=k;likelyInk=likely;jpg=j;}}
}
