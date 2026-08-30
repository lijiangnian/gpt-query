package com.example.mediaparser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** On-device fallback for Zhihu login walls. Cookies remain inside Android WebView. */
public final class ZhihuWebViewActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_RESULT_JSON = "result_json";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> sniffed = new LinkedHashSet<>();
    private WebView web;
    private TextView status;
    private ProgressBar progress;
    private int attempts;
    private boolean done;
    private String source;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        source = getIntent().getStringExtra(EXTRA_URL);
        if (source == null) source = "";
        setContentView(buildUi());
        configure();
        web.loadUrl(source);
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(Color.WHITE);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this);
        top.addView(progress, new LinearLayout.LayoutParams(dp(28), dp(28)));
        status = new TextView(this);
        status.setText("正在打开知乎页面…");
        status.setTextSize(14);
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        st.setMarginStart(dp(10));
        top.addView(status, st);
        root.addView(top);
        Button retry = new Button(this);
        retry.setText("重新提取正文 / 视频");
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> { attempts = 0; extractAfter(200); });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        rp.topMargin = dp(8);
        root.addView(retry, rp);
        TextView hint = new TextView(this);
        hint.setText("若知乎要求验证或登录，可在下方完成后点“重新提取”。Cookie 只保存在本机，不会上传给解析服务。");
        hint.setTextSize(12);
        hint.setTextColor(Color.rgb(103, 112, 126));
        root.addView(hint);
        web = new WebView(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wp.topMargin = dp(8);
        root.addView(web, wp);
        return root;
    }

    @SuppressWarnings("SetJavaScriptEnabled") private void configure() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/144 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { if (!done) status.setText("知乎页面加载中… " + p + "%"); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                return !("http".equals(scheme) || "https".equals(scheme));
            }
            @Override public void onLoadResource(WebView v, String u) { if (isMedia(u)) sniffed.add(u.replaceFirst("^http:", "https:")); }
            @Override public void onPageFinished(WebView v, String u) { if (!done) { status.setText("页面已打开，正在读取正文和媒体…"); extractAfter(500); } }
        });
    }

    private void extractAfter(long ms) { handler.postDelayed(this::extract, ms); }
    private void extract() {
        if (done) return;
        attempts++;
        web.evaluateJavascript(JS, value -> {
            try {
                String raw = decode(value);
                JSONObject obj = raw.isBlank() ? new JSONObject() : new JSONObject(raw);
                JSONArray media = obj.optJSONArray("media");
                if (media == null) media = new JSONArray();
                for (String u : sniffed) if (!contains(media, u)) {
                    JSONObject m = new JSONObject();
                    m.put("type", "video"); m.put("label", "知乎视频"); m.put("url", u); media.put(m);
                }
                obj.put("media", media);
                obj.put("sourceUrl", web.getUrl() == null ? source : web.getUrl());
                String currentUrl = web.getUrl() == null ? source : web.getUrl();
                boolean bodyPage = isBodyPage(source) || isBodyPage(currentUrl);
                String content = obj.optString("contentText", "").trim();
                boolean realContent = content.length() >= 20 && !isGenericSiteText(content);
                if (!realContent) obj.put("contentText", "");
                boolean hasVideo=false;for(int i=0;i<media.length();i++){JSONObject m=media.optJSONObject(i);if(m!=null&&"video".equals(m.optString("type"))){hasVideo=true;break;}}
                if (hasVideo || realContent || (!bodyPage && !obj.optString("title", "").isBlank())) {
                    done = true;
                    progress.setVisibility(ProgressBar.GONE);
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_JSON, obj.toString()));
                    handler.postDelayed(this::finish, 120);
                    return;
                }
            } catch (Exception ignored) {}
            if (attempts < 6) {
                status.setText("等待知乎正文/视频加载…（" + attempts + "/6）");
                extractAfter(1000);
            } else {
                progress.setVisibility(ProgressBar.GONE);
                status.setText("暂未读取到内容；请完成验证/登录后点“重新提取”。");
            }
        });
    }

    private static boolean isMedia(String u) { if (u == null) return false; String l=u.toLowerCase(); return l.startsWith("http") && (l.contains(".mp4") || l.contains(".m3u8")); }
    private static boolean isGenericSiteText(String s){return s.contains("中文互联网高质量的问答社区")||s.contains("让人们更好地分享知识、经验和见解")||s.contains("知识分享社区和创作者聚集的原创内容平台");}
    private static boolean isBodyPage(String u){return u!=null&&(u.contains("/answer/")||u.contains("zhuanlan.zhihu.com/p/")||u.matches(".*zhihu\\.com/(?:p|pin)/.*"));}
    private static boolean contains(JSONArray a, String u) { for (int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&u.equals(o.optString("url")))return true;}return false; }
    private static String decode(String v) throws Exception { if(v==null||"null".equals(v))return"";if(v.startsWith("\"")&&v.endsWith("\""))return new JSONArray("["+v+"]").getString(0);return v; }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private static final String JS = "(function(){try{" +
            "var o={title:'',author:'',description:'',contentText:'',coverUrl:'',media:[]};" +
            "function q(s){var e=document.querySelector(s);return e?(e.innerText||e.textContent||'').trim():'';}" +
            "function meta(n){var e=document.querySelector('meta[property=\\\"'+n+'\\\"],meta[name=\\\"'+n+'\\\"]');return e?e.content||'':'';}" +
            "o.title=q('h1.QuestionHeader-title,h1.Post-Title')||meta('og:title')||document.title;" +
            "o.author=q('.AuthorInfo-name,.Post-Author .UserLink-link,.ContentItem-meta .UserLink-link');o.description=meta('og:description')||meta('description');if(/中文互联网高质量的问答社区|让人们更好地分享知识、经验和见解|知识分享社区和创作者聚集的原创内容平台/.test(o.description))o.description='';o.coverUrl=meta('og:image');" +
            "var blocks=document.querySelectorAll('.RichContent-inner,.Post-RichTextContainer,article'),best='';for(var i=0;i<blocks.length;i++){var t=(blocks[i].innerText||'').trim();if(t.length>best.length)best=t;}o.contentText=best||o.description;" +
            "var seen={};function add(t,l,u){if(!u||typeof u!=='string'||u.indexOf('http')!==0||seen[u])return;seen[u]=1;o.media.push({type:t,label:l,url:u.replace(/^http:/,'https:')});}" +
            "document.querySelectorAll('video,video source').forEach(function(v){add('video','知乎视频',v.currentSrc||v.src||'');});" +
            "document.querySelectorAll('.RichContent-inner img,.Post-RichTextContainer img').forEach(function(v,i){add('image','知乎图片 '+(i+1),v.currentSrc||v.src||'');});" +
            "if(window.performance&&performance.getEntriesByType)performance.getEntriesByType('resource').forEach(function(e){if(/\\.(mp4|m3u8)(\\?|$)/i.test(e.name))add('video','知乎视频',e.name);});" +
            "return JSON.stringify(o);}catch(e){return JSON.stringify({error:String(e),media:[]});}})();";
}
