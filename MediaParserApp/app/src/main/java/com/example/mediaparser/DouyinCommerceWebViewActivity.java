package com.example.mediaparser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.example.mediaparser.net.HttpClient;
import com.example.mediaparser.parser.DouyinCommerceData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Uses the product page itself to create Douyin's short-lived request signature. */
public final class DouyinCommerceWebViewActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_RESULT_JSON = "result_json";
    private static final String API_PATH = "/aweme/v2/shop/promotion/pack/h5/";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private WebView webView;
    private TextView status;
    private ProgressBar progress;
    private String sourceUrl = "";
    private String currentPageUrl = "";
    private String userAgent = "";
    private volatile boolean requesting;
    private volatile boolean finished;
    private int autoPlayAttempts;
    private final Set<String> sniffedVideos = new LinkedHashSet<>();
    private String productCover = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        sourceUrl = getIntent().getStringExtra(EXTRA_URL);
        if (sourceUrl == null) sourceUrl = "";
        setContentView(buildUi());
        configure();
        status.setText("正在打开抖音商城商品页…");
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
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.setMarginStart(dp(10));
        top.addView(status, sp);
        root.addView(top);

        Button retry = new Button(this);
        retry.setText("重新读取商品媒体");
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> {
            requesting = false;
            autoPlayAttempts = 0;
            status.setText("正在读取商品视频…");
            tryAutoPlay();
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        rp.topMargin = dp(8);
        root.addView(retry, rp);

        TextView hint = new TextView(this);
        hint.setText("页面只在本机打开，用于取得抖音商城临时签名。若出现验证，请在下面完成后点“重新读取商品媒体”。");
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

    @SuppressWarnings({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configure() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        userAgent = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36";
        s.setUserAgentString(userAgent);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new Bridge(), "MediaParserBridge");
        // Unlike onPageStarted/evaluateJavascript, this runs before any page script. It
        // prevents a warm WebView cache from winning the race on fast physical phones.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, HOOK_JS, Collections.singleton("*"));
        }
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                if (!finished) status.setText("抖音商城页面加载中… " + value + "%");
                if (value < 60) installHook();
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !("http".equals(scheme) || "https".equals(scheme));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                super.onPageStarted(view, url, icon);
                currentPageUrl = url == null ? sourceUrl : url;
                installHook();
            }
            @Override public void onLoadResource(WebView view, String url) {
                super.onLoadResource(view, url);
                if (isProductVideoUrl(url)) {
                    sniffedVideos.add(url.replaceFirst("^http:", "https:"));
                    finishFromPageMedia();
                    return;
                }
                if (productCover.isBlank() && isProductImageUrl(url)) {
                    productCover = url.replaceFirst("^http:", "https:");
                }
                if (url != null && url.contains(API_PATH) && url.contains("a_bogus=")) {
                    status.setText("已取得商品页签名，正在读取公开媒体…");
                    // Current Douyin pages may execute the request inside a WebWorker, which
                    // is outside the page JS hook. Recreate its documented H5 form from the
                    // final product URL and immediately reuse the page's short-lived signature.
                    if (!requesting && isSignedPackUrl(url)) {
                        String form = buildPackForm(currentPageUrl);
                        if (!form.isBlank()) {
                            requesting = true;
                            executor.execute(() -> requestPack(url, form));
                        }
                    }
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                currentPageUrl = url == null ? sourceUrl : url;
                installHook();
                if (!finished && !requesting) status.setText("商品页已打开，等待商品视频数据…");
                // The response itself may live in a WebWorker. Starting the already-visible
                // public product video makes WebView request its real playback URL, which
                // onLoadResource can capture without reading Worker internals.
                autoPlayAttempts = 0;
                main.postDelayed(DouyinCommerceWebViewActivity.this::tryAutoPlay, 350);
            }
        });
    }

    /**
     * Douyin serves more than one product-page layout.  Some Xiaomi WebView builds
     * receive a different play-button class from the emulator, and a single early
     * click is also easily lost while the carousel is still hydrating.  Retry a
     * bounded number of times and click the visible media surface as a layout-
     * independent fallback.  Loading the video is what exposes its public VOD URL
     * to onLoadResource; no purchase/login action is performed.
     */
    private void tryAutoPlay() {
        if (finished || webView == null || autoPlayAttempts >= 20) return;
        autoPlayAttempts++;
        webView.evaluateJavascript(AUTO_PLAY_JS, value -> {
            if (finished) return;
            performNativeTap(value);
            if (autoPlayAttempts == 1) status.setText("商品页已打开，正在自动读取商品视频…");
            main.postDelayed(this::tryAutoPlay, 500);
        });
    }

    private void performNativeTap(String jsValue) {
        try {
            // evaluateJavascript JSON-quotes returned strings.  Parsing through a
            // one-element JSONArray safely removes that layer without ad-hoc escaping.
            String point = new JSONArray("[" + jsValue + "]").optString(0, "");
            String[] values = point.split(",");
            if (values.length != 3 || webView.getWidth() <= 0) return;
            float cssX = Float.parseFloat(values[0]);
            float cssY = Float.parseFloat(values[1]);
            float cssWidth = Float.parseFloat(values[2]);
            if (cssWidth <= 0 || cssX < 0 || cssY < 0) return;
            float scale = webView.getWidth() / cssWidth;
            float x = Math.min(webView.getWidth() - 1f, cssX * scale);
            float y = Math.min(webView.getHeight() - 1f, cssY * scale);
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 45, MotionEvent.ACTION_UP, x, y, 0);
            webView.dispatchTouchEvent(down);
            webView.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        } catch (Exception ignored) {}
    }

    private void installHook() {
        if (webView == null) return;
        webView.evaluateJavascript(HOOK_JS, null);
    }

    private final class Bridge {
        @JavascriptInterface public void capture(String url, String body) {
            if (finished || requesting || url == null || body == null) return;
            if (!isSignedPackUrl(url)) return;
            if (body.length() < 20 || body.length() > 200_000) return;
            requesting = true;
            main.post(() -> status.setText("签名有效，正在提取商品主视频和图片…"));
            executor.execute(() -> requestPack(url, body));
        }

        @JavascriptInterface public void response(String url, String responseBody) {
            if (finished || !isSignedPackUrl(url) || responseBody == null
                    || responseBody.length() < 20 || responseBody.length() > 2_000_000) return;
            executor.execute(() -> {
                try {
                    String page = currentPageUrl == null || currentPageUrl.isBlank() ? sourceUrl : currentPageUrl;
                    finishWithResult(DouyinCommerceData.parse(responseBody, page));
                } catch (Exception ignored) {
                    // The network replay remains as a fallback for partial/non-JSON responses.
                }
            });
        }
    }

    private void requestPack(String signedUrl, String body) {
        try {
            String page = currentPageUrl;
            if (page == null || page.isBlank()) page = sourceUrl;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", userAgent.isBlank() ? HttpClient.MOBILE_UA : userAgent);
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            headers.put("Origin", "https://haohuo.jinritemai.com");
            headers.put("Referer", page);
            // Do not call CookieManager from this worker thread: some Xiaomi/WebView
            // builds serialize it onto the UI thread and can deadlock. This public H5
            // endpoint is anonymous; the short-lived a_bogus signature is sufficient.
            HttpClient.Response response = HttpClient.post(signedUrl, headers, body);
            if (!response.ok()) throw new IllegalStateException("商品接口 HTTP " + response.status);
            finishWithResult(DouyinCommerceData.parse(response.body, page));
        } catch (Exception e) {
            if (finished) return;
            requesting = false;
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            main.post(() -> {
                progress.setVisibility(ProgressBar.GONE);
                status.setText("商品媒体读取失败：" + message + "。可重新加载，若页面要求验证请先完成验证。");
            });
        }
    }

    private void finishWithResult(JSONObject result) {
        if (finished) return;
        finished = true;
        main.post(() -> {
            progress.setVisibility(ProgressBar.GONE);
            status.setText("商品媒体提取成功，正在返回…");
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT_JSON, result.toString());
            setResult(RESULT_OK, data);
            main.postDelayed(DouyinCommerceWebViewActivity.this::finish, 120);
        });
    }

    private void finishFromPageMedia() {
        if (finished || sniffedVideos.isEmpty()) return;
        try {
            Uri page = Uri.parse(currentPageUrl == null ? sourceUrl : currentPageUrl);
            String title = "抖音商城商品";
            String cover = productCover;
            try {
                JSONObject goods = new JSONObject(qp(page, "goods_detail"));
                title = nonBlank(goods.optString("name", ""), goods.optString("title", title));
                cover = nonBlank(goods.optString("cover", ""), nonBlank(goods.optString("cover_url", ""), cover));
            } catch (Exception ignored) {}
            JSONArray media = new JSONArray();
            JSONObject video = new JSONObject();
            video.put("type", "video");
            video.put("label", "商品主视频");
            video.put("url", sniffedVideos.iterator().next());
            media.put(video);
            if (cover != null && cover.startsWith("http")) {
                JSONObject image = new JSONObject();
                image.put("type", "image");
                image.put("label", "商品封面");
                image.put("url", cover.replaceFirst("^http:", "https:"));
                media.put(image);
            }
            JSONObject out = new JSONObject();
            out.put("platform", "抖音商城");
            out.put("sourceUrl", currentPageUrl);
            out.put("title", title);
            out.put("author", "");
            out.put("description", title);
            out.put("coverUrl", cover == null ? "" : cover);
            out.put("media", media);
            finishWithResult(out);
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        finished = true;
        executor.shutdownNow();
        if (webView != null) { webView.stopLoading(); webView.removeJavascriptInterface("MediaParserBridge"); webView.destroy(); }
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static boolean isSignedPackUrl(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            return "https".equals(uri.getScheme())
                    && (host.equals("ecombdapi.com") || host.endsWith(".ecombdapi.com"))
                    && API_PATH.equals(uri.getPath())
                    && raw.contains("a_bogus=");
        } catch (Exception e) {
            return false;
        }
    }

    static String buildPackForm(String pageUrl) {
        try {
            Uri page = Uri.parse(pageUrl == null ? "" : pageUrl);
            String productId = qp(page, "product_id");
            String promotionId = qp(page, "promotion_id");
            String authorId = qp(page, "kol_id");
            String meta = qp(page, "meta_params");
            String additions = qp(page, "request_additions");
            String ecS = qp(page, "ec_s");
            if (productId.isBlank() || promotionId.isBlank() || meta.isBlank() || additions.isBlank()) return "";

            String sourceMethod = "open_url", carrierSource = "xtab_homepage_toufang";
            try {
                JSONObject metaObj = new JSONObject(meta);
                JSONObject entrance = new JSONObject(metaObj.optString("entrance_info", "{}"));
                sourceMethod = entrance.optString("source_method", sourceMethod);
                carrierSource = entrance.optString("carrier_source", carrierSource);
            } catch (Exception ignored) {}

            String ui = "{" +
                    "\"source_page\":\"copy\"," +
                    "\"from_live\":false," +
                    "\"from_video\":null," +
                    "\"source_method\":" + JSONObject.quote(sourceMethod) + "," +
                    "\"carrier_source\":" + JSONObject.quote(carrierSource) + "," +
                    "\"three_d_log_data\":null," +
                    "\"follow_status\":null," +
                    "\"which_account\":null," +
                    "\"ad_log_extra\":null," +
                    "\"from_group_id\":null," +
                    "\"bolt_param\":null," +
                    "\"transition_tracker_data\":null," +
                    "\"request_additions\":" + JSONObject.quote(additions) + "," +
                    "\"selected_ids\":null," +
                    "\"window_reposition\":null," +
                    "\"is_short_screen\":null," +
                    "\"full_mode\":true}";

            JSONObject additionsObj = new JSONObject(additions);
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            fields.put("ui_params", ui);
            fields.put("use_new_price", "1");
            fields.put("is_h5", "1");
            fields.put("bff_type", "2");
            fields.put("is_in_app", "0");
            fields.put("origin_type", nonBlank(qp(page, "h5_origin_type"), "detail_share_v2"));
            fields.put("promotion_ids", productId);
            fields.put("item_id", nonBlank(qp(page, "item_id"), "0"));
            fields.put("meta_param", meta);
            fields.put("source_page", "copy");
            fields.put("request_additions", additions);
            fields.put("author_id", authorId);
            fields.put("isFromVideo", "false");
            fields.put("ec_s", ecS);
            fields.put("ec_promotion_id", promotionId);
            fields.put("enter_from", "copy");
            fields.put("enable_timing", "true");
            fields.put("from_internal_feed", additionsObj.optString("from_internal_feed", "false"));
            fields.put("cps_track", additionsObj.optString("cps_track", ""));
            fields.put("marketing_channel", additionsObj.optString("marketing_channel", ""));
            fields.put("ecom_scene_id", additionsObj.optString("ecom_scene_id", "1099,1031"));
            fields.put("is_new_h5_bff", "1");
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (out.length() > 0) out.append('&');
                out.append(enc(field.getKey())).append('=').append(enc(field.getValue()));
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String qp(Uri uri, String key) {
        String value = uri.getQueryParameter(key);
        return value == null ? "" : value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return value == null ? "" : value;
        }
    }

    private static boolean isProductVideoUrl(String raw) {
        if (raw == null) return false;
        try {
            Uri uri = Uri.parse(raw);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            return (host.contains("douyinvod.com") || host.contains("bytevcloud.com"))
                    && !raw.toLowerCase().contains(".js");
        } catch (Exception e) { return false; }
    }

    private static boolean isProductImageUrl(String raw) {
        if (raw == null) return false;
        String lower = raw.toLowerCase();
        return lower.contains("item.ecombdimg.com/img/ecom-shop-material/")
                && (lower.contains("jpeg_") || lower.contains("png_") || lower.contains("webp"));
    }

    private static final String HOOK_JS = "(function(){try{" +
            "if(window.__mediaParserCommerceHook)return;window.__mediaParserCommerceHook=1;" +
            "function ok(u){u=String(u||'');return u.indexOf('/aweme/v2/shop/promotion/pack/h5/')>=0&&u.indexOf('a_bogus=')>=0;}" +
            "function hit(u,b){try{u=String(u||'');b=typeof b==='string'?b:'';if(ok(u)&&b)MediaParserBridge.capture(u,b);}catch(e){}}" +
            "function answer(u,b){try{if(ok(u)&&typeof b==='string'&&b)MediaParserBridge.response(String(u),b);}catch(e){}}" +
            "var f=window.fetch;if(f)window.fetch=function(i,n){var u=typeof i==='string'?i:(i&&i.url);try{hit(u,n&&n.body);}catch(e){}var p=f.apply(this,arguments);try{if(ok(u))p.then(function(r){try{r.clone().text().then(function(t){answer(u,t);});}catch(e){}});}catch(e){}return p;};" +
            "var X=window.XMLHttpRequest;if(X){var o=X.prototype.open,s=X.prototype.send;X.prototype.open=function(m,u){this.__mpu=u;return o.apply(this,arguments);};X.prototype.send=function(b){var x=this,u=this.__mpu;hit(u,b);if(ok(u))this.addEventListener('load',function(){var t='';try{t=x.responseText||'';}catch(e){}try{if(!t&&x.response)t=typeof x.response==='string'?x.response:JSON.stringify(x.response);}catch(e){}answer(u,t);});return s.apply(this,arguments);};}" +
            "}catch(e){}})();";

    private static final String AUTO_PLAY_JS = "(function(){try{" +
            "function point(e,f){if(!e)return '';var r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height*(f||.5);try{e.click();}catch(z){}return [x,y,innerWidth].join(',');}" +
            "var v=document.querySelector('video');if(v){try{v.muted=true;v.play();}catch(x){}return point(v,.5);}" +
            "var ss=['.head-figure__media-view__video__play-icon','.head-figure__media-view__video','[class*=play-icon]','[class*=playIcon]','[class*=video-play]','[class*=videoPlay]','[aria-label*=播放]'];" +
            "for(var i=0;i<ss.length;i++){var e=document.querySelector(ss[i]);if(e)return point(e,.5);}" +
            "var root=document.querySelector('[class*=head-figure]')||document.querySelector('[class*=media-view]');" +
            "if(root)return point(root,.5);" +
            "var imgs=[].slice.call(document.images||[]).filter(function(e){var r=e.getBoundingClientRect();return r.width>innerWidth*.55&&r.height>180&&r.top<innerHeight&&r.bottom>0;}).sort(function(a,b){var x=a.getBoundingClientRect(),y=b.getBoundingClientRect();return y.width*y.height-x.width*x.height;});" +
            "if(imgs.length)return point(imgs[0],.5);" +
            "return '';}catch(x){return '';}})()";
}
