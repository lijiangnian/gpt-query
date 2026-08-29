package com.example.mediaparser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mediaparser.subtitle.AliyunKeyStore;
import com.example.mediaparser.subtitle.AliyunKeyValidator;
import com.example.mediaparser.subtitle.AliyunSettings;
import com.example.mediaparser.subtitle.DoubaoCredentialStore;
import com.example.mediaparser.subtitle.DoubaoCredentialValidator;
import com.example.mediaparser.subtitle.GeminiKeyStore;
import com.example.mediaparser.subtitle.GeminiKeyValidator;
import com.example.mediaparser.subtitle.GroqKeyStore;
import com.example.mediaparser.subtitle.GroqKeyValidator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated credential page; the home screen only links here. */
public final class ApiSettingsActivity extends Activity {
    private static final int BLUE=Color.rgb(39,100,231),TEXT=Color.rgb(23,27,35),MUTED=Color.rgb(103,112,126),BORDER=Color.rgb(224,228,236),PANEL=Color.rgb(247,249,252);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(ui());}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private View ui(){ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(24),dp(16),dp(32));root.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(dp(16),dp(24)+bars.top,dp(16),dp(32)+bars.bottom);}return insets;});scroll.addView(root);root.addView(navigation());
        root.addView(text("API 设置",26,TEXT,true));TextView intro=text("所有凭证均由 Android Keystore 加密保存在本机。这里负责配置；测试结果在独立分页页面查看。",13,MUTED,false);intro.setPadding(0,dp(6),0,dp(10));root.addView(intro);
        Button diagnostics=primary("一键全面测试 · 分页报告");diagnostics.setOnClickListener(v->startActivity(new Intent(this,ApiDiagnosticsActivity.class)));root.addView(diagnostics,new LinearLayout.LayoutParams(-1,dp(50)));
        Button help=secondary("开发者说明 · API 获取与使用帮助");help.setOnClickListener(v->startActivity(new Intent(this,DeveloperHelpActivity.class)));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,dp(50));hp.topMargin=dp(8);root.addView(help,hp);
        addGemini(root);addGroq(root);addAliyun(root);addDoubao(root);return scroll;}
    private void addGemini(LinearLayout root){LinearLayout card=card(root,"Gemini");TextView status=status(card);EditText key=secret("粘贴 Gemini API Key");card.addView(key,row());actions(card,"清除","保存并测试",()->{GeminiKeyStore.clear(this);status.setText("状态：未设置");},b->{String v=key.getText().toString().trim();if(v.isBlank())v=GeminiKeyStore.load(this);if(v.isBlank()){status.setText("请先填写 Key");return;}try{GeminiKeyStore.save(this,v);key.setText("");runTest(b,status,"Gemini",()->GeminiKeyValidator.validate(GeminiKeyStore.load(this)));}catch(Exception e){status.setText("保存失败："+e.getMessage());}});status.setText(GeminiKeyStore.hasKey(this)?"状态：已保存 "+GeminiKeyStore.masked(this):"状态：未设置");}
    private void addGroq(LinearLayout root){LinearLayout card=card(root,"Groq Whisper");TextView status=status(card);EditText key=secret("粘贴 Groq API Key");card.addView(key,row());actions(card,"清除","保存并测试",()->{GroqKeyStore.clear(this);status.setText("状态：未设置");},b->{String v=key.getText().toString().trim();if(v.isBlank())v=GroqKeyStore.load(this);if(v.isBlank()){status.setText("请先填写 Key");return;}try{GroqKeyStore.save(this,v);key.setText("");runTest(b,status,"Groq",()->GroqKeyValidator.validate(GroqKeyStore.load(this)));}catch(Exception e){status.setText("保存失败："+e.getMessage());}});status.setText(GroqKeyStore.hasKey(this)?"状态：已保存 "+GroqKeyStore.masked(this):"状态：未设置");}
    private void addAliyun(LinearLayout root){LinearLayout card=card(root,"阿里云 Paraformer / Qwen3-ASR");TextView status=status(card);EditText key=secret("DashScope / 百炼 API Key"),ws=input("Workspace ID（北京地域）");ws.setText(AliyunSettings.workspace(this));card.addView(key,row());card.addView(ws,rowTop());actions(card,"清除","保存并测试两种模型",()->{AliyunSettings.clear(this);key.setText("");ws.setText("");status.setText("状态：未设置");},b->{String v=key.getText().toString().trim();if(v.isBlank())v=AliyunKeyStore.load(this);String w=ws.getText().toString().trim();if(v.isBlank()||w.isBlank()){status.setText("请填写 Key 与 Workspace");return;}try{AliyunKeyStore.save(this,v);AliyunSettings.saveWorkspace(this,w);key.setText("");runTest(b,status,"阿里云",()->AliyunKeyValidator.validate(AliyunKeyStore.load(this),AliyunSettings.workspace(this)));}catch(Exception e){status.setText("保存失败："+e.getMessage());}});status.setText(AliyunKeyStore.hasKey(this)?"状态：已保存 "+AliyunKeyStore.masked(this)+" / "+AliyunSettings.workspace(this):"状态：未设置");}
    private void addDoubao(LinearLayout root){LinearLayout card=card(root,"豆包 / 火山引擎 ASR");TextView status=status(card);EditText api=secret("新版 X-Api-Key（有则只填这个）"),app=input("旧版 App ID"),token=secret("旧版 Access Token"),resource=input("Resource ID");resource.setText(DoubaoCredentialStore.load(this).resourceId);card.addView(api,row());card.addView(app,rowTop());card.addView(token,rowTop());card.addView(resource,rowTop());actions(card,"清除","保存并测试",()->{DoubaoCredentialStore.clear(this);api.setText("");app.setText("");token.setText("");resource.setText(DoubaoCredentialStore.DEFAULT_RESOURCE);status.setText("状态：未设置");},b->{DoubaoCredentialStore.Credentials old=DoubaoCredentialStore.load(this);String a=api.getText().toString().trim(),id=app.getText().toString().trim(),t=token.getText().toString().trim(),r=resource.getText().toString().trim();DoubaoCredentialStore.Credentials c=!a.isBlank()?new DoubaoCredentialStore.Credentials(a,"","",r):new DoubaoCredentialStore.Credentials(old.apiKey,id.isBlank()?old.appId:id,t.isBlank()?old.accessToken:t,r);if(!c.configured()){status.setText("请填写新版 Key，或 App ID + Access Token");return;}try{DoubaoCredentialStore.save(this,c);api.setText("");app.setText("");token.setText("");runTest(b,status,"豆包",()->DoubaoCredentialValidator.validate(DoubaoCredentialStore.load(this)));}catch(Exception e){status.setText("保存失败："+e.getMessage());}});DoubaoCredentialStore.Credentials saved=DoubaoCredentialStore.load(this);status.setText(saved.configured()?"状态：已保存 "+saved.masked()+" / "+saved.resourceId:"状态：未设置");}
    private void runTest(Button b,TextView s,String name,Call call){b.setEnabled(false);b.setText("测试中…");s.setText(name+"：正在鉴权…");worker.execute(()->{GeminiKeyValidator.Result r;try{r=call.run();}catch(Exception e){r=GeminiKeyValidator.Result.error(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}GeminiKeyValidator.Result done=r;runOnUiThread(()->{b.setEnabled(true);b.setText("保存并测试");s.setText(name+"："+(done.usable()?"通过 · ":"未通过 · ")+done.message);});});}
    private LinearLayout card(LinearLayout root,String title){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(12),dp(12),dp(12),dp(12));x.setBackground(bg(PANEL,14,BORDER));x.addView(text(title,16,TEXT,true));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);root.addView(x,p);return x;}
    private TextView status(LinearLayout c){TextView s=text("",12,MUTED,false);s.setPadding(0,dp(5),0,dp(8));c.addView(s);return s;}
    private void actions(LinearLayout c,String left,String right,Runnable clear,Action action){LinearLayout r=new LinearLayout(this);Button a=secondary(left),b=primary(right);r.addView(a,new LinearLayout.LayoutParams(0,dp(46),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),2);p.setMarginStart(dp(8));r.addView(b,p);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(8);c.addView(r,rp);a.setOnClickListener(v->clear.run());b.setOnClickListener(v->action.run(b));}
    private EditText secret(String hint){EditText e=input(hint);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
    private EditText input(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextSize(14);e.setTextColor(TEXT);e.setPadding(dp(12),0,dp(12),0);e.setBackground(bg(Color.WHITE,10,BORDER));return e;}
    private LinearLayout.LayoutParams row(){return new LinearLayout.LayoutParams(-1,dp(46));}private LinearLayout.LayoutParams rowTop(){LinearLayout.LayoutParams p=row();p.topMargin=dp(8);return p;}
    private Button primary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackground(bg(BLUE,12,BLUE));return b;}private Button secondary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(TEXT);b.setBackground(bg(Color.WHITE,12,BORDER));return b;}
    private View navigation(){LinearLayout n=new LinearLayout(this);Button direct=secondary("直连解析"),local=secondary("本地处理"),settings=primary("API 设置");n.addView(direct,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMarginStart(dp(6));n.addView(local,p);LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(0,dp(44),1);q.setMarginStart(dp(6));n.addView(settings,q);direct.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));local.setOnClickListener(v->startActivity(new Intent(this,LocalMediaActivity.class)));LinearLayout.LayoutParams o=new LinearLayout.LayoutParams(-1,-2);o.bottomMargin=dp(16);n.setLayoutParams(o);return n;}
    private TextView text(String s,int z,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(bold)v.setTypeface(v.getTypeface(),Typeface.BOLD);return v;}private GradientDrawable bg(int c,int r,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));d.setStroke(dp(1),stroke);return d;}private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private interface Call{GeminiKeyValidator.Result run();}private interface Action{void run(Button button);}
}
