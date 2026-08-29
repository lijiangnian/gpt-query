package com.example.mediaparser.subtitle;

import android.content.Context;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fixed-memory local ASR: MediaCodec -> PCM -> neural VAD -> sherpa-onnx token timing. */
public final class LocalTranscriber {
    public interface Progress { void onProgress(String stage,int done,int total); }
    public static final class Segment { public final long startMs,endMs;public final String text;Segment(long s,long e,String t){startMs=s;endMs=e;text=t;} }
    public static final class Word { public final long startMs,endMs;public final String text;Word(long s,long e,String t){startMs=s;endMs=e;text=t;} }
    public static final class Result {
        public final List<Segment> segments;public final List<Word> words;public final String text,language,timingNote;public final long durationMs;
        Result(List<Segment>s,List<Word>w,String t,String l,long d,String note){segments=Collections.unmodifiableList(s);words=Collections.unmodifiableList(w);text=t;language=l;durationMs=d;timingNote=note;}
    }
    private static final int SR=16000,RMS_FRAME=320,VAD_FRAME=512,MAX_RECOGNITION_SAMPLES=15*SR,MAX_SUBTITLE_CHARS=22;
    private LocalTranscriber(){}

    public static Result run(Context context,File media,LocalModelManager.ModelSpec spec,String language,Progress progress)throws Exception{
        if(!LocalModelManager.isInstalled(context,spec))throw new IllegalStateException("本地模型尚未安装："+spec.name);
        File vadModel=null;
        try{
            vadModel=LocalModelManager.ensureVad(context,(stage,done,total)->{if(progress!=null)progress.onProgress(stage,(int)Math.min(Integer.MAX_VALUE,done),(int)Math.min(Integer.MAX_VALUE,total));});
        }catch(Exception e){if(progress!=null)progress.onProgress("分句模型不可用，使用兼容分句",0,0);}
        LocalAudioDecoder.PcmFile pcm=LocalAudioDecoder.decode(media,context.getCacheDir(),p->{if(progress!=null)progress.onProgress("解码音频 "+p+"%",p,100);});
        OfflineRecognizer recognizer=null;
        try{
            boolean neuralVad=vadModel!=null,coverageFallback=false;List<Range> ranges;
            try{ranges=neuralVad?neuralVad(pcm.file,pcm.samples,vadModel):vad(pcm.file,pcm.samples);if(neuralVad){List<Range> energy=vad(pcm.file,pcm.samples);double neuralCoverage=coverage(ranges,pcm.samples),energyCoverage=coverage(energy,pcm.samples);if(energyCoverage>=0.65&&energyCoverage-neuralCoverage>=0.20){ranges=energy;coverageFallback=true;}}}catch(Exception e){neuralVad=false;ranges=vad(pcm.file,pcm.samples);}
            if(ranges.isEmpty())ranges=Collections.singletonList(new Range(0,pcm.samples));
            ranges=splitLongRangesAtQuietPoints(pcm.file,ranges);
            recognizer=create(context,spec,language);List<Segment> segments=new ArrayList<>();List<Word> words=new ArrayList<>();StringBuilder full=new StringBuilder();String detected="";int done=0,total=countChunks(ranges);boolean usedTokenTiming=false,usedEstimatedTiming=false;
            try(RandomAccessFile in=new RandomAccessFile(pcm.file,"r")){
                for(Range r:ranges){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException("本地识别已取消");
                    long pos=r.start;while(pos<r.end){long end=Math.min(r.end,pos+MAX_RECOGNITION_SAMPLES);float[] samples=read(in,pos,(int)(end-pos));
                        OfflineStream stream=recognizer.createStream();try{stream.acceptWaveform(samples,SR);recognizer.decode(stream);OfflineRecognizerResult rr=recognizer.getResult(stream);String text=clean(rr.getText());
                            if(detected.isBlank()&&rr.getLang()!=null)detected=rr.getLang();if(!text.isBlank()){
                                Timing timing=appendTimedSegments(segments,words,pos*1000/SR,end*1000/SR,text,rr.getTokens(),rr.getTimestamps(),rr.getDurations());
                                usedTokenTiming|=timing.token;usedEstimatedTiming|=!timing.token;if(full.length()>0)full.append('\n');full.append(text);
                            }
                        }finally{stream.release();}pos=end;done++;if(progress!=null)progress.onProgress("本地AI识别",done,total);
                    }
                }
            }
            if(full.length()==0)throw new IllegalStateException("本地模型没有识别到可用语音");
            String note=(neuralVad?"Silero VAD 语音分句":"兼容能量分句")+(coverageFallback?"；检测到持续配乐，已切换覆盖优先分段":"")+(usedTokenTiming?" + 模型 token 时间戳（已保存字/词级对齐）":"")+(usedEstimatedTiming?"；部分短句在真实语音片段内按文字比例细分":"")+"；硬字幕仍需复核";
            return new Result(segments,words,full.toString(),detected,pcm.durationMs(),note);
        } finally {if(recognizer!=null)recognizer.release();pcm.file.delete();}
    }

    private static OfflineRecognizer create(Context c,LocalModelManager.ModelSpec spec,String language){
        File dir=LocalModelManager.dir(c,spec);File model=LocalModelManager.findFile(dir,"model.int8.onnx");File tokens=LocalModelManager.findFile(dir,"tokens.txt");
        if(model==null||tokens==null)throw new IllegalStateException("模型文件不完整，请删除后重新下载");OfflineModelConfig mc=new OfflineModelConfig();
        if(spec.engine== LocalModelManager.Engine.PARAFORMER){OfflineParaformerModelConfig p=new OfflineParaformerModelConfig();p.setModel(model.getAbsolutePath());mc.setParaformer(p);}
        else{OfflineSenseVoiceModelConfig s=new OfflineSenseVoiceModelConfig();s.setModel(model.getAbsolutePath());s.setLanguage("zh".equals(language)?"zh":"auto");s.setUseInverseTextNormalization(true);mc.setSenseVoice(s);}
        mc.setTokens(tokens.getAbsolutePath());mc.setNumThreads(Math.max(2,Math.min(6,Runtime.getRuntime().availableProcessors()-1)));mc.setDebug(false);mc.setProvider("cpu");
        FeatureConfig fc=new FeatureConfig();fc.setSampleRate(SR);fc.setFeatureDim(80);fc.setDither(0f);OfflineRecognizerConfig rc=new OfflineRecognizerConfig();rc.setFeatConfig(fc);rc.setModelConfig(mc);rc.setDecodingMethod("greedy_search");return new OfflineRecognizer(null,rc);
    }

    private static List<Range> neuralVad(File file,long sampleCount,File model)throws Exception{
        SileroVadModelConfig silero=new SileroVadModelConfig();silero.setModel(model.getAbsolutePath());silero.setThreshold(0.25f);silero.setMinSilenceDuration(0.35f);silero.setMinSpeechDuration(0.25f);silero.setWindowSize(VAD_FRAME);silero.setMaxSpeechDuration(12f);
        VadModelConfig config=new VadModelConfig();config.setSileroVadModelConfig(silero);config.setSampleRate(SR);config.setNumThreads(1);config.setProvider("cpu");config.setDebug(false);
        Vad vad=new Vad(null,config);ArrayList<Range> out=new ArrayList<>();
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){byte[] b=new byte[VAD_FRAME*2];long readSamples=0;while(readSamples<sampleCount){int wanted=(int)Math.min(VAD_FRAME,sampleCount-readSamples);int n=in.read(b,0,wanted*2);if(n<=0)break;float[] x=new float[VAD_FRAME];int count=n/2;for(int i=0;i<count;i++){short v=(short)((b[i*2]&255)|(b[i*2+1]<<8));x[i]=v/32768f;}vad.acceptWaveform(x);drain(vad,out,sampleCount);readSamples+=count;}vad.flush();drain(vad,out,sampleCount);}finally{vad.release();}
        return out;
    }
    private static void drain(Vad vad,List<Range> out,long sampleCount){while(!vad.empty()){SpeechSegment s=vad.front();long start=Math.max(0,s.getStart()),end=Math.min(sampleCount,start+s.getSamples().length);if(end-start>=SR/5)out.add(new Range(start,end));vad.pop();}}

    /** Compatibility fallback retained for devices where the neural VAD cannot initialize. */
    private static List<Range> vad(File file,long sampleCount)throws Exception{
        ArrayList<Float> rms=new ArrayList<>();try(RandomAccessFile in=new RandomAccessFile(file,"r")){byte[] b=new byte[RMS_FRAME*2];long frames=(sampleCount+RMS_FRAME-1)/RMS_FRAME;for(long f=0;f<frames;f++){int n=in.read(b);if(n<=0)break;double sum=0;int count=n/2;for(int i=0;i<count;i++){short v=(short)((b[i*2]&255)|(b[i*2+1]<<8));double x=v/32768.0;sum+=x*x;}rms.add((float)Math.sqrt(sum/Math.max(1,count)));}}
        if(rms.isEmpty())return Collections.emptyList();ArrayList<Float> sorted=new ArrayList<>(rms);Collections.sort(sorted);float floor=sorted.get(Math.min(sorted.size()-1,sorted.size()/5));float threshold=Math.max(0.006f,Math.min(0.025f,floor*3.0f));
        ArrayList<Range> out=new ArrayList<>();int start=-1,lastActive=-1;for(int i=0;i<rms.size();i++){boolean active=rms.get(i)>=threshold;if(active){if(start<0)start=Math.max(0,i-8);lastActive=i;}if(start>=0&&i-lastActive>20){addRange(out,(long)start*RMS_FRAME,Math.min(sampleCount,(long)(lastActive+10)*RMS_FRAME));start=-1;}}
        if(start>=0)addRange(out,(long)start*RMS_FRAME,Math.min(sampleCount,(long)(lastActive+10)*RMS_FRAME));return out;
    }
    private static void addRange(List<Range> out,long start,long end){if(end-start<SR/3)return;if(!out.isEmpty()&&start-out.get(out.size()-1).end<SR/2)out.get(out.size()-1).end=end;else out.add(new Range(start,end));}
    private static List<Range> splitLongRangesAtQuietPoints(File file,List<Range> input)throws Exception{ArrayList<Range> out=new ArrayList<>();try(RandomAccessFile in=new RandomAccessFile(file,"r")){for(Range r:input){long pos=r.start;while(r.end-pos>MAX_RECOGNITION_SAMPLES){long earliest=pos+9L*SR,latest=Math.min(pos+MAX_RECOGNITION_SAMPLES,r.end-3L*SR);long cut=latest>earliest?quietestCut(in,earliest,latest,pos+12L*SR):pos+MAX_RECOGNITION_SAMPLES;if(cut<=pos+SR||cut>=r.end)cut=Math.min(r.end,pos+MAX_RECOGNITION_SAMPLES);out.add(new Range(pos,cut));pos=cut;}if(r.end-pos>=SR/5)out.add(new Range(pos,r.end));}}return out;}
    private static long quietestCut(RandomAccessFile in,long earliest,long latest,long target)throws Exception{int window=SR/12,step=SR/25;byte[] b=new byte[window*2];double best=Double.MAX_VALUE;long bestCut=Math.min(latest,Math.max(earliest,target));for(long p=earliest;p<=latest;p+=step){in.seek(p*2);int n=in.read(b);if(n<2)break;double sum=0;int count=n/2;for(int i=0;i<count;i++){short v=(short)((b[i*2]&255)|(b[i*2+1]<<8));double x=v/32768d;sum+=x*x;}double rms=Math.sqrt(sum/Math.max(1,count));long center=p+count/2;double score=rms+Math.abs(center-target)/(double)SR*0.0005;if(score<best){best=score;bestCut=center;}}return bestCut;}

    static List<String> splitSubtitleText(String text){
        ArrayList<String> out=new ArrayList<>();String clean=clean(text);int start=0;
        for(int i=0;i<clean.length();i++){char c=clean.charAt(i);if(isHardBreak(c)){addLong(out,clean.substring(start,i+1));start=i+1;}}
        if(start<clean.length())addLong(out,clean.substring(start));return out;
    }
    private static void addLong(List<String> out,String value){String s=value.trim();while(s.length()>MAX_SUBTITLE_CHARS){int cut=-1;for(int i=Math.min(MAX_SUBTITLE_CHARS,s.length()-1);i>=8;i--){char c=s.charAt(i-1);if(c=='，'||c==','||c=='、'||c=='：'||c==':'||Character.isWhitespace(c)){cut=i;break;}}if(cut<0)cut=MAX_SUBTITLE_CHARS;String p=s.substring(0,cut).trim();if(!p.isEmpty())out.add(p);s=s.substring(cut).trim();}if(!s.isEmpty())out.add(s);}
    private static boolean isHardBreak(char c){return c=='。'||c=='！'||c=='？'||c=='!'||c=='?'||c=='；'||c==';'||c=='\n';}

    static Timing appendTimedSegments(List<Segment> out,long chunkStartMs,long chunkEndMs,String text,float[] timestamps,float[] durations){return appendTimedSegments(out,new ArrayList<>(),chunkStartMs,chunkEndMs,text,null,timestamps,durations);}
    static Timing appendTimedSegments(List<Segment> out,List<Word> words,long chunkStartMs,long chunkEndMs,String text,String[] tokens,float[] timestamps,float[] durations){
        if(validTokenAlignment(text,tokens,timestamps,chunkEndMs-chunkStartMs)){
            ArrayList<TimedToken> timed=new ArrayList<>();for(int i=0;i<tokens.length;i++){String piece=tokenPiece(tokens[i]);if(piece.isBlank())continue;long start=Math.max(chunkStartMs,chunkStartMs+Math.round(timestamps[i]*1000f));long next=i+1<timestamps.length?chunkStartMs+Math.round(timestamps[i+1]*1000f):chunkEndMs;long end;
                if(durations!=null&&durations.length==timestamps.length&&durations[i]>0)end=chunkStartMs+Math.round((timestamps[i]+durations[i])*1000f);else end=Math.min(next,start+700);end=Math.min(chunkEndMs,Math.max(start+10,end));TimedToken t=new TimedToken(start,end,piece);timed.add(t);words.add(new Word(start,end,clean(piece)));}
            appendTokenGroups(out,timed);return new Timing(true);
        }
        List<String> phrases=splitSubtitleText(text);if(phrases.isEmpty())return new Timing(false);long[] boundaries=new long[phrases.size()+1];boundaries[0]=chunkStartMs;boundaries[phrases.size()]=chunkEndMs;int total=0;for(String p:phrases)total+=units(p);int consumed=0;
        for(int i=1;i<phrases.size();i++){consumed+=units(phrases.get(i-1));boundaries[i]=chunkStartMs+Math.round((chunkEndMs-chunkStartMs)*(double)consumed/Math.max(1,total));}
        for(int i=0;i<phrases.size();i++){long start=boundaries[i],end=boundaries[i+1];if(end>start)out.add(new Segment(start,end,phrases.get(i)));}return new Timing(false);
    }
    private static void appendTokenGroups(List<Segment> out,List<TimedToken> tokens){StringBuilder text=new StringBuilder();long start=-1,end=-1,previousStart=-1;int count=0;for(TimedToken t:tokens){int pieceUnits=units(t.text);boolean silence=previousStart>=0&&t.startMs-previousStart>=800;boolean tooLong=start>=0&&t.startMs-start>=5500;boolean tooMany=count>0&&count+pieceUnits>MAX_SUBTITLE_CHARS;if((silence||tooLong||tooMany)&&text.length()>0){addTokenGroup(out,start,end,text);text.setLength(0);count=0;start=-1;}if(start<0)start=t.startMs;text.append(t.text);count+=pieceUnits;end=t.endMs;previousStart=t.startMs;char last=t.text.charAt(t.text.length()-1);if(isHardBreak(last)||(isSoftBreak(last)&&count>=10)){addTokenGroup(out,start,end,text);text.setLength(0);count=0;start=-1;}}
        if(text.length()>0)addTokenGroup(out,start,end,text);
    }
    private static void addTokenGroup(List<Segment> out,long start,long end,StringBuilder value){String text=clean(value.toString());if(!text.isBlank()&&end>start)out.add(new Segment(start,end,text));}
    private static boolean validTokenAlignment(String text,String[] tokens,float[] timestamps,long chunkMs){if(tokens==null||timestamps==null||tokens.length!=timestamps.length||tokens.length<1||!validTimestamps(timestamps,chunkMs))return false;StringBuilder joined=new StringBuilder();for(String t:tokens)joined.append(tokenPiece(t));return normalizeAlignment(text).equals(normalizeAlignment(joined.toString()));}
    private static String tokenPiece(String token){if(token==null||token.matches("<\\|[^>]+\\|>"))return "";return token.replace('▁',' ');}
    private static String normalizeAlignment(String s){return clean(s).replaceAll("[\\s\\p{Punct}，。！？；：、‘’“”《》【】（）…—]+","").toLowerCase(java.util.Locale.ROOT);}
    private static boolean isSoftBreak(char c){return c=='，'||c==','||c=='、'||c=='：'||c==':';}
    private static boolean validTimestamps(float[] ts,long chunkMs){if(ts==null||ts.length<1)return false;float last=-1;boolean advanced=ts.length==1;for(float t:ts){if(Float.isNaN(t)||Float.isInfinite(t)||t<0||t*1000>chunkMs+1500||t+0.001f<last)return false;if(t>last+0.01f)advanced=true;last=t;}return advanced;}
    private static int units(String s){int n=0;for(int i=0;i<s.length();i++)if(!Character.isWhitespace(s.charAt(i)))n++;return Math.max(1,n);}
    private static double coverage(List<Range> ranges,long samples){if(samples<=0)return 0;long total=0;for(Range r:ranges)total+=Math.max(0,r.end-r.start);return Math.min(1d,total/(double)samples);}
    private static int countChunks(List<Range> rs){long n=0;for(Range r:rs)n+=(r.end-r.start+MAX_RECOGNITION_SAMPLES-1)/MAX_RECOGNITION_SAMPLES;return (int)Math.max(1,n);}
    private static float[] read(RandomAccessFile in,long start,int count)throws Exception{in.seek(start*2);byte[] b=new byte[count*2];in.readFully(b);float[] x=new float[count];for(int i=0;i<count;i++){short v=(short)((b[i*2]&255)|(b[i*2+1]<<8));x[i]=v/32768f;}return x;}
    private static String clean(String s){if(s==null)return "";return s.replaceAll("<\\|[^>]+\\|>","").replaceAll("\\s+"," ").trim();}
    static final class Timing{final boolean token;Timing(boolean t){token=t;}}
    private static final class TimedToken{final long startMs,endMs;final String text;TimedToken(long s,long e,String t){startMs=s;endMs=e;text=t;}}
    private static final class Range{final long start;long end;Range(long s,long e){start=s;end=e;}}
}
