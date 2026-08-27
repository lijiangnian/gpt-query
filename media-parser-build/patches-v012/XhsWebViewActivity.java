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

/**
 * Browser fallback for Xiaohongshu. It intentionally uses Android WebView instead of
 * another remote parsing service so share tokens, browser JS and page-loaded media
 * stay on the device. The page is shown only when the fast HTTP parser cannot obtain media.
 */
public final class XhsWebViewActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_RESULT_JSON = "result_json";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> sniffedVideos = new LinkedHashSet<>();
    private WebView webView;
    private TextView status;
    private ProgressBar progress;
    private int attempts;
    private boolean finished;
    private String sourceUrl = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sourceUrl = getIntent().getStringExtra(EXTRA_URL);
        if (sourceUrl == null) sourceUrl = "";
        if (sourceUrl.startsWith("http://xhslink.com") || sourceUrl.startsWith("http://xhslink.cn")) {
            sourceUrl = "https://" + sourceUrl.substring("http://".length());
        }
        setContentView(buildUi());
        configureWebView();
        status.setText("正在用浏览器兼容模式读取小红书页面…");
        webView.loadUrl(sourceUrl);
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
        status.setTextSize(14);
        status.setTextColor(Color.rgb(60, 65, 75));
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        st.setMarginStart(dp(10));
        top.addView(status, st);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button retry = new Button(this);
        retry.setText("重新提取");
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> {
            attempts = 0;
            status.setText("重新读取页面媒体…");
            extractAfter(250);
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        rp.topMargin = dp(8);
        root.addView(retry, rp);

        TextView hint = new TextView(this);
        hint.setText("如果小红书要求验证或登录，可直接在下面完成，然后点“重新提取”。成功后会自动返回解析结果。");
        hint.setTextSize(12);
        hint.setTextColor(Color.rgb(105, 112, 126));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(6);
        root.addView(hint, hp);

        webView = new WebView(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wp.topMargin = dp(8);
        root.addView(webView, wp);
        return root;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36");
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                if (!finished) status.setText("小红书页面加载中… " + newProgress + "%");
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                return true;
            }

            @Override public void onLoadResource(WebView view, String url) {
                super.onLoadResource(view, url);
                if (url == null) return;
                String l = url.toLowerCase();
                if ((l.contains("xhscdn.com") && (l.contains("sns-video") || l.contains(".mp4") || l.contains("video"))) || l.endsWith(".mp4")) {
                    sniffedVideos.add(url.replaceFirst("^http:", "https:"));
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (finished) return;
                status.setText("页面已打开，正在提取原图/视频/Live Photo…");
                extractAfter(450);
            }
        });
    }

    private void extractAfter(long delay) {
        handler.postDelayed(this::extract, delay);
    }

    private void extract() {
        if (finished) return;
        attempts++;
        webView.evaluateJavascript(EXTRACT_JS, value -> {
            if (finished) return;
            try {
                String decoded = decodeJavascriptString(value);
                JSONObject obj = decoded.isBlank() ? new JSONObject() : new JSONObject(decoded);
                JSONArray media = obj.optJSONArray("media");
                if (media == null) media = new JSONArray();

                // Add media observed by WebView's actual network requests. This catches blob-backed videos.
                for (String u : sniffedVideos) {
                    if (!containsUrl(media, u)) {
                        JSONObject m = new JSONObject();
                        m.put("type", "video");
                        m.put("label", "小红书视频");
                        m.put("url", u);
                        media.put(m);
                    }
                }
                obj.put("media", media);
                obj.put("sourceUrl", webView.getUrl() == null ? sourceUrl : webView.getUrl());

                if (media.length() > 0) {
                    finished = true;
                    progress.setVisibility(ProgressBar.GONE);
                    status.setText("解析成功，正在返回…");
                    Intent data = new Intent();
                    data.putExtra(EXTRA_RESULT_JSON, obj.toString());
                    setResult(RESULT_OK, data);
                    handler.postDelayed(this::finish, 120);
                    return;
                }
            } catch (Exception ignored) {}

            if (attempts < 5) {
                status.setText("等待小红书媒体加载…（" + attempts + "/5）");
                extractAfter(1200);
            } else {
                progress.setVisibility(ProgressBar.GONE);
                status.setText("暂未抓到媒体。若页面显示验证/登录，请完成后点“重新提取”。");
            }
        });
    }

    private static boolean containsUrl(JSONArray arr, String url) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null && url.equals(m.optString("url"))) return true;
        }
        return false;
    }

    private static String decodeJavascriptString(String value) throws Exception {
        if (value == null || "null".equals(value)) return "";
        if (value.startsWith("\"") && value.endsWith("\"")) {
            JSONArray a = new JSONArray("[" + value + "]");
            return a.getString(0);
        }
        return value;
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static final String EXTRACT_JS = "" +
            "(function(){try{" +
            "var out={platform:'小红书',title:'',author:'',description:'',coverUrl:'',media:[]};" +
            "var seen={};function add(t,l,u){if(!u||typeof u!=='string')return;u=u.replace(/^http:/,'https:');if(u.indexOf('blob:')===0||u.indexOf('data:')===0||seen[u])return;seen[u]=1;out.media.push({type:t,label:l,url:u});}" +
            "function noteOf(s){if(!s)return null;var p=location.pathname.split('/').filter(Boolean),id='';for(var i=0;i<p.length-1;i++){if(p[i]==='explore'||p[i]==='item'){id=p[i+1];break;}}var n=s.note||{},m=n.noteDetailMap||{};var e=(id&&m[id])||(n.currentNoteId&&m[n.currentNoteId]);if(e)return e.note||e;for(var k in m){if(m[k])return m[k].note||m[k];}if(s.noteData&&s.noteData.data&&s.noteData.data.noteData)return s.noteData.data.noteData;if(s.noteData&&s.noteData.noteData)return s.noteData.noteData;return null;}" +
            "function bestStream(st){if(!st)return '';var best='',score=-1;for(var k in st){var a=st[k];if(!Array.isArray(a))continue;for(var i=0;i<a.length;i++){var x=a[i]||{},u=(x.backupUrls&&x.backupUrls[0])||x.masterUrl||'',h=Number(x.height||0),b=Number(x.videoBitrate||x.avgBitrate||0),sc=h*1000000+Math.min(b,999999);if(u&&sc>=score){best=u;score=sc;}}}return best;}" +
            "var s=window.__INITIAL_STATE__||null,n=noteOf(s);" +
            "if(n){out.title=n.title||'';out.description=n.desc||n.description||'';var user=n.user||{};out.author=user.nickName||user.nickname||user.name||'';var imgs=n.imageList||[];" +
            "var origin=n.video&&n.video.consumer&&n.video.consumer.originVideoKey;if(origin){add('video','小红书视频',origin.indexOf('http')===0?origin:'https://sns-video-bd.xhscdn.com/'+origin.replace(/^\\/+/,''));}else{var vu=bestStream(n.video&&n.video.media&&n.video.media.stream);if(vu)add('video','小红书视频',vu);}" +
            "for(var i=0;i<imgs.length;i++){var im=imgs[i]||{},u=im.urlDefault||im.urlPre||im.url||((im.infoList&&im.infoList.length)?im.infoList[im.infoList.length-1].url:'')||'';if(u){u=u.replace(/!.*/,'');if(!out.coverUrl)out.coverUrl=u;add('image','图片 '+(i+1),u);}var lv=bestStream(im.stream);if(lv)add('video','Live Photo '+(i+1),lv);}" +
            "}" +
            "var domImgs=document.querySelectorAll('.note-image-box img,.media-container img');for(var j=0;j<domImgs.length;j++){var du=domImgs[j].currentSrc||domImgs[j].src||'';if(du&&du.indexOf('xhscdn')>=0)add('image','页面图片 '+(j+1),du.replace(/!.*/,''));}" +
            "var vids=document.querySelectorAll('video,video source');for(var q=0;q<vids.length;q++){var su=vids[q].src||vids[q].currentSrc||'';if(su&&su.indexOf('http')===0)add('video','小红书视频',su);}" +
            "return JSON.stringify(out);" +
            "}catch(e){return JSON.stringify({media:[],error:String(e)});}})();";
}
