package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Honest metrics: CER/leak/timestamp error are reported only when a reference transcript exists. */
public final class AsrBenchmark {
    private AsrBenchmark(){}

    public static Score score(String requested,SubtitleOutput output,long elapsedMs,String reference){
        AsrDocument doc=output.document();Reference ref=Reference.parse(reference);EditDistance d=ref.text.isBlank()?null:distance(chars(ref.text),chars(doc.plainText()));
        double covered=0;long last=-1;for(AsrDocument.Segment s:doc.segments){long a=Math.max(s.startMs,last),b=Math.max(a,s.endMs);covered+=Math.max(0,b-a);last=Math.max(last,b);}double coverage=doc.durationMs<=0?0:Math.min(1,covered/doc.durationMs);
        double ts=ref.segments.isEmpty()?-1:timestampError(ref.segments,doc.segments);
        return new Score(requested,doc.engine,elapsedMs,doc.segments.size(),coverage,d==null?-1:d.total/(double)Math.max(1,d.reference),d==null?-1:d.deletions/(double)Math.max(1,d.reference),ts,"");
    }

    public static Score failure(String requested,long elapsedMs,Throwable error){return new Score(requested,"",elapsedMs,0,0,-1,-1,-1,safe(error));}

    public static String report(List<Score> scores,boolean hasReference){
        StringBuilder b=new StringBuilder("MediaParser ASR 横评\n");b.append(hasReference?"综合分：文字40% + 时间戳30% + 漏句15% + 速度10% + 稳定性5%。\n":"没有参考稿：不伪造 CER、漏字率、时间戳误差或综合分；请粘贴硬字幕/OCR/SRT 后重跑。\n");
        for(Score s:scores){b.append("\n").append(s.requested).append(" → ").append(s.error.isBlank()?s.actual:s.error).append('\n');if(!s.error.isBlank())continue;b.append("端到端耗时：").append(time(s.elapsedMs)).append("；分段：").append(s.segments).append("；时间轴覆盖：").append(pct(s.coverage));if(s.cer>=0)b.append("；文字准确率：").append(pct(Math.max(0,1-s.cer))).append("；CER：").append(pct(s.cer)).append("；漏字率：").append(pct(s.deletionRate));if(s.timestampErrorMs>=0)b.append("；平均时间戳误差：").append(Math.round(s.timestampErrorMs)).append("ms");else if(hasReference)b.append("；参考稿无时间轴");b.append('\n');}
        if(hasReference){ArrayList<Score> ranked=new ArrayList<>();for(Score s:scores)if(s.error.isBlank()&&s.cer>=0)ranked.add(s);b.append("\n文字准确率排名\n");ranked.sort((a,c)->Double.compare(a.cer,c.cer));int i=1;for(Score s:ranked)b.append(i++).append(". ").append(s.requested).append("：").append(pct(Math.max(0,1-s.cer))).append("（CER ").append(pct(s.cer)).append("）\n");b.append("\n综合排名\n");ranked.sort((a,c)->Double.compare(weighted(c,scores),weighted(a,scores)));i=1;for(Score s:ranked)b.append(i++).append(". ").append(s.requested).append("：").append(String.format(Locale.ROOT,"%.1f",weighted(s,scores))).append(" 分\n");}
        return b.toString();
    }

    public static JSONObject json(List<Score> scores,boolean hasReference)throws Exception{JSONObject root=new JSONObject().put("schema","mediaparser-asr-benchmark-v3").put("reference_provided",hasReference).put("weights",new JSONObject().put("text_accuracy",.40).put("timestamp_accuracy",.30).put("deletion_accuracy",.15).put("speed",.10).put("stability",.05)).put("missing_metrics_are_renormalized",true);JSONArray a=new JSONArray();for(Score s:scores){JSONObject x=s.json();if(hasReference&&s.error.isBlank()&&s.cer>=0)x.put("text_accuracy",Math.max(0,1-s.cer)).put("weighted_score",weighted(s,scores));a.put(x);}return root.put("results",a);}

    /** 0-100. It is intentionally unavailable without a real reference transcript. */
    public static double weighted(Score s,List<Score> all){if(s==null||!s.error.isBlank()||s.cer<0)return-1;double total=.40*clamp(1-s.cer)+.15*clamp(1-s.deletionRate)+.05*clamp(s.coverage),weights=.60;long fastest=Long.MAX_VALUE;for(Score x:all)if(x.error.isBlank()&&x.elapsedMs>0)fastest=Math.min(fastest,x.elapsedMs);if(fastest<Long.MAX_VALUE&&s.elapsedMs>0){total+=.10*clamp(fastest/(double)s.elapsedMs);weights+=.10;}if(s.timestampErrorMs>=0){total+=.30*clamp(1-s.timestampErrorMs/2000d);weights+=.30;}return weights<=0?-1:100*total/weights;}

    public static final class Score{
        public final String requested,actual,error;public final long elapsedMs;public final int segments;public final double coverage,cer,deletionRate,timestampErrorMs;
        Score(String requested,String actual,long elapsedMs,int segments,double coverage,double cer,double deletionRate,double timestampErrorMs,String error){this.requested=requested;this.actual=actual;this.elapsedMs=elapsedMs;this.segments=segments;this.coverage=coverage;this.cer=cer;this.deletionRate=deletionRate;this.timestampErrorMs=timestampErrorMs;this.error=error;}
        JSONObject json()throws Exception{JSONObject x=new JSONObject().put("requested_engine",requested).put("actual_engine",actual).put("elapsed_ms",elapsedMs).put("segments",segments).put("timeline_coverage",coverage).put("error",error);if(cer>=0)x.put("cer",cer).put("deletion_rate",deletionRate);if(timestampErrorMs>=0)x.put("mean_timestamp_error_ms",timestampErrorMs);return x;}
    }

    private static double timestampError(List<RefSegment> ref,List<AsrDocument.Segment> actual){if(ref.isEmpty()||actual.isEmpty())return -1;double sum=0;int n=0;for(RefSegment r:ref){long center=(r.start+r.end)/2,best=Long.MAX_VALUE;for(AsrDocument.Segment a:actual){long diff=Math.abs(center-(a.startMs+a.endMs)/2);if(diff<best)best=diff;}if(best<Long.MAX_VALUE){sum+=best;n++;}}return n==0?-1:sum/n;}
    private static EditDistance distance(String a,String b){int n=a.length(),m=b.length();int[][] cost=new int[n+1][m+1],del=new int[n+1][m+1];for(int i=0;i<=n;i++){cost[i][0]=i;del[i][0]=i;}for(int j=0;j<=m;j++)cost[0][j]=j;for(int i=1;i<=n;i++)for(int j=1;j<=m;j++){int sub=cost[i-1][j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1),de=cost[i-1][j]+1,in=cost[i][j-1]+1;int min=Math.min(sub,Math.min(de,in));cost[i][j]=min;if(min==de){del[i][j]=del[i-1][j]+1;}else if(min==in){del[i][j]=del[i][j-1];}else del[i][j]=del[i-1][j-1];}return new EditDistance(cost[n][m],del[n][m],n);}
    private static String chars(String s){return s==null?"":s.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+","");}
    private static String safe(Throwable e){String s=e==null?"未知错误":e.getMessage();return s==null?e.getClass().getSimpleName():s.replaceAll("[\\r\\n]+"," ");}
    private static String pct(double d){return String.format(Locale.ROOT,"%.1f%%",d*100);}
    private static double clamp(double d){return Math.max(0,Math.min(1,d));}
    private static String time(long ms){return String.format(Locale.ROOT,"%.1fs",ms/1000d);}
    private static final class EditDistance{final int total,deletions,reference;EditDistance(int t,int d,int r){total=t;deletions=d;reference=r;}}
    private static final class RefSegment{final long start,end;RefSegment(long s,long e){start=s;end=e;}}
    private static final class Reference{final String text;final List<RefSegment>segments;Reference(String t,List<RefSegment>s){text=t;segments=s;}
        static Reference parse(String raw){String x=raw==null?"":raw.trim();ArrayList<RefSegment>s=new ArrayList<>();StringBuilder text=new StringBuilder();String[] blocks=x.split("(?:\\r?\\n){2,}");for(String block:blocks){String[] lines=block.split("\\r?\\n");int timing=-1;for(int i=0;i<lines.length;i++)if(lines[i].contains("-->")){timing=i;break;}if(timing>=0){String[] p=lines[timing].split("-->");if(p.length==2){long a=parseTime(p[0]),b=parseTime(p[1]);if(a>=0&&b>a)s.add(new RefSegment(a,b));}for(int i=timing+1;i<lines.length;i++){if(text.length()>0)text.append('\n');text.append(lines[i]);}}}return new Reference(text.length()==0?x:text.toString(),s);}
        static long parseTime(String raw){try{String[] hm=raw.trim().replace('.',',').split(":");String[] sm=hm[2].split(",");return Long.parseLong(hm[0])*3600000+Long.parseLong(hm[1])*60000+Long.parseLong(sm[0])*1000+Long.parseLong(sm[1]);}catch(Exception e){return-1;}}
    }
}
