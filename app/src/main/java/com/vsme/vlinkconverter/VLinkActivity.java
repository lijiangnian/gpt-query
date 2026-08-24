package com.vsme.vlinkconverter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

public class VLinkActivity extends Activity {
    private static final int BG = Color.rgb(10, 11, 14);
    private static final int CARD = Color.rgb(27, 29, 35);
    private static final int CARD2 = Color.rgb(36, 39, 47);
    private static final int TEXT = Color.rgb(245, 246, 248);
    private static final int MUTED = Color.rgb(161, 168, 182);
    private static final int BLUE = Color.rgb(63, 133, 255);
    private static final int PURPLE = Color.rgb(139, 92, 246);
    private static final int GREEN = Color.rgb(69, 210, 145);
    private static final int AMBER = Color.rgb(255, 188, 82);
    private static final int RED = Color.rgb(255, 105, 105);

    private static final int NONE = -1;
    private static final int V2RAYNG = 0;
    private static final int HIDDIFY = 1;
    private static final int XRAY = 2;
    private static final int FLCLASH = 10;
    private static final int CMFA = 11;
    private static final int OTHER_CLASH = 12;

    private EditText input;
    private TextView status, summary, resultTitle, resultHint;
    private LinearLayout clientPanel, resultPanel;
    private EditText resultText;
    private Button copyButton, qrButton, openButton, configButton;
    private int mode = NONE;
    private VlessNode node;
    private String yaml = "";
    private LocalProfileServer server;

    @Override protected void onCreate(Bundle b) { super.onCreate(b); setContentView(buildUi()); }
    @Override protected void onDestroy() { if (server != null) server.stop(); super.onDestroy(); }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        scroll.addView(root);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("V", 28, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(gradient(BLUE, PURPLE, 18));
        head.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(0, -2, 1f);
        wordsLp.leftMargin = dp(14);
        head.addView(words, wordsLp);
        words.addView(text("VLink", 28, true));
        TextView sub = text("VLESS 节点导入助手", 13, false); sub.setTextColor(MUTED); words.addView(sub);
        root.addView(head);

        TextView privacy = text("● 本地解析 · URL 优先 · 节点不上传", 12, false);
        privacy.setTextColor(GREEN);
        LinearLayout.LayoutParams privacyLp = lp(); privacyLp.topMargin = dp(12); root.addView(privacy, privacyLp);

        LinearLayout inputCard = card();
        LinearLayout.LayoutParams inputCardLp = lp(); inputCardLp.topMargin = dp(18); root.addView(inputCard, inputCardLp);
        inputCard.addView(text("粘贴 VLESS 链接", 16, true));
        TextView inputDesc = text("支持 Reality / TLS / TCP / WS / gRPC / XHTTP 常见参数", 12, false);
        inputDesc.setTextColor(MUTED); inputCard.addView(inputDesc);
        input = new EditText(this);
        input.setHint("vless://..."); input.setHintTextColor(Color.rgb(105,112,126)); input.setTextColor(TEXT); input.setTextSize(12);
        input.setMinLines(4); input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setPadding(dp(14), dp(12), dp(14), dp(12)); input.setBackground(round(CARD2, 16));
        LinearLayout.LayoutParams inLp = lp(); inLp.topMargin = dp(10); inputCard.addView(input, inLp);

        LinearLayout parseRow = new LinearLayout(this);
        LinearLayout.LayoutParams prLp = lp(); prLp.topMargin = dp(10); inputCard.addView(parseRow, prLp);
        Button paste = button("粘贴并识别", true); paste.setOnClickListener(v -> pasteAndParse()); parseRow.addView(paste, weight());
        Button parse = button("识别", false); parse.setOnClickListener(v -> parse()); LinearLayout.LayoutParams pLp = weight(); pLp.leftMargin = dp(8); parseRow.addView(parse, pLp);
        Button clear = button("清空", false); clear.setOnClickListener(v -> clear()); LinearLayout.LayoutParams cLp = weight(); cLp.leftMargin = dp(8); parseRow.addView(clear, cLp);
        status = text("等待输入", 13, false); status.setTextColor(MUTED); LinearLayout.LayoutParams stLp = lp(); stLp.topMargin = dp(9); inputCard.addView(status, stLp);
        summary = text("", 12, false); summary.setTextColor(Color.rgb(205,211,220)); summary.setVisibility(View.GONE); inputCard.addView(summary);

        clientPanel = new LinearLayout(this); clientPanel.setOrientation(LinearLayout.VERTICAL); clientPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams cpLp = lp(); cpLp.topMargin = dp(14); root.addView(clientPanel, cpLp); buildClients();

        resultPanel = card(); resultPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams rpLp = lp(); rpLp.topMargin = dp(14); root.addView(resultPanel, rpLp);
        resultTitle = text("导入结果", 16, true); resultPanel.addView(resultTitle);
        resultHint = text("", 12, false); resultHint.setTextColor(MUTED); resultPanel.addView(resultHint);
        resultText = new EditText(this); resultText.setTextColor(TEXT); resultText.setTextSize(12); resultText.setMinLines(4);
        resultText.setGravity(Gravity.TOP | Gravity.START); resultText.setBackground(round(Color.rgb(18,20,25), 16)); resultText.setPadding(dp(14),dp(12),dp(14),dp(12));
        resultText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams rtxtLp = lp(); rtxtLp.topMargin = dp(10); resultPanel.addView(resultText, rtxtLp);
        LinearLayout actions = new LinearLayout(this); LinearLayout.LayoutParams aLp = lp(); aLp.topMargin = dp(10); resultPanel.addView(actions, aLp);
        copyButton = button("复制", true); copyButton.setOnClickListener(v -> copyResult()); actions.addView(copyButton, weight());
        qrButton = button("二维码", false); qrButton.setOnClickListener(v -> qr()); LinearLayout.LayoutParams qLp = weight(); qLp.leftMargin = dp(8); actions.addView(qrButton, qLp);
        openButton = button("打开客户端", false); openButton.setOnClickListener(v -> openClient()); LinearLayout.LayoutParams oLp = weight(); oLp.leftMargin = dp(8); actions.addView(openButton, oLp);
        configButton = button("查看生成配置（备用）", false); configButton.setOnClickListener(v -> showConfig());
        LinearLayout.LayoutParams cfLp = lp(); cfLp.topMargin = dp(8); resultPanel.addView(configButton, cfLp);

        LinearLayout note = card(); LinearLayout.LayoutParams noteLp = lp(); noteLp.topMargin = dp(14); root.addView(note, noteLp);
        note.addView(text("兼容性说明", 14, true));
        TextView noteText = text("• v2rayNG / Hiddify / Xray：直接用原始 vless://，不转换。\n• FlClash：生成本机订阅 URL，按“配置 → ＋ → URL”导入。\n• Clash Meta for Android：生成订阅 URL，并支持官方 install-config 一键导入。\n• Reality 若在 v2rayNG 正常但 Clash 超时，可能是 Mihomo 与服务器 Xray Reality 版本兼容问题。", 12, false);
        noteText.setTextColor(MUTED); noteText.setLineSpacing(0, 1.18f); note.addView(noteText);
        return scroll;
    }

    private void buildClients() {
        LinearLayout direct = card(); clientPanel.addView(direct);
        direct.addView(text("无需转换 · 直接使用 URL", 15, true));
        TextView d = text("最稳定、最省事。", 12, false); d.setTextColor(GREEN); direct.addView(d);
        addClient(direct, "1", "v2rayNG", "Android 首选 · 直接导入 vless://", V2RAYNG, true);
        addClient(direct, "2", "Hiddify", "直接导入 vless://", HIDDIFY, false);
        addClient(direct, "3", "Xray / 其他 VLESS", "复制原始 URL 或扫码", XRAY, false);

        LinearLayout clash = card(); LinearLayout.LayoutParams clp = lp(); clp.topMargin = dp(12); clientPanel.addView(clash, clp);
        clash.addView(text("需要转换 · Clash / Mihomo", 15, true));
        TextView c = text("根据客户端实际导入方式生成订阅 URL，配置文件只做备用。", 12, false); c.setTextColor(AMBER); clash.addView(c);
        addClient(clash, "A", "FlClash", "本机订阅 URL → 配置 → ＋ → URL", FLCLASH, true);
        addClient(clash, "B", "Clash Meta for Android", "订阅 URL → 一键 install-config", CMFA, false);
        addClient(clash, "C", "其他 Clash / Mihomo", "生成本机订阅 URL", OTHER_CLASH, false);
    }

    private void addClient(LinearLayout parent, String badge, String name, String desc, int m, boolean rec) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12),dp(12),dp(12),dp(12)); row.setBackground(round(CARD2,16)); row.setOnClickListener(v -> select(m));
        TextView icon = text(badge, 13, true); icon.setGravity(Gravity.CENTER); icon.setBackground(round(m >= 10 ? PURPLE : BLUE, 12)); row.addView(icon, new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout w = new LinearLayout(this); w.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0,-2,1f); wlp.leftMargin = dp(12); row.addView(w,wlp);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); w.addView(top); top.addView(text(name,14,true));
        if (rec) { TextView r = text("推荐",10,true); r.setTextColor(Color.WHITE); r.setPadding(dp(7),dp(2),dp(7),dp(2)); r.setBackground(round(m>=10?Color.rgb(86,61,27):Color.rgb(25,66,111),10)); LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2,-2); rlp.leftMargin=dp(8); top.addView(r,rlp); }
        TextView ds = text(desc,11,false); ds.setTextColor(MUTED); w.addView(ds); TextView arrow = text("›",26,false); arrow.setTextColor(MUTED); row.addView(arrow);
        LinearLayout.LayoutParams rlp = lp(); rlp.topMargin = dp(9); parent.addView(row,rlp);
    }

    private void pasteAndParse() {
        ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip()!=null && cm.getPrimaryClip().getItemCount()>0) {
            CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(this); if (s!=null) input.setText(s.toString().trim());
        }
        parse();
    }

    private void parse() {
        try {
            node = VlessNode.parse(input.getText().toString().trim()); mode = NONE; yaml = "";
            status.setText("✓ 识别成功 · VLESS" + ("reality".equalsIgnoreCase(node.security) ? " / Reality" : "")); status.setTextColor(GREEN);
            summary.setText(node.summary()); summary.setVisibility(View.VISIBLE); clientPanel.setVisibility(View.VISIBLE); resultPanel.setVisibility(View.GONE);
            if (server != null) server.setContent("");
        } catch (Exception e) {
            node = null; mode = NONE; status.setText("✕ 识别失败 · " + e.getMessage()); status.setTextColor(RED); summary.setVisibility(View.GONE); clientPanel.setVisibility(View.GONE); resultPanel.setVisibility(View.GONE);
        }
    }

    private void select(int m) {
        if (node == null) { toast("请先识别节点"); return; }
        mode = m; resultPanel.setVisibility(View.VISIBLE);
        if (m < 10) {
            yaml = ""; if (server != null) server.setContent("");
            resultTitle.setText(m==V2RAYNG?"v2rayNG · 直接导入":m==HIDDIFY?"Hiddify · 直接导入":"Xray / 其他 VLESS · 直接导入");
            resultHint.setText("无需转换。下面就是原始 VLESS URL。"); resultHint.setTextColor(GREEN); resultText.setText(node.raw);
            copyButton.setText("复制 VLESS URL"); qrButton.setVisibility(View.VISIBLE); openButton.setText("打开客户端"); configButton.setVisibility(View.GONE); return;
        }
        yaml = node.toMihomoYaml();
        try {
            String url = localUrl(yaml); resultText.setText(url); resultHint.setTextColor(GREEN);
            if (m==FLCLASH) resultHint.setText("复制订阅 URL → 打开 FlClash → 配置 → ＋ → URL → 粘贴。");
            else if (m==CMFA) resultHint.setText("点击“一键导入”，VLink 会调用 Clash Meta 官方 install-config。");
            else resultHint.setText("在目标客户端选择 URL / Remote Profile 导入。");
            resultTitle.setText(m==FLCLASH?"FlClash · 订阅 URL":m==CMFA?"Clash Meta for Android · 订阅 URL":"Clash / Mihomo · 订阅 URL");
            copyButton.setText("复制订阅 URL"); qrButton.setVisibility(View.VISIBLE); openButton.setText(m==CMFA?"一键导入":m==FLCLASH?"打开 FlClash":"复制后导入"); openButton.setVisibility(View.VISIBLE); configButton.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            resultTitle.setText("生成配置"); resultHint.setText("本机订阅服务启动失败，已退回 YAML。" ); resultHint.setTextColor(AMBER); resultText.setText(yaml); copyButton.setText("复制 YAML"); qrButton.setVisibility(View.GONE); openButton.setVisibility(View.GONE); configButton.setVisibility(View.GONE);
        }
    }

    private String localUrl(String data) throws Exception { if (server==null) server=new LocalProfileServer(); server.setContent(data); server.startIfNeeded(); return server.getUrl(); }

    private void copyResult() { String s=resultText.getText().toString().trim(); if(s.isEmpty()){toast("没有可复制的内容");return;} copy(s); toast(mode<10?"VLESS URL 已复制":s.startsWith("http://")?"订阅 URL 已复制":"YAML 已复制"); }

    private void openClient() {
        if (node==null || mode==NONE) return;
        if (mode<10) {
            String pkg = mode==V2RAYNG?"com.v2ray.ang":mode==HIDDIFY?"app.hiddify.com":null;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(node.raw));
            if(pkg!=null && getPackageManager().getLaunchIntentForPackage(pkg)!=null){i.setPackage(pkg);try{startActivity(i);return;}catch(Exception ignored){}}
            try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW,Uri.parse(node.raw)),"选择支持 VLESS 的客户端"));}catch(Exception e){toast("请复制 URL 或使用二维码导入");} return;
        }
        String url=resultText.getText().toString().trim(); if(!url.startsWith("http://")){toast("当前没有可用订阅 URL");return;}
        if(mode==FLCLASH){copy(url); launch("com.follow.clash","未找到 FlClash；URL 已复制"); toast("FlClash → 配置 → ＋ → URL"); return;}
        if(mode==CMFA){
            try{Intent x=new Intent(Intent.ACTION_VIEW,Uri.parse("clashmeta://install-config?url="+Uri.encode(url)));x.setPackage("com.github.metacubex.clash.meta");startActivity(x);return;}catch(Exception ignored){}
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("clash://install-config?url="+Uri.encode(url))));return;}catch(Exception ignored){}
            copy(url); launch("com.github.metacubex.clash.meta","URL 已复制，请手动添加"); return;
        }
        copy(url); toast("订阅 URL 已复制，请在目标客户端选择 URL / Remote Profile");
    }

    private void showConfig() {
        if(yaml.isEmpty()){toast("当前没有生成配置");return;}
        EditText code=new EditText(this);code.setText(yaml);code.setTextSize(11);code.setTextColor(TEXT);code.setBackgroundColor(Color.rgb(20,21,25));code.setPadding(dp(12),dp(12),dp(12),dp(12));code.setMinLines(16);
        new AlertDialog.Builder(this).setTitle("Mihomo YAML（备用）").setView(code).setNegativeButton("关闭",null).setPositiveButton("复制",(d,w)->{copy(yaml);toast("YAML 已复制");}).show();
    }

    private void qr() {
        String s=resultText.getText().toString().trim();if(s.isEmpty()){toast("没有可生成二维码的内容");return;}
        try{int size=Math.min(getResources().getDisplayMetrics().widthPixels-dp(72),dp(420));BitMatrix m=new MultiFormatWriter().encode(s,BarcodeFormat.QR_CODE,size,size);Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565);for(int y=0;y<size;y++)for(int x=0;x<size;x++)b.setPixel(x,y,m.get(x,y)?Color.BLACK:Color.WHITE);ImageView iv=new ImageView(this);iv.setImageBitmap(b);iv.setPadding(dp(14),dp(14),dp(14),dp(14));new AlertDialog.Builder(this).setTitle(mode<10?"VLESS URL 二维码":"订阅 URL 二维码").setView(iv).setPositiveButton("关闭",null).show();}catch(Exception e){toast("二维码生成失败");}
    }

    private void clear(){node=null;mode=NONE;yaml="";input.setText("");status.setText("等待输入");status.setTextColor(MUTED);summary.setVisibility(View.GONE);clientPanel.setVisibility(View.GONE);resultPanel.setVisibility(View.GONE);if(server!=null)server.setContent("");}
    private void copy(String s){ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("VLink",s));}
    private void launch(String pkg,String fail){Intent i=getPackageManager().getLaunchIntentForPackage(pkg);if(i!=null)startActivity(i);else toast(fail);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    private LinearLayout card(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(15),dp(15),dp(15),dp(15));x.setBackground(round(CARD,22));return x;}
    private TextView text(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(TEXT);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(12);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(Color.WHITE);b.setMinHeight(dp(46));b.setPadding(dp(7),0,dp(7),0);b.setBackground(primary?gradient(BLUE,PURPLE,14):round(CARD2,14));return b;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private GradientDrawable gradient(int a,int b,int r){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{a,b});g.setCornerRadius(dp(r));return g;}
    private LinearLayout.LayoutParams lp(){return new LinearLayout.LayoutParams(-1,-2);} private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(46),1f);} private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
