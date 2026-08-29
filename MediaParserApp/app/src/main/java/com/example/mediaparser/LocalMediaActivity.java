package com.example.mediaparser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.subtitle.AliyunKeyStore;
import com.example.mediaparser.subtitle.AliyunSettings;
import com.example.mediaparser.subtitle.AsrBenchmark;
import com.example.mediaparser.subtitle.DoubaoCredentialStore;
import com.example.mediaparser.subtitle.GeminiKeyStore;
import com.example.mediaparser.subtitle.GroqKeyStore;
import com.example.mediaparser.subtitle.LocalModelManager;
import com.example.mediaparser.subtitle.SubtitleExtractor;
import com.example.mediaparser.subtitle.SubtitleOutput;
import com.example.mediaparser.subtitle.SubtitleProvider;
import com.example.mediaparser.subtitle.SubtitleSaver;
import com.example.mediaparser.subtitle.TranscriptionOptions;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local video/audio import, meeting workflow and six-engine benchmark. */
public final class LocalMediaActivity extends Activity {
    private static final int VIDEO=5101,AUDIO=5102,BLUE=Color.rgb(39,100,231),TEXT=Color.rgb(23,27,35),MUTED=Color.rgb(103,112,126),BORDER=Color.rgb(224,228,236),PANEL=Color.rgb(247,249,252);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final EnumMap<SubtitleProvider,CheckBox> checks=new EnumMap<>(SubtitleProvider.class);private final EnumMap<SubtitleProvider,TextView> engineStates=new EnumMap<>(SubtitleProvider.class);
    private Spinner mode,engine;private TextView selected,state;private EditText reference;private CheckBox cloudPermission;private Button start;private MediaItem source;private String title="local_media",mime="";private boolean video,busy;
    private static final SubtitleProvider[] SIX={SubtitleProvider.LOCAL,SubtitleProvider.ALIYUN,SubtitleProvider.QWEN3,SubtitleProvider.DOUBAO,SubtitleProvider.GEMINI,SubtitleProvider.GROQ};

    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(ui());String pick=getIntent().getStringExtra("pick");if("video".equals(pick))pickVideo();else if("audio".equals(pick))pickAudio();else if("meeting".equals(pick)){mode.setSelection(1);pickAudio();}}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}

    private View ui(){ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(24),dp(16),dp(32));scroll.addView(root);
        root.addView(text("本地音视频工作台",26,TEXT,true));TextView intro=text("相册视频、录屏、会议录音和采访文件都可直接读取。支持 MP4 / MOV / MKV / MP3 / M4A / WAV / AAC。",13,MUTED,false);intro.setPadding(0,dp(6),0,dp(12));root.addView(intro);
        LinearLayout choose=new LinearLayout(this);Button v=button("选择本地视频"),a=button("选择本地音频");choose.addView(v,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams al=new LinearLayout.LayoutParams(0,dp(48),1);al.setMarginStart(dp(8));choose.addView(a,al);root.addView(choose);v.setOnClickListener(x->pickVideo());a.setOnClickListener(x->pickAudio());
        selected=text("尚未选择文件",14,MUTED,false);selected.setPadding(0,dp(10),0,dp(12));root.addView(selected);
        root.addView(label("处理模式"));mode=spinner(new String[]{"字幕模式","会议 / 录音模式","六引擎 ASR 横评"});root.addView(mode,new LinearLayout.LayoutParams(-1,dp(52)));
        root.addView(label("单引擎 / 自动模式"));String[] names=new String[SubtitleProvider.values().length];for(int i=0;i<names.length;i++)names[i]=SubtitleProvider.values()[i].label;engine=spinner(names);engine.setSelection(indexOf(SubtitleProvider.AUTO));root.addView(engine,new LinearLayout.LayoutParams(-1,dp(52)));
        LinearLayout benchmark=panel();benchmark.addView(text("横评引擎（同一文件逐个执行、分别显示进度）",15,TEXT,true));for(SubtitleProvider p:SIX){CheckBox c=new CheckBox(this);c.setText(p.label);c.setChecked(p==SubtitleProvider.LOCAL||p==SubtitleProvider.GROQ||p==SubtitleProvider.GEMINI);checks.put(p,c);benchmark.addView(c);TextView s=text("未运行",12,MUTED,false);s.setPadding(dp(28),0,0,dp(4));engineStates.put(p,s);benchmark.addView(s);}TextView honest=text("阿里 Paraformer/Qwen3 会使用百炼官方临时存储，文件与模型/账号绑定，48小时自动清理；其他云端直接上传给对应服务商。云端调用必须单独勾选许可。",12,MUTED,false);honest.setPadding(0,dp(6),0,0);benchmark.addView(honest);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(10);root.addView(benchmark,bp);
        root.addView(label("参考稿（可选：粘贴硬字幕 OCR、人工稿或 SRT）"));reference=new EditText(this);reference.setHint("有参考稿才计算真实 CER、漏字率和时间戳误差；没有参考稿不会伪造分数。");reference.setMinLines(3);reference.setMaxLines(8);reference.setBackground(bg(Color.WHITE,12,BORDER));reference.setPadding(dp(12),dp(10),dp(12),dp(10));root.addView(reference);
        cloudPermission=new CheckBox(this);cloudPermission.setText("允许本任务调用云端 ASR（可能消耗试用或付费额度）");cloudPermission.setChecked(false);root.addView(cloudPermission);
        start=button("开始处理");LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(52));sp.topMargin=dp(8);root.addView(start,sp);start.setOnClickListener(x->start());state=text("",13,MUTED,false);state.setPadding(0,dp(10),0,0);root.addView(state);
        TextView note=text("会议模式不会截图。识别结束后可编辑、播放当前句、合并、拆分、删除，并导出逐字稿或复制网页会议纪要提示词。",12,MUTED,false);note.setPadding(0,dp(18),0,0);root.addView(note);return scroll;}

    private void pickVideo(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/mp4","video/quicktime","video/x-matroska","video/webm"});startActivityForResult(i,VIDEO);}
    private void pickAudio(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/mpeg","audio/mp4","audio/wav","audio/x-wav","audio/aac","audio/flac","audio/ogg"});startActivityForResult(i,AUDIO);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}video=request==VIDEO;mime=getContentResolver().getType(uri);if(mime==null)mime=video?"video/mp4":"audio/mp4";title=name(uri);String ext=extension(title,mime);source=MediaItem.local(video?MediaItem.Type.VIDEO:MediaItem.Type.AUDIO,title,uri.toString(),ext,mime);getSharedPreferences("diagnostic_last_media",MODE_PRIVATE).edit().putString("uri",uri.toString()).putString("title",title).putString("mime",mime).putString("ext",ext).putBoolean("video",video).apply();selected.setText("已选择："+title+"\n"+(video?"本地视频":"本地音频")+" · "+mime);}

    private void start(){if(busy)return;if(source==null){toast("请先选择本地视频或音频");return;}int m=mode.getSelectedItemPosition();if(m==2)runBenchmark();else runSingle(m==1);}
    private void runSingle(boolean meeting){SubtitleProvider p=SubtitleProvider.values()[engine.getSelectedItemPosition()];String issue=preflight(p);if(!issue.isBlank()){alert(issue);return;}busy=true;start.setEnabled(false);state.setText("正在准备 "+title+"…");worker.execute(()->{long begun=System.currentTimeMillis();try{SubtitleOutput out=run(p,listener(p),meeting);runOnUiThread(()->{busy=false;start.setEnabled(true);state.setText("完成 · 实际引擎："+out.actualEngine+" · "+seconds(System.currentTimeMillis()-begun)+"\n已保存原稿；正在打开编辑器…");try{startActivity(TranscriptionEditorActivity.intent(this,out.document(),title,source.url,mime,video,meeting));}catch(Exception e){alert("无法打开编辑器："+e.getMessage());}});}catch(Exception e){String msg=SubtitleExtractor.errorMessage(e,key(p));runOnUiThread(()->{busy=false;start.setEnabled(true);state.setText("失败："+msg);});}});}

    private void runBenchmark(){ArrayList<SubtitleProvider> selectedProviders=new ArrayList<>();for(SubtitleProvider p:SIX)if(checks.get(p).isChecked())selectedProviders.add(p);if(selectedProviders.isEmpty()){toast("至少选择一个横评引擎");return;}busy=true;start.setEnabled(false);state.setText("横评开始 · 每条引擎独立计时");for(SubtitleProvider p:SIX)engineStates.get(p).setText(checks.get(p).isChecked()?"等待中":"未选择");String ref=reference.getText().toString();worker.execute(()->{ArrayList<AsrBenchmark.Score> scores=new ArrayList<>();ArrayList<SubtitleOutput> outputs=new ArrayList<>();ArrayList<SubtitleProvider> successes=new ArrayList<>();for(SubtitleProvider p:selectedProviders){long begun=System.currentTimeMillis();String issue=preflight(p);if(!issue.isBlank()){scores.add(AsrBenchmark.failure(p.label,0,new IllegalStateException(issue)));setEngineState(p,"跳过："+issue);continue;}setEngineState(p,"运行中…");try{SubtitleOutput out=run(p,listener(p),false);scores.add(AsrBenchmark.score(p.label,out,System.currentTimeMillis()-begun,ref));outputs.add(out);successes.add(p);setEngineState(p,"完成 · "+seconds(System.currentTimeMillis()-begun)+" · 实际 "+out.actualEngine);}catch(Exception e){String msg=SubtitleExtractor.errorMessage(e,key(p));scores.add(AsrBenchmark.failure(p.label,System.currentTimeMillis()-begun,e));setEngineState(p,"失败 · "+msg);}}
            String report=AsrBenchmark.report(scores,!ref.trim().isBlank());try{SubtitleSaver.saveText(this,title,"_ASR横评",".txt","text/plain",report);SubtitleSaver.saveText(this,title,"_ASR横评",".json","application/json",AsrBenchmark.json(scores,!ref.trim().isBlank()).toString(2));}catch(Exception e){report+="\n报告保存失败："+e.getMessage();}String finalReport=report;runOnUiThread(()->{busy=false;start.setEnabled(true);state.setText(finalReport);if(!outputs.isEmpty())chooseResult(outputs,successes);});});}

    private void chooseResult(List<SubtitleOutput> outputs,List<SubtitleProvider> providers){String[] names=new String[outputs.size()];for(int i=0;i<names.length;i++)names[i]=providers.get(i).label+" → "+outputs.get(i).actualEngine;new AlertDialog.Builder(this).setTitle("打开哪一份原稿编辑？").setItems(names,(d,index)->{try{startActivity(TranscriptionEditorActivity.intent(this,outputs.get(index).document(),title,source.url,mime,video,false));}catch(Exception e){alert(e.getMessage());}}).setNegativeButton("稍后",null).show();}

    private SubtitleOutput run(SubtitleProvider p,SubtitleExtractor.Listener listener,boolean meeting)throws Exception{TranscriptionOptions options=options(meeting);if(p==SubtitleProvider.AUTO){SubtitleProvider primary=SubtitleProvider.fromSaved(getSharedPreferences("subtitle_recognition",MODE_PRIVATE).getString("auto_primary","LOCAL"));return SubtitleExtractor.extractAuto(this,source,title,primary,cloudPermission.isChecked(),options,listener);}return SubtitleExtractor.extract(this,source,title,p,key(p),options,listener);}
    private SubtitleExtractor.Listener listener(SubtitleProvider p){return new SubtitleExtractor.Listener(){public void onStage(String s){setEngineState(p,s);runOnUiThread(()->state.setText(p.label+"："+s));}public void onSourceProgress(int percent,long done,long total){onStage("读取本地文件 "+percent+"%");}public void onUploadProgress(int percent,long done,long total){onStage("上传 "+percent+"%");}public void onTranscribeStart(long d){onStage("服务端识别中…");}};}
    private void setEngineState(SubtitleProvider p,String s){runOnUiThread(()->{TextView v=engineStates.get(p);if(v!=null)v.setText(s);});}
    private String preflight(SubtitleProvider p){if(p==SubtitleProvider.AUTO)return LocalModelManager.isInstalled(this,LocalModelManager.selected(this))||cloudPermission.isChecked()?"":"自动模式当前没有已安装本地模型，且未允许云端调用";if(p.isCloud()&&!cloudPermission.isChecked())return "未勾选允许本任务调用云端 ASR";if(p==SubtitleProvider.LOCAL&&!LocalModelManager.isInstalled(this,LocalModelManager.selected(this)))return "本地模型未安装，请回首页下载并校验";if((p==SubtitleProvider.ALIYUN||p==SubtitleProvider.QWEN3)&&(!AliyunKeyStore.hasKey(this)||AliyunSettings.workspace(this).isBlank()))return "阿里云 Key / Workspace 未配置";if(p==SubtitleProvider.DOUBAO){DoubaoCredentialStore.Credentials c=DoubaoCredentialStore.load(this);if(c.appId.isBlank()||c.accessToken.isBlank())return "本地文件直传豆包需要 App ID + Access Token，并开通流式小时版";}if(p==SubtitleProvider.GEMINI&&!GeminiKeyStore.hasKey(this))return "Gemini Key 未配置";if(p==SubtitleProvider.GROQ&&!GroqKeyStore.hasKey(this))return "Groq Key 未配置";return"";}
    private String key(SubtitleProvider p){if(p==SubtitleProvider.ALIYUN||p==SubtitleProvider.QWEN3)return AliyunKeyStore.load(this);if(p==SubtitleProvider.GROQ)return GroqKeyStore.load(this);if(p==SubtitleProvider.GEMINI)return GeminiKeyStore.load(this);return p==SubtitleProvider.LOCAL?"local":"doubao";}
    private TranscriptionOptions options(boolean meeting){android.content.SharedPreferences x=getSharedPreferences("subtitle_recognition",MODE_PRIVATE);return new TranscriptionOptions(x.getString("language","auto"),x.getBoolean("accurate",true),x.getString("terms",""),video,meeting);}

    private String name(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "local_media";}
    private static String extension(String name,String mime){String n=name==null?"":name;int dot=n.lastIndexOf('.');if(dot>=0&&dot>n.length()-6)return n.substring(dot).toLowerCase(Locale.ROOT);if(mime==null)return".media";if(mime.contains("quicktime"))return".mov";if(mime.contains("matroska"))return".mkv";if(mime.contains("mpeg"))return".mp3";if(mime.contains("wav"))return".wav";if(mime.contains("aac"))return".aac";return mime.startsWith("video/")?".mp4":".m4a";}
    private Spinner spinner(String[] values){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);s.setBackground(bg(Color.WHITE,12,BORDER));return s;}
    private int indexOf(SubtitleProvider p){SubtitleProvider[] a=SubtitleProvider.values();for(int i=0;i<a.length;i++)if(a[i]==p)return i;return 0;}
    private TextView label(String s){TextView v=text(s,14,TEXT,true);v.setPadding(0,dp(14),0,dp(5));return v;}
    private LinearLayout panel(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(12),dp(12),dp(12),dp(12));x.setBackground(bg(PANEL,14,BORDER));return x;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setBackground(bg(BLUE,12,BLUE));return b;}
    private TextView text(String s,int z,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(bold)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD);return v;}
    private GradientDrawable bg(int c,int r,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));d.setStroke(dp(1),stroke);return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void alert(String s){new AlertDialog.Builder(this).setMessage(s).setPositiveButton("知道了",null).show();}
    private static String seconds(long ms){return String.format(Locale.ROOT,"%.1f 秒",ms/1000d);}
}

