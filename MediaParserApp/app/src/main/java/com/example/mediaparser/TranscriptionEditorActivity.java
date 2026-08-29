package com.example.mediaparser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.subtitle.AsrDocument;
import com.example.mediaparser.subtitle.SubtitleOutput;
import com.example.mediaparser.subtitle.SubtitleSaver;
import com.example.mediaparser.subtitle.VisionPackageExporter;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Segment editor shared by subtitle and meeting workflows. */
public final class TranscriptionEditorActivity extends Activity {
    private static final int BLUE=Color.rgb(39,100,231),TEXT=Color.rgb(23,27,35),MUTED=Color.rgb(103,112,126),BORDER=Color.rgb(224,228,236),PANEL=Color.rgb(247,249,252);
    private final ArrayList<Row> rows=new ArrayList<>();private final Handler main=new Handler(Looper.getMainLooper());private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout list;private TextView summary;private int visible=40;private String engine="",language="",title="media",sourceUri="",sourceMime="",documentPath="";private long durationMs;private boolean meeting,video;private MediaPlayer player;private Runnable stopPlayback;

    public static Intent intent(Context context,AsrDocument document,String title,String sourceUri,String sourceMime,boolean video,boolean meeting)throws Exception{
        File folder=new File(context.getNoBackupFilesDir(),"editor");if(!folder.exists()&&!folder.mkdirs())throw new IllegalStateException("无法创建编辑缓存");cleanupOld(folder);File f=new File(folder,UUID.randomUUID()+".json");write(f,document.toJson().toString());
        return new Intent(context,TranscriptionEditorActivity.class).putExtra("document",f.getAbsolutePath()).putExtra("title",title).putExtra("source_uri",sourceUri).putExtra("source_mime",sourceMime).putExtra("video",video).putExtra("meeting",meeting);
    }

    @Override protected void onCreate(Bundle b){super.onCreate(b);try{load();setContentView(ui());}catch(Exception e){new AlertDialog.Builder(this).setTitle("无法打开转写稿").setMessage(e.getMessage()).setPositiveButton("关闭",(d,w)->finish()).show();}}
    @Override protected void onDestroy(){releasePlayer();worker.shutdownNow();if(isFinishing()&&!documentPath.isBlank())new File(documentPath).delete();super.onDestroy();}

    private void load()throws Exception{Intent i=getIntent();title=i.getStringExtra("title");sourceUri=i.getStringExtra("source_uri");sourceMime=i.getStringExtra("source_mime");meeting=i.getBooleanExtra("meeting",false);video=i.getBooleanExtra("video",false);documentPath=i.getStringExtra("document");if(documentPath==null)throw new IllegalArgumentException("缺少转写结果");File f=new File(documentPath);AsrDocument d=AsrDocument.fromJson(new JSONObject(read(f)));engine=d.engine;language=d.language;durationMs=d.durationMs;for(AsrDocument.Segment s:d.segments)rows.add(new Row(s.startMs,s.endMs,s.text,s.speaker,s.confidence));}

    private static void cleanupOld(File folder){File[] files=folder.listFiles();if(files==null)return;long cutoff=System.currentTimeMillis()-24L*60*60*1000;for(File f:files)if(f.isFile()&&f.lastModified()<cutoff)f.delete();}

    private static void write(File file,String value)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private static String read(File file)throws Exception{try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}

    private View ui(){ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(24),dp(16),dp(32));scroll.addView(root);
        root.addView(text(meeting?"会议逐字稿编辑器":"字幕时间轴编辑器",25,TEXT,true));root.addView(text(engine+(!language.isBlank()?" · "+language:"")+"\n点击编辑文字；播放、合并、拆分和删除都在本机完成。",13,MUTED,false));
        summary=text("",13,MUTED,false);summary.setPadding(0,dp(10),0,dp(10));root.addView(summary);
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);Button export=button("导出全部");Button ai=button(meeting?"会议纪要提示词":"整理字幕提示词");top.addView(export,new LinearLayout.LayoutParams(0,dp(46),1));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(46),1);ap.setMarginStart(dp(8));top.addView(ai,ap);root.addView(top);export.setOnClickListener(v->export());ai.setOnClickListener(v->copyPrompt());
        if(video&&!meeting){Button zip=button("制作硬字幕截图 ZIP（只在 ASR 附近搜索）");LinearLayout.LayoutParams zp=new LinearLayout.LayoutParams(-1,dp(46));zp.topMargin=dp(8);root.addView(zip,zp);zip.setOnClickListener(v->vision(zip));}
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);root.addView(list,lp);render();return scroll;}

    private void render(){list.removeAllViews();summary.setText("共 "+rows.size()+" 段 · 当前显示 "+Math.min(visible,rows.size())+" 段 · 编辑后词级时间戳会降级为句级时间轴");int n=Math.min(visible,rows.size());for(int index=0;index<n;index++){final int pos=index;Row r=rows.get(index);LinearLayout card=panel();card.addView(text((index+1)+"  "+clock(r.start)+" → "+clock(r.end)+(r.speaker.isBlank()?"":"  "+r.speaker),12,MUTED,true));TextView body=text(r.text,15,TEXT,false);body.setPadding(0,dp(7),0,dp(7));body.setTextIsSelectable(true);body.setOnClickListener(v->edit(pos));card.addView(body);LinearLayout actions=new LinearLayout(this);String[] labels={"播放","编辑","合并下条","拆分","删除"};for(String label:labels){Button x=small(label);actions.addView(x,new LinearLayout.LayoutParams(0,dp(38),1));if(label.equals("播放"))x.setOnClickListener(v->play(pos));else if(label.equals("编辑"))x.setOnClickListener(v->edit(pos));else if(label.equals("合并下条"))x.setOnClickListener(v->merge(pos));else if(label.equals("拆分"))x.setOnClickListener(v->split(pos));else x.setOnClickListener(v->delete(pos));}card.addView(actions);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(8);list.addView(card,cp);}
        if(visible<rows.size()){Button more=button("加载更多（剩余 "+(rows.size()-visible)+" 段）");more.setOnClickListener(v->{visible+=40;render();});list.addView(more,new LinearLayout.LayoutParams(-1,dp(44)));}}

    private void edit(int i){Row r=rows.get(i);LinearLayout box=new LinearLayout(this);box.setPadding(dp(18),0,dp(18),0);box.setOrientation(LinearLayout.VERTICAL);EditText text=new EditText(this);text.setText(r.text);text.setMinLines(3);box.addView(text);EditText speaker=new EditText(this);speaker.setHint("发言人（可选）");speaker.setText(r.speaker);box.addView(speaker);new AlertDialog.Builder(this).setTitle("编辑第 "+(i+1)+" 段").setView(box).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String value=text.getText().toString().trim();if(!value.isBlank()){r.text=value;r.speaker=speaker.getText().toString().trim();render();}}).show();}
    private void merge(int i){if(i+1>=rows.size()){toast("已经是最后一段");return;}Row a=rows.get(i),b=rows.remove(i+1);a.end=b.end;a.text=(a.text+" "+b.text).trim();if(a.speaker.isBlank())a.speaker=b.speaker;render();}
    private void split(int i){Row r=rows.get(i);int mid=Math.max(1,r.text.length()/2);LinearLayout box=new LinearLayout(this);box.setPadding(dp(18),0,dp(18),0);box.setOrientation(LinearLayout.VERTICAL);EditText first=new EditText(this),second=new EditText(this);first.setText(r.text.substring(0,mid));second.setText(r.text.substring(mid));box.addView(first);box.addView(second);new AlertDialog.Builder(this).setTitle("拆分第 "+(i+1)+" 段").setView(box).setNegativeButton("取消",null).setPositiveButton("拆分",(d,w)->{String a=first.getText().toString().trim(),c=second.getText().toString().trim();if(a.isBlank()||c.isBlank()){toast("两段文字都不能为空");return;}long originalEnd=r.end,t=(r.start+originalEnd)/2;r.text=a;r.end=t;rows.add(i+1,new Row(t,Math.max(t+1,originalEnd),c,r.speaker,r.confidence));render();}).show();}
    private void delete(int i){new AlertDialog.Builder(this).setTitle("删除这一段？").setMessage(rows.get(i).text).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{rows.remove(i);render();}).show();}

    private void play(int i){if(sourceUri==null||sourceUri.isBlank()){toast("没有可播放的源文件");return;}releasePlayer();Row r=rows.get(i);try{player=new MediaPlayer();player.setDataSource(this,Uri.parse(sourceUri));player.setOnPreparedListener(p->{p.seekTo(r.start>Integer.MAX_VALUE?Integer.MAX_VALUE:(int)r.start);p.start();stopPlayback=()->{if(player==p&&p.isPlaying())p.pause();};main.postDelayed(stopPlayback,Math.max(300,r.end-r.start));});player.setOnErrorListener((p,a,b)->{toast("无法播放该片段");releasePlayer();return true;});player.prepareAsync();}catch(Exception e){toast("播放失败："+e.getMessage());releasePlayer();}}
    private void releasePlayer(){if(stopPlayback!=null)main.removeCallbacks(stopPlayback);stopPlayback=null;if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}}

    private AsrDocument document(){ArrayList<AsrDocument.Segment>s=new ArrayList<>();for(int i=0;i<rows.size();i++){Row r=rows.get(i);s.add(new AsrDocument.Segment(i+1,r.start,r.end,r.text,r.speaker,r.confidence,Collections.emptyList()));}return new AsrDocument(engine,language,durationMs,s,Collections.emptyList());}
    private void export(){try{AsrDocument d=document();SubtitleSaver.saveText(this,title,"_编辑稿",".srt","application/x-subrip",d.srt());SubtitleSaver.saveText(this,title,"_编辑稿",".txt","text/plain",d.plainText());SubtitleSaver.saveText(this,title,"_带时间戳全文",".txt","text/plain",d.timestampText());SubtitleSaver.saveText(this,title,"_统一ASR",".json","application/json",d.toJson().toString(2));if(meeting)SubtitleSaver.saveText(this,title,"_会议网页整理包",".txt","text/plain",meetingPrompt(d));toast("已保存 SRT、TXT、时间戳全文和统一 JSON 到 Download");}catch(Exception e){toast("导出失败："+e.getMessage());}}
    private void copyPrompt(){String prompt=meeting?meetingPrompt(document()):subtitlePrompt(document());ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("MediaParser 网页整理提示词",prompt));toast("已复制，请粘贴到网页版高端模型");}
    private String meetingPrompt(AsrDocument d){return "请整理下面的会议逐字稿。不得杜撰。输出：会议主题、核心结论、讨论事项、决策、待办事项（负责人/截止时间）、未解决问题。保留关键时间戳；发言人未知时不要猜。\n\n"+d.timestampText();}
    private String subtitlePrompt(AsrDocument d){return "请校正下面的字幕原稿，保留时间轴。不要凭常识补写无法确认的内容；如果同时提供硬字幕截图，清晰画面文字优先于 ASR。输出合法 SRT 和纯文本。\n\n"+d.srt();}
    private void vision(Button b){b.setEnabled(false);b.setText("截图处理中…");worker.execute(()->{try{MediaItem source=MediaItem.local(MediaItem.Type.VIDEO,title,sourceUri,extension(sourceMime),sourceMime);VisionPackageExporter.Result r=VisionPackageExporter.export(this,source,title,SubtitleOutput.fromDocument(document()),s->main.post(()->b.setText(s)));main.post(()->{b.setEnabled(true);b.setText("重新制作硬字幕截图 ZIP");toast("已保存 "+r.locations.size()+" 个 ZIP，共 "+r.frames+" 张去重截图");});}catch(Exception e){main.post(()->{b.setEnabled(true);b.setText("重试制作截图 ZIP");toast("截图失败："+e.getMessage());});}});}

    private static String extension(String mime){if(mime==null)return".mp4";if(mime.contains("matroska"))return".mkv";if(mime.contains("quicktime"))return".mov";return".mp4";}
    private LinearLayout panel(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(12),dp(12),dp(12),dp(12));x.setBackground(bg(PANEL,14,BORDER));return x;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setBackground(bg(BLUE,12,BLUE));return b;}
    private Button small(String s){Button b=new Button(this);b.setText(s);b.setTextSize(11);b.setTextColor(TEXT);b.setAllCaps(false);b.setPadding(1,0,1,0);b.setBackground(bg(Color.WHITE,9,BORDER));return b;}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD);return v;}
    private GradientDrawable bg(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private static String clock(long ms){long h=ms/3600000;ms%=3600000;long m=ms/60000;ms%=60000;return String.format(java.util.Locale.ROOT,"%02d:%02d:%02d.%03d",h,m,ms/1000,ms%1000);}
    private static final class Row{long start,end;String text,speaker;double confidence;Row(long a,long b,String t,String s,double c){start=a;end=b;text=t;speaker=s;confidence=c;}}
}

