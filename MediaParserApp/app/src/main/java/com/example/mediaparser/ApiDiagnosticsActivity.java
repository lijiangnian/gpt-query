package com.example.mediaparser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.subtitle.AliyunKeyStore;
import com.example.mediaparser.subtitle.AliyunKeyValidator;
import com.example.mediaparser.subtitle.AliyunSettings;
import com.example.mediaparser.subtitle.AsrDocument;
import com.example.mediaparser.subtitle.DoubaoCredentialStore;
import com.example.mediaparser.subtitle.DoubaoCredentialValidator;
import com.example.mediaparser.subtitle.DiagnosticReportSanitizer;
import com.example.mediaparser.subtitle.GeminiKeyStore;
import com.example.mediaparser.subtitle.GeminiKeyValidator;
import com.example.mediaparser.subtitle.GroqKeyStore;
import com.example.mediaparser.subtitle.GroqKeyValidator;
import com.example.mediaparser.subtitle.LocalModelManager;
import com.example.mediaparser.subtitle.SubtitleExtractor;
import com.example.mediaparser.subtitle.SubtitleOutput;
import com.example.mediaparser.subtitle.SubtitleProvider;
import com.example.mediaparser.subtitle.SubtitleSaver;
import com.example.mediaparser.subtitle.TranscriptionOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-tap, on-device diagnostics. Raw credentials never leave the provider-specific request. */
public final class ApiDiagnosticsActivity extends Activity {
    private static final int BLUE=Color.rgb(39,100,231),TEXT=Color.rgb(23,27,35),MUTED=Color.rgb(103,112,126),BORDER=Color.rgb(224,228,236),PANEL=Color.rgb(247,249,252);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView state,media,pageLabel;private CheckBox actual;private Button run,copy,save,previous,next;private volatile boolean busy;private String report="";private List<String> reportPages=new ArrayList<>();private int pageIndex;
    private static final SubtitleProvider[] ENGINES={SubtitleProvider.LOCAL,SubtitleProvider.ALIYUN,SubtitleProvider.QWEN3,SubtitleProvider.DOUBAO,SubtitleProvider.GEMINI,SubtitleProvider.GROQ};

    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(ui());refreshMedia();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}

    private View ui(){ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(24),dp(16),dp(32));root.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(dp(16),dp(24)+bars.top,dp(16),dp(32)+bars.bottom);}return insets;});scroll.addView(root);
        root.addView(text("API 与整机一键测试",26,TEXT,true));TextView intro=text("读取本机 Android Keystore 中已保存的配置并逐项测试。报告只包含掩码、状态、耗时和脱敏错误，绝不导出完整 Key / Token。",13,MUTED,false);intro.setPadding(0,dp(6),0,dp(12));root.addView(intro);
        LinearLayout safety=panel();safety.addView(text("安全规则",15,TEXT,true));safety.addView(text("• 完整密钥不显示、不复制、不写日志、不进报告\n• 默认只鉴权，不上传媒体、不产生转写调用\n• 真实转写必须主动勾选，可能消耗试用或付费额度\n• 官方不能查询余额时只提示去控制台查看",12,MUTED,false));root.addView(safety);
        media=text("",13,MUTED,false);media.setPadding(0,dp(14),0,dp(6));root.addView(media);
        Button open=secondary("导入/更换测试文件");open.setOnClickListener(v->startActivity(new Intent(this,LocalMediaActivity.class)));root.addView(open,new LinearLayout.LayoutParams(-1,dp(46)));
        actual=new CheckBox(this);actual.setText("同时用最近导入文件依次真实转写六个引擎（可能耗费额度）");actual.setChecked(false);actual.setPadding(0,dp(8),0,dp(8));root.addView(actual);
        run=primary("开始全面测试");run.setOnClickListener(v->start());root.addView(run,new LinearLayout.LayoutParams(-1,dp(52)));
        state=text("尚未测试",13,MUTED,false);state.setTextIsSelectable(true);state.setPadding(0,dp(12),0,dp(12));root.addView(state);
        LinearLayout pager=new LinearLayout(this);previous=secondary("上一页");next=secondary("下一页");pageLabel=text("0 / 0",13,MUTED,true);pageLabel.setGravity(android.view.Gravity.CENTER);pager.addView(previous,new LinearLayout.LayoutParams(0,dp(44),1));pager.addView(pageLabel,new LinearLayout.LayoutParams(0,dp(44),1));pager.addView(next,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(pager);previous.setEnabled(false);next.setEnabled(false);previous.setOnClickListener(v->{if(pageIndex>0){pageIndex--;showPage();}});next.setOnClickListener(v->{if(pageIndex+1<reportPages.size()){pageIndex++;showPage();}});
        LinearLayout row=new LinearLayout(this);copy=secondary("复制脱敏报告");save=secondary("保存到 Download");copy.setEnabled(false);save.setEnabled(false);row.addView(copy,new LinearLayout.LayoutParams(0,dp(46),1));LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(0,dp(46),1);sl.setMarginStart(dp(8));row.addView(save,sl);root.addView(row);copy.setOnClickListener(v->copy());save.setOnClickListener(v->save());return scroll;}

    @Override protected void onResume(){super.onResume();refreshMedia();}
    private void refreshMedia(){android.content.SharedPreferences p=getSharedPreferences("diagnostic_last_media",MODE_PRIVATE);String title=p.getString("title","");media.setText(title.isBlank()?"真实转写样本：尚未保存最近导入文件（仍可运行全部鉴权）":"真实转写样本："+title+" · "+p.getString("mime",""));}
    private void start(){if(busy)return;boolean withActual=actual.isChecked();MediaItem sample=lastMedia();if(withActual&&sample==null){toast("请先导入一个本地视频或音频，App 会记住它作为测试样本");return;}busy=true;run.setEnabled(false);copy.setEnabled(false);save.setEnabled(false);previous.setEnabled(false);next.setEnabled(false);pageLabel.setText("测试中");state.setText("正在读取本机加密配置并开始鉴权…");worker.execute(()->{StringBuilder out=new StringBuilder(header(withActual));List<String> secrets=secrets();testAuth(out,secrets);if(withActual)testActual(out,sample,secrets);report=redact(out.toString(),secrets);reportPages=DiagnosticReportSanitizer.pages(report,12);pageIndex=0;runOnUiThread(()->{busy=false;run.setEnabled(true);copy.setEnabled(true);save.setEnabled(true);showPage();});});}

    private void testAuth(StringBuilder out,List<String> secrets){out.append("\n【API 鉴权（不上传媒体）】\n");
        append(out,"Gemini",GeminiKeyStore.hasKey(this),GeminiKeyStore.masked(this),()->GeminiKeyValidator.validate(GeminiKeyStore.load(this)));
        append(out,"Groq",GroqKeyStore.hasKey(this),GroqKeyStore.masked(this),()->GroqKeyValidator.validate(GroqKeyStore.load(this)));
        String ws=AliyunSettings.workspace(this);boolean ali=AliyunKeyStore.hasKey(this)&&!ws.isBlank();String aliMask=AliyunKeyStore.masked(this)+" / "+mask(ws);append(out,"阿里 Paraformer-v2",ali,aliMask,()->AliyunKeyValidator.validateParaformer(AliyunKeyStore.load(this),ws));append(out,"阿里 Qwen3-ASR",ali,aliMask,()->AliyunKeyValidator.validateQwen3(AliyunKeyStore.load(this),ws));
        DoubaoCredentialStore.Credentials d=DoubaoCredentialStore.load(this);append(out,"豆包/火山",d.configured(),d.masked()+" / "+d.resourceId,()->DoubaoCredentialValidator.validate(d));
        for(LocalModelManager.ModelSpec s:LocalModelManager.CATALOG)out.append("本地模型 ").append(s.name).append("：").append(LocalModelManager.isInstalled(this,s)?"已安装并校验":"未安装").append('\n');
    }

    private void testActual(StringBuilder out,MediaItem sample,List<String> secrets){out.append("\n【真实转写与时间轴检查】\n");ArrayList<SpeedRank> ranks=new ArrayList<>();for(SubtitleProvider p:ENGINES){String issue=preflight(p);if(!issue.isBlank()){out.append(p.label).append("：跳过 · ").append(issue).append('\n');continue;}update("真实转写："+p.label+"…");long begun=System.currentTimeMillis();try{SubtitleOutput result=SubtitleExtractor.extract(this,sample,"API全面测试",p,key(p),new TranscriptionOptions("auto",true,"",sample.type==MediaItem.Type.VIDEO,false),null);long elapsed=System.currentTimeMillis()-begun;AsrDocument doc=result.document();boolean valid=validTimeline(doc);out.append(p.label).append("：成功 · 实际 ").append(result.actualEngine).append(" · ").append(seconds(elapsed)).append(" · ").append(doc.segments.size()).append(" 段 · 时间轴").append(valid?"通过":"异常").append(" · 词级 ").append(wordCount(doc)).append('\n');ranks.add(new SpeedRank(p.label,elapsed));}catch(Exception e){out.append(p.label).append("：失败 · ").append(SubtitleExtractor.errorMessage(e,key(p))).append(" · ").append(seconds(System.currentTimeMillis()-begun)).append('\n');}}if(!ranks.isEmpty()){ranks.sort(Comparator.comparingLong(x->x.elapsedMs));out.append("\n【本次速度排序】\n");for(int i=0;i<ranks.size();i++){SpeedRank x=ranks.get(i);out.append(i+1).append(". ").append(x.name).append(" · ").append(seconds(x.elapsedMs)).append('\n');}out.append("仅按本次耗时排序，不代表文字准确率。\n");}}

    private void append(StringBuilder out,String name,boolean configured,String masked,Call call){if(!configured){out.append(name).append("：未配置\n");return;}update("鉴权："+name+"…");long begun=System.currentTimeMillis();try{GeminiKeyValidator.Result r=call.run();out.append(name).append("：").append(r.usable()?"通过":"未通过").append(" · ").append(masked).append(" · ").append(r.state).append(" · ").append(r.message).append(" · ").append(seconds(System.currentTimeMillis()-begun)).append('\n');}catch(Exception e){out.append(name).append("：异常 · ").append(e.getClass().getSimpleName()).append(" · ").append(seconds(System.currentTimeMillis()-begun)).append('\n');}}
    private String preflight(SubtitleProvider p){if(p==SubtitleProvider.LOCAL&&!LocalModelManager.isInstalled(this,LocalModelManager.selected(this)))return"当前选择的本地模型未安装";if((p==SubtitleProvider.ALIYUN||p==SubtitleProvider.QWEN3)&&(!AliyunKeyStore.hasKey(this)||AliyunSettings.workspace(this).isBlank()))return"阿里配置缺失";if(p==SubtitleProvider.DOUBAO){DoubaoCredentialStore.Credentials c=DoubaoCredentialStore.load(this);if(c.appId.isBlank()||c.accessToken.isBlank())return"真实文件直传需要 App ID + Access Token";}if(p==SubtitleProvider.GEMINI&&!GeminiKeyStore.hasKey(this))return"Gemini Key 缺失";if(p==SubtitleProvider.GROQ&&!GroqKeyStore.hasKey(this))return"Groq Key 缺失";return"";}
    private String key(SubtitleProvider p){if(p==SubtitleProvider.GEMINI)return GeminiKeyStore.load(this);if(p==SubtitleProvider.GROQ)return GroqKeyStore.load(this);if(p==SubtitleProvider.ALIYUN||p==SubtitleProvider.QWEN3)return AliyunKeyStore.load(this);return p==SubtitleProvider.LOCAL?"local":"doubao";}
    private MediaItem lastMedia(){android.content.SharedPreferences p=getSharedPreferences("diagnostic_last_media",MODE_PRIVATE);String uri=p.getString("uri","");if(uri.isBlank())return null;boolean video=p.getBoolean("video",false);return MediaItem.local(video?MediaItem.Type.VIDEO:MediaItem.Type.AUDIO,p.getString("title","test_media"),uri,p.getString("ext",video?".mp4":".m4a"),p.getString("mime",video?"video/mp4":"audio/mp4"));}
    private List<String> secrets(){ArrayList<String>x=new ArrayList<>();x.add(GeminiKeyStore.load(this));x.add(GroqKeyStore.load(this));x.add(AliyunKeyStore.load(this));DoubaoCredentialStore.Credentials d=DoubaoCredentialStore.load(this);x.add(d.apiKey);x.add(d.accessToken);x.add(d.appId);return x;}
    static String redact(String raw,List<String> secrets){return DiagnosticReportSanitizer.redact(raw,secrets);}
    private String header(boolean actual){return "MediaParser API 全面测试报告\n版本："+version()+"\n时间："+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(new Date())+"\n设备："+Build.MANUFACTURER+" "+Build.MODEL+" / Android "+Build.VERSION.RELEASE+" / "+Build.SUPPORTED_ABIS[0]+"\n真实转写："+(actual?"已明确允许":"未允许")+"\n余额：官方无法统一查询时请到各服务商控制台查看\n";}
    private String version(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return"未知";}}
    private static boolean validTimeline(AsrDocument d){long last=-1;for(AsrDocument.Segment s:d.segments){if(s.startMs<0||s.endMs<=s.startMs||s.startMs<last)return false;last=s.endMs;}return!d.segments.isEmpty();}
    private static int wordCount(AsrDocument d){int n=0;for(AsrDocument.Segment s:d.segments)n+=s.words.size();return n;}
    private void update(String s){runOnUiThread(()->state.setText(s));}
    private void showPage(){if(reportPages.isEmpty()){state.setText("没有报告");pageLabel.setText("0 / 0");previous.setEnabled(false);next.setEnabled(false);return;}state.setText(reportPages.get(pageIndex));pageLabel.setText((pageIndex+1)+" / "+reportPages.size());previous.setEnabled(pageIndex>0);next.setEnabled(pageIndex+1<reportPages.size());}
    private void copy(){ClipboardManager c=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);if(c!=null){c.setPrimaryClip(ClipData.newPlainText("MediaParser 脱敏测试报告",report));toast("已复制脱敏报告");}}
    private void save(){try{SubtitleSaver.saveText(this,"MediaParser","_API全面测试报告",".txt","text/plain",report);toast("已保存到系统 Download");}catch(Exception e){toast("保存失败："+e.getMessage());}}
    private static String mask(String s){if(s==null||s.isBlank())return"";return s.length()<9?"••••":s.substring(0,4)+"••••"+s.substring(s.length()-4);}
    private static String seconds(long ms){return String.format(Locale.ROOT,"%.1fs",ms/1000d);}
    private LinearLayout panel(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(12),dp(12),dp(12),dp(12));x.setBackground(bg(PANEL,14,BORDER));return x;}
    private Button primary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackground(bg(BLUE,12,BLUE));return b;}
    private Button secondary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(TEXT);b.setBackground(bg(Color.WHITE,12,BORDER));return b;}
    private TextView text(String s,int z,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(bold)v.setTypeface(v.getTypeface(),Typeface.BOLD);return v;}
    private GradientDrawable bg(int c,int r,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));d.setStroke(dp(1),stroke);return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private interface Call{GeminiKeyValidator.Result run();}
    private static final class SpeedRank{final String name;final long elapsedMs;SpeedRank(String n,long e){name=n;elapsedMs=e;}}
}
