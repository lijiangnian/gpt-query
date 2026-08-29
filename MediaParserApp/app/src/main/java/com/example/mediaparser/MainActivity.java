package com.example.mediaparser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mediaparser.core.LinkExtractor;
import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.model.ParseResult;
import com.example.mediaparser.parser.ParseException;
import com.example.mediaparser.parser.ParserRegistry;
import com.example.mediaparser.util.FileSaver;
import com.example.mediaparser.subtitle.SubtitleExtractor;
import com.example.mediaparser.subtitle.SubtitleProgress;
import com.example.mediaparser.subtitle.SubtitleOutput;
import com.example.mediaparser.subtitle.SubtitlePreview;
import com.example.mediaparser.subtitle.GeminiKeyStore;
import com.example.mediaparser.subtitle.GeminiKeyValidator;
import com.example.mediaparser.subtitle.GroqKeyStore;
import com.example.mediaparser.subtitle.GroqKeyValidator;
import com.example.mediaparser.subtitle.SubtitleProvider;
import com.example.mediaparser.subtitle.TranscriptionOptions;
import com.example.mediaparser.subtitle.LocalModelManager;
import com.example.mediaparser.subtitle.AliyunKeyStore;
import com.example.mediaparser.subtitle.AliyunKeyValidator;
import com.example.mediaparser.subtitle.AliyunSettings;
import com.example.mediaparser.subtitle.DoubaoCredentialStore;
import com.example.mediaparser.subtitle.DoubaoCredentialValidator;
import com.example.mediaparser.subtitle.VisionPackageExporter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_XHS_WEB = 4102;
    private static final int BLUE = Color.rgb(39, 100, 231);
    private static final int TEXT = Color.rgb(23, 27, 35);
    private static final int MUTED = Color.rgb(103, 112, 126);
    private static final int BORDER = Color.rgb(224, 228, 236);
    private static final int PANEL = Color.rgb(247, 249, 252);

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ParserRegistry registry = new ParserRegistry();

    private EditText input;
    private Button parseButton;
    private ProgressBar progress;
    private TextView status;
    private LinearLayout resultBox;
    private ParseResult currentResult;
    private EditText geminiKeyInput;
    private TextView geminiKeyStatus;
    private TextView geminiCompactStatus;
    private LinearLayout geminiSettingsBody;
    private Button geminiSettingsToggle;
    private boolean geminiSettingsExpanded;
    private boolean geminiKeyValidating;
    private EditText groqKeyInput;
    private TextView groqKeyStatus;
    private TextView groqCompactStatus;
    private LinearLayout groqSettingsBody;
    private Button groqSettingsToggle;
    private boolean groqSettingsExpanded;
    private boolean groqKeyValidating;
    private boolean parsing;
    private boolean subtitleBusy;
    private View subtitleResultPanel;
    private Button subtitleActionButton;
    private TextView subtitleProgressView;
    private Runnable subtitleRerun;
    private SubtitleOutput lastSubtitleOutput;
    private EditText aliyunKeyInput;
    private EditText aliyunWorkspaceInput;
    private TextView aliyunKeyStatus;
    private boolean aliyunKeyValidating;
    private EditText doubaoApiKeyInput,doubaoAppIdInput,doubaoAccessTokenInput,doubaoResourceInput;
    private TextView doubaoStatus;
    private boolean doubaoValidating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(dp(18), dp(18) + bars.top, dp(18), dp(28) + bars.bottom);
            }
            return insets;
        });

        TextView title = text("媒体解析器", 28, TEXT, true);
        root.addView(title);
        TextView sub = text("粘贴完整分享文案，或从社媒 App 直接“分享”到这里。", 15, MUTED, false);
        sub.setPadding(0, dp(6), 0, dp(12));
        root.addView(sub);

        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"抖音", "小红书", "快手", "B站", "微博"};
        for (String n : names) {
            TextView chip = text(n, 13, TEXT, false);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setBackground(roundRect(PANEL, dp(18), BORDER, 1));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMarginEnd(dp(8));
            chips.addView(chip, cp);
        }
        chipsScroll.addView(chips);
        root.addView(chipsScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildLocalMediaEntryCard());

        root.addView(buildRouteCard());

        input = new EditText(this);
        input.setTextSize(16);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(145, 151, 162));
        input.setHint("粘贴分享链接或整段分享文案\n例如：https://v.douyin.com/...");
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        input.setBackground(roundRect(Color.WHITE, dp(14), BORDER, 1));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(14);
        root.addView(input, ip);

        LinearLayout smallActions = new LinearLayout(this);
        smallActions.setOrientation(LinearLayout.HORIZONTAL);
        smallActions.setGravity(Gravity.END);
        Button paste = secondaryButton("粘贴");
        paste.setOnClickListener(v -> pasteClipboard());
        Button clear = secondaryButton("清空");
        clear.setOnClickListener(v -> {
            input.setText("");
            clearResult();
        });
        smallActions.addView(paste);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMarginStart(dp(8));
        smallActions.addView(clear, clp);
        LinearLayout.LayoutParams sap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sap.topMargin = dp(8);
        root.addView(smallActions, sap);

        parseButton = primaryButton("开始解析");
        parseButton.setOnClickListener(v -> startParse());
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        pp.topMargin = dp(10);
        root.addView(parseButton, pp);

        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        stateRow.addView(progress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        status = text("", 14, MUTED, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.setMarginStart(dp(10));
        stateRow.addView(status, sp);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.topMargin = dp(12);
        root.addView(stateRow, srp);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rbp.topMargin = dp(14);
        root.addView(resultBox, rbp);

        TextView foot = text("仅保存你有权下载和使用的内容。平台页面结构会变化，解析失败时会显示具体原因。", 12, MUTED, false);
        foot.setPadding(0, dp(22), 0, 0);
        root.addView(foot);

        root.addView(buildRecognitionCard());

        LinearLayout.LayoutParams localLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        localLp.topMargin = dp(8);
        root.addView(buildLocalModelCard(), localLp);

        LinearLayout apiSettingsSection = new LinearLayout(this);
        apiSettingsSection.setOrientation(LinearLayout.VERTICAL);
        apiSettingsSection.setPadding(0, dp(22), 0, 0);
        apiSettingsSection.addView(text("API Key 设置 · 本机加密保存", 15, TEXT, true));
        Button diagnostics=primaryButton("一键全面测试所有 API / 六引擎");
        diagnostics.setOnClickListener(v->startActivity(new Intent(this,ApiDiagnosticsActivity.class)));
        LinearLayout.LayoutParams diagnosticsLayout=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48));
        diagnosticsLayout.topMargin=dp(8);
        apiSettingsSection.addView(diagnostics,diagnosticsLayout);
        LinearLayout.LayoutParams geminiLayout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        geminiLayout.topMargin = dp(8);
        apiSettingsSection.addView(buildGeminiCard(), geminiLayout);
        LinearLayout.LayoutParams groqLayout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        groqLayout.topMargin = dp(8);
        apiSettingsSection.addView(buildGroqCard(), groqLayout);
        LinearLayout.LayoutParams aliLayout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aliLayout.topMargin = dp(8);
        apiSettingsSection.addView(buildAliyunCard(), aliLayout);
        LinearLayout.LayoutParams doubaoLayout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        doubaoLayout.topMargin = dp(8);
        apiSettingsSection.addView(buildDoubaoCard(), doubaoLayout);
        root.addView(apiSettingsSection);
        return scroll;
    }

    private View buildLocalMediaEntryCard(){
        LinearLayout card=panel();
        LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);outer.topMargin=dp(14);card.setLayoutParams(outer);
        card.addView(text("本地音视频 / 会议",17,TEXT,true));
        TextView note=text("不需要先上传社媒：相册视频、录屏、会议录音可直接转字幕、逐字稿或做六引擎横评。",12,MUTED,false);note.setPadding(0,dp(5),0,dp(9));card.addView(note);
        LinearLayout first=new LinearLayout(this);first.setOrientation(LinearLayout.HORIZONTAL);Button video=secondaryButton("选择本地视频"),audio=secondaryButton("选择本地音频");first.addView(video,new LinearLayout.LayoutParams(0,dp(44),1f));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(44),1f);ap.setMarginStart(dp(8));first.addView(audio,ap);card.addView(first);
        Button meeting=primaryButton("会议 / 录音 → 逐字稿与纪要");LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(46));mp.topMargin=dp(8);card.addView(meeting,mp);
        video.setOnClickListener(v->openLocalMedia("video"));audio.setOnClickListener(v->openLocalMedia("audio"));meeting.setOnClickListener(v->openLocalMedia("meeting"));return card;
    }

    private void openLocalMedia(String pick){startActivity(new Intent(this,LocalMediaActivity.class).putExtra("pick",pick));}

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String shared = null;
        if (Intent.ACTION_SEND.equals(action) && intent.getType() != null && intent.getType().startsWith("text/")) {
            shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (cs != null) shared = cs.toString();
        }
        if (!TextUtils.isEmpty(shared)) {
            input.setText(shared);
            input.setSelection(input.length());
            status.setText("已收到分享内容，可直接解析。检测平台：" + displayPlatform(LinkExtractor.detectPlatform(shared)));
            startParse();
        }
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
            toast("剪贴板没有文本");
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        input.setText(cs == null ? "" : cs.toString());
        input.setSelection(input.length());
        clearResult();
        status.setText("已粘贴。检测平台：" + displayPlatform(LinkExtractor.detectPlatform(input.getText().toString())));
    }

    private void startParse() {
        if (parsing) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            toast("先粘贴分享链接或分享文案");
            return;
        }
        parsing = true;
        currentResult = null;
        resultBox.removeAllViews();
        resultBox.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        parseButton.setEnabled(false);
        parseButton.setText("解析中…");
        status.setText("正在识别平台并读取作品数据…");

        executor.execute(() -> {
            try {
                ParseResult result = registry.parseText(text);
                main.post(() -> showResult(result));
            } catch (ParseException e) {
                String platform = LinkExtractor.detectPlatform(text);
                if ("xhs".equals(platform)) {
                    main.post(() -> launchXhsFallback(text, e.getMessage()));
                } else {
                    main.post(() -> showError(e.getMessage()));
                }
            } catch (Throwable t) {
                main.post(() -> showError("解析异常：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())));
            }
        });
    }

    private void launchXhsFallback(String text, String fastError) {
        String extracted = LinkExtractor.extractFirstUrl(text);
        if (extracted == null) {
            showError(fastError == null ? "小红书快速解析失败" : fastError);
            return;
        }
        parsing = false;
        progress.setVisibility(View.GONE);
        parseButton.setEnabled(true);
        parseButton.setText("重新解析");
        status.setText("快速解析没有拿到媒体，切换小红书浏览器兼容模式…");
        Intent i = new Intent(this, XhsWebViewActivity.class);
        i.putExtra(XhsWebViewActivity.EXTRA_URL, LinkExtractor.ensureScheme(extracted));
        startActivityForResult(i, REQ_XHS_WEB);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_XHS_WEB) return;
        if (resultCode != RESULT_OK || data == null) {
            showError("小红书浏览器兼容解析未完成。可重新解析后在页面中完成验证/登录再提取。");
            return;
        }
        String raw = data.getStringExtra(XhsWebViewActivity.EXTRA_RESULT_JSON);
        try {
            showResult(parseXhsWebResult(raw));
        } catch (Exception e) {
            showError("小红书浏览器解析结果无效：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private ParseResult parseXhsWebResult(String raw) throws Exception {
        JSONObject obj = new JSONObject(raw == null ? "{}" : raw);
        String sourceUrl = obj.optString("sourceUrl", "");
        ParseResult.Builder b = ParseResult.builder("小红书", sourceUrl)
                .title(obj.optString("title", ""))
                .author(obj.optString("author", ""))
                .description(obj.optString("description", ""))
                .coverUrl(obj.optString("coverUrl", ""));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://www.xiaohongshu.com/");
        headers.put("User-Agent", com.example.mediaparser.net.HttpClient.DESKTOP_UA);
        JSONArray media = obj.optJSONArray("media");
        JSONObject mainVideo = null;
        if (media != null) {
            for (int i = 0; i < media.length(); i++) {
                JSONObject m = media.optJSONObject(i);
                if (m == null) continue;
                String type = m.optString("type", "image");
                String label = m.optString("label", "");
                String url = m.optString("url", "");
                if ("video".equals(type) && !isLiveLabel(label) && isPlausibleXhsVideoUrl(url)) {
                    mainVideo = m;
                    break;
                }
            }
        }

        String mainVideoForAudio = "";
        if (mainVideo != null) {
            String url = mainVideo.optString("url", "");
            b.add(new MediaItem(MediaItem.Type.VIDEO, "小红书视频", url, headers));
            mainVideoForAudio = url;
        } else if (media != null) {
            for (int i = 0; i < media.length(); i++) {
                JSONObject m = media.optJSONObject(i);
                if (m == null) continue;
                String url = m.optString("url", "");
                if (url.isBlank()) continue;
                String type = m.optString("type", "image");
                String label = m.optString("label", "小红书媒体");
                if ("video".equals(type)) {
                    if (isLiveLabel(label) && isPlausibleXhsVideoUrl(url)) {
                        b.add(new MediaItem(MediaItem.Type.VIDEO, label, url, headers));
                    }
                } else if ("image".equals(type)) {
                    b.add(new MediaItem(MediaItem.Type.IMAGE, label, url, headers));
                }
            }
        }
        if (!mainVideoForAudio.isBlank()) b.add(MediaItem.audioTrack("视频完整音轨 · M4A", mainVideoForAudio, headers));
        ParseResult result = b.build();
        if (!result.hasMedia()) throw new IllegalStateException("没有媒体地址");
        return result;
    }

    private static boolean isLiveLabel(String label) {
        String l = label == null ? "" : label.toLowerCase();
        return l.contains("live") || l.contains("实况");
    }

    private static boolean isPlausibleXhsVideoUrl(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            Uri u = Uri.parse(raw);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            String path = u.getPath() == null ? "" : u.getPath().toLowerCase();
            String[] bad = {".js", ".css", ".json", ".html", ".htm", ".svg", ".woff", ".woff2", ".ttf", ".map"};
            for (String ext : bad) if (path.endsWith(ext)) return false;
            if (host.contains("sns-video")) return true;
            return path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov");
        } catch (Exception e) {
            return false;
        }
    }

    private void showResult(ParseResult r) {
        parsing = false;
        currentResult = r;
        progress.setVisibility(View.GONE);
        parseButton.setEnabled(true);
        parseButton.setText("重新解析");
        status.setText("解析成功 · " + r.platform + " · 找到 " + r.media.size() + " 个媒体文件");
        resultBox.removeAllViews();
        subtitleResultPanel = null;
        resultBox.setVisibility(View.VISIBLE);

        LinearLayout info = panel();
        TextView platform = text(r.platform, 13, BLUE, true);
        info.addView(platform);
        if (!r.title.isBlank()) {
            TextView t = text(r.title, 19, TEXT, true);
            t.setPadding(0, dp(7), 0, 0);
            info.addView(t);
        }
        if (!r.author.isBlank()) {
            TextView a = text("作者：" + r.author, 14, MUTED, false);
            a.setPadding(0, dp(6), 0, 0);
            info.addView(a);
        }
        String audioHint = audioHint(r.platform);
        if (!audioHint.isBlank()) {
            TextView ah = text(audioHint, 12, BLUE, false);
            ah.setPadding(0, dp(7), 0, 0);
            info.addView(ah);
        }
        if (!r.description.isBlank() && !r.description.equals(r.title)) {
            TextView d = text(r.description, 14, MUTED, false);
            d.setPadding(0, dp(8), 0, 0);
            info.addView(d);
        }
        resultBox.addView(info);

        if (r.media.size() > 1) {
            Button saveAll = primaryButton("保存全部（" + r.media.size() + "）");
            saveAll.setOnClickListener(v -> saveAll(r, saveAll));
            LinearLayout.LayoutParams sap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            sap.topMargin = dp(10);
            resultBox.addView(saveAll, sap);
        }

        for (int i = 0; i < r.media.size(); i++) addMediaCard(r.media.get(i), i);

        // Subtitle extraction is intentionally last: it is a derived, slower action rather than
        // one of the source media files.
        MediaItem subtitleSource = findSubtitleSource(r);
        if (subtitleSource != null) {
            LinearLayout subtitlePanel = panel();
            LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stp.topMargin = dp(10);
            resultBox.addView(subtitlePanel, stp);
            subtitlePanel.addView(text("字幕生成", 16, TEXT, true));
            TextView sh = text(routeDescription(selectedProvider()), 12, MUTED, false);
            sh.setPadding(0, dp(6), 0, dp(7));
            subtitlePanel.addView(sh);
            TextView subtitleState = text(providerState(selectedProvider()), 12, MUTED, false);
            subtitleState.setPadding(0, 0, 0, dp(9));
            subtitlePanel.addView(subtitleState);
            Button subtitleButton = primaryButton("生成字幕");
            subtitleButton.setOnClickListener(v -> startSubtitle(subtitleSource, r.title, subtitleButton, subtitleState));
            subtitlePanel.addView(subtitleButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
            Button visionButton=secondaryButton("制作硬字幕截图 ZIP（手动发给网页版AI）");
            visionButton.setOnClickListener(v->startVisionExport(subtitleSource,r.title,visionButton,subtitleState));
            LinearLayout.LayoutParams vlp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(46));vlp.topMargin=dp(8);subtitlePanel.addView(visionButton,vlp);
            subtitlePanel.addView(text("截图仅在手机本机裁切、去重并写入 Download；App 不会自动把ZIP上传给任何AI。已有时间轴时每段取3个锚点；未检测到明显字幕时扩展前后2秒并每250ms搜索。否则每0.5秒抽样。",11,MUTED,false));
        }
    }

    private void addMediaCard(MediaItem item, int index) {
        LinearLayout card = panel();
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(10);
        resultBox.addView(card, cp);

        TextView label = text(item.label + " · " + typeName(item.type), 16, TEXT, true);
        card.addView(label);
        TextView url = text(item.extractsAudioTrack() ? "从视频中仅抽取原始音轨并封装为 M4A，不重新编码。" : item.url, 12, MUTED, false);
        url.setTextIsSelectable(!item.extractsAudioTrack());
        url.setMaxLines(3);
        url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        url.setPadding(0, dp(7), 0, dp(8));
        card.addView(url);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button left;
        if (item.extractsAudioTrack()) {
            left = secondaryButton("查看文件");
            left.setOnClickListener(v -> openDownloadsFolder());
        } else {
            left = secondaryButton("复制直链");
            left.setOnClickListener(v -> copy(item.url));
        }

        String saveLabel;
        if (item.extractsAudioTrack()) saveLabel = "提取";
        else if (item.type == MediaItem.Type.AUDIO) saveLabel = "保存音频";
        else if (item.type == MediaItem.Type.VIDEO) saveLabel = "保存视频";
        else saveLabel = "保存图片";
        Button save = secondaryButton(saveLabel);
        save.setOnClickListener(v -> saveOne(item, currentResult == null ? "media" : currentResult.title, save, saveLabel));
        actions.addView(left, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams sv = new LinearLayout.LayoutParams(0, dp(44), 1f);
        sv.setMarginStart(dp(8));
        actions.addView(save, sv);
        card.addView(actions);
    }

    private void showError(String message) {
        parsing = false;
        currentResult = null;
        progress.setVisibility(View.GONE);
        parseButton.setEnabled(true);
        parseButton.setText("重新解析");
        status.setText(message == null ? "解析失败" : message);
        resultBox.setVisibility(View.GONE);
    }

    private void clearResult() {
        currentResult = null;
        subtitleResultPanel = null;
        lastSubtitleOutput = null;
        resultBox.removeAllViews();
        resultBox.setVisibility(View.GONE);
        status.setText("");
    }

    private MediaItem findSubtitleSource(ParseResult result) {
        if (result == null) return null;
        for (MediaItem item : result.media) {
            if (item.extractsAudioTrack()) return item;
        }
        if ("B站".equals(result.platform)) {
            for (MediaItem item : result.media) {
                if (item.type == MediaItem.Type.AUDIO && !item.extractsAudioTrack()) return item;
            }
        }
        for (MediaItem item : result.media) {
            if (item.type == MediaItem.Type.VIDEO) return item;
        }
        for (MediaItem item : result.media) {
            if (item.type == MediaItem.Type.AUDIO) return item;
        }
        return null;
    }

    private void startSubtitle(MediaItem source, String title, Button button, TextView subtitleState) {
        if (subtitleBusy) return;
        SubtitleProvider provider = selectedProvider();
        String apiKey = loadProviderKey(provider);
        if (provider==SubtitleProvider.LOCAL&&!LocalModelManager.isInstalled(this,LocalModelManager.selected(this))) {
            subtitleState.setText("请先在本地模型管理下载并选择模型");toast("先下载本地模型");return;
        }
        if (provider!=SubtitleProvider.LOCAL&&provider!=SubtitleProvider.AUTO&&apiKey.isBlank()) {
            subtitleState.setText("请先在页面底部设置 " + provider.label + " API Key");
            status.setText("还没有 " + provider.label + " API Key。请在页面底部获取并保存 Key。");
            if (provider == SubtitleProvider.GROQ) setGroqSettingsExpanded(true, true);
            else if(provider==SubtitleProvider.GEMINI)setGeminiSettingsExpanded(true, true);
            toast("先设置 " + provider.label + " API Key");
            return;
        }
        if((provider==SubtitleProvider.ALIYUN||provider==SubtitleProvider.QWEN3)&&AliyunSettings.workspace(this).isBlank()){toast("先在页面底部设置阿里云 Workspace ID");return;}
        boolean allowPaid=getSharedPreferences("subtitle_recognition",MODE_PRIVATE).getBoolean("allow_paid",false);
        if(provider.isCloud()&&!allowPaid){new AlertDialog.Builder(this).setTitle("确认本次云端识别")
                .setMessage("服务商接口不能让 App 可靠读取剩余试用额度。默认不允许自动产生付费调用。只有你点“仅本次允许”后，本任务才会调用已配置的云端 ASR；自动模式失败后也可能切换到另一家。请先在服务商控制台确认免费额度或设置额度用完即停。")
                .setNegativeButton("取消",null).setPositiveButton("仅本次允许",(d,w)->runSubtitle(source,title,button,subtitleState,provider,apiKey,true)).show();return;}
        runSubtitle(source, title, button, subtitleState, provider, apiKey,allowPaid);
    }

    private void runSubtitle(MediaItem source, String title, Button button, TextView subtitleState, SubtitleProvider provider, String apiKey,boolean cloudPermitted) {
        if (subtitleBusy) return;
        final TranscriptionOptions options;
        try { options = recognitionOptions(); }
        catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        subtitleActionButton=button;subtitleProgressView=subtitleState;
        subtitleRerun=()->startSubtitle(source,title,button,subtitleState);
        button.setOnClickListener(v->subtitleRerun.run());
        subtitleBusy=true; button.setEnabled(false);button.setAlpha(0.55f);button.setText("字幕处理中…");
        parseButton.setEnabled(false);
        SubtitleProgress progress = new SubtitleProgress(provider);
        final boolean[] running={true};
        final long started=SystemClock.elapsedRealtime();
        Runnable ticker=new Runnable(){public void run(){
            if(!running[0])return;
            subtitleState.setText(progress.render(SystemClock.elapsedRealtime()));
            main.postDelayed(this,1000);
        }};
        main.post(ticker);
        executor.execute(()->{
            SubtitleExtractor.Listener subtitleListener=new SubtitleExtractor.Listener(){
                public void onPhase(SubtitleProgress.Phase phase,SubtitleProgress.State state,String detail){
                    main.post(()->{if(!running[0])return;
                        progress.update(phase,state,detail,SystemClock.elapsedRealtime());
                        subtitleState.setText(progress.render(SystemClock.elapsedRealtime()));
                        status.setText(SubtitleProgress.label(phase)+"："+detail);
                    });
                }
                public void onStage(String text){onPhase(SubtitleProgress.Phase.PREPARE,SubtitleProgress.State.RUNNING,text);}
                public void onSourceProgress(int percent,long done,long total){onStage("下载 "+percent+"%（"+done/1024+" / "+total/1024+" KB）");}
                public void onUploadProgress(int percent,long done,long total){}
                public void onTranscribeStart(long duration){}
            };
            try{
                SubtitleProvider primary=SubtitleProvider.fromSaved(getSharedPreferences("subtitle_recognition",MODE_PRIVATE).getString("auto_primary","LOCAL"));
                SubtitleOutput output=provider==SubtitleProvider.AUTO
                        ?SubtitleExtractor.extractAuto(this,source,title,primary,cloudPermitted,options,subtitleListener)
                        :SubtitleExtractor.extract(this, source, title, provider, apiKey, options, subtitleListener);
                main.post(()->{
                    running[0]=false;main.removeCallbacks(ticker);progress.finish();subtitleBusy=false;
                    subtitleState.setText(progress.render(SystemClock.elapsedRealtime())+"\n\n总用时 "+formatEta(SystemClock.elapsedRealtime()-started));
                    button.setText("重新生成字幕");button.setEnabled(true);button.setAlpha(1f);parseButton.setEnabled(true);
                    status.setText(!output.hasTiming()?"文字已保存；时间轴不可用，未导出SRT":"ASR 原稿与对齐文件已保存；可继续制作网页校对截图包");
                    showSubtitleResult(output,provider);
                });
            }catch(Throwable e){
                String message=SubtitleExtractor.errorMessage(e,apiKey);
                main.post(()->{running[0]=false;main.removeCallbacks(ticker);progress.finish();subtitleBusy=false;
                    subtitleState.setText(progress.render(SystemClock.elapsedRealtime())+"\n\n"+message);
                    status.setText("字幕失败："+message);button.setText("重试字幕");button.setEnabled(true);button.setAlpha(1f);parseButton.setEnabled(true);
                });
            }
        });
    }

    private static String formatEta(long ms) {
        long sec = Math.max(0L, (ms + 500L) / 1000L);
        if (sec < 60L) return sec + "秒";
        long min = sec / 60L;
        long rem = sec % 60L;
        if (min < 60L) return rem == 0 ? min + "分钟" : min + "分" + rem + "秒";
        long h = min / 60L;
        long m = min % 60L;
        return h + "小时" + (m == 0 ? "" : m + "分");
    }

    private static String formatMb(long bytes) {
        return Math.max(0L, bytes) / (1024L * 1024L) + " MB";
    }

    private void showSubtitleResult(SubtitleOutput output, SubtitleProvider provider) {
        lastSubtitleOutput=output;
        if(subtitleActionButton!=null) {
            subtitleActionButton.setEnabled(true);subtitleActionButton.setAlpha(1f);
            subtitleActionButton.setText("重新生成字幕");
            subtitleActionButton.setOnClickListener(v->{if(subtitleRerun!=null)subtitleRerun.run();});
        }
        if (subtitleResultPanel != null) resultBox.removeView(subtitleResultPanel);
        LinearLayout card = panel();
        subtitleResultPanel = card;
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(10);
        resultBox.addView(card, cp);

        final SubtitleOutput[] active = {output};
        final String[] version = {"ASR 原稿"};
        String meta = (output.actualEngine.isBlank()?provider.label:output.actualEngine) + " · ASR 原稿";
        if (!output.detectedLanguage.isBlank()) meta += " · " + output.detectedLanguage;
        card.addView(text(meta, 16, TEXT, true));
        TextView note = text("时间轴经过范围校验，不代表逐字对齐。App 不再调用 Gemini 二次校对；请制作硬字幕截图 ZIP，再交给网页版模型校正。画面硬字幕清晰时应优先相信画面文字。", 12, MUTED, false);
        note.setPadding(0, dp(6), 0, dp(8));
        card.addView(note);

        LinearLayout versions = new LinearLayout(this);
        versions.setOrientation(LinearLayout.VERTICAL);
        card.addView(versions);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        Button timed = secondaryButton("分段时间轴");
        Button plain = secondaryButton("查看全文");
        modes.addView(timed, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams plainLayout = new LinearLayout.LayoutParams(0, dp(42), 1f);
        plainLayout.setMarginStart(dp(8));
        modes.addView(plain, plainLayout);
        card.addView(modes);

        TextView previewState = text("", 12, MUTED, false);
        previewState.setPadding(0, dp(8), 0, 0);
        card.addView(previewState);
        TextView body = text("", 14, TEXT, false);
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(3), 1f);
        body.setPadding(0, dp(8), 0, dp(10));
        card.addView(body);
        Button more = secondaryButton("加载更多字幕");
        card.addView(more, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        final boolean[] showTiming = {true};
        final int[] visibleSegments = {SubtitlePreview.PAGE_SIZE};
        Runnable render = () -> {
            SubtitlePreview.Page page = SubtitlePreview.first(active[0].segments, visibleSegments[0]);
            body.setText(showTiming[0] && active[0].hasTiming() ? page.text : active[0].fullText);
            if(!active[0].hasTiming()) body.setText(active[0].timingWarning+"\n\n"+active[0].fullText);
            else if(!active[0].timingWarning.isBlank())body.setText(active[0].timingWarning+"\n\n"+body.getText());
            previewState.setText(showTiming[0] && active[0].hasTiming()
                    ? version[0] + " · 显示 " + page.shown + " / " + page.total + " 段 · 时:分:秒,毫秒"
                    : version[0] + (active[0].hasTiming()?" · 完整文字（可切换时间轴）":" · 完整文字，无时间轴"));
            more.setVisibility(showTiming[0] && page.hasMore ? View.VISIBLE : View.GONE);
            more.setText("加载更多（剩余 " + (page.total - page.shown) + " 段）");
            timed.setEnabled(active[0].hasTiming() && !showTiming[0]);
            timed.setAlpha(showTiming[0] ? 0.65f : 1f);
            plain.setEnabled(showTiming[0]);
            plain.setAlpha(showTiming[0] ? 1f : 0.65f);
        };
        timed.setOnClickListener(v -> { showTiming[0] = true; render.run(); });
        plain.setOnClickListener(v -> { showTiming[0] = false; render.run(); });
        more.setOnClickListener(v -> {
            visibleSegments[0] = Math.min(active[0].segments.size(), visibleSegments[0] + SubtitlePreview.PAGE_SIZE);
            render.run();
        });
        render.run();
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button copySrt = secondaryButton("复制 SRT");
        copySrt.setOnClickListener(v -> { if(active[0].hasTiming()) copy(active[0].srt); else toast("该稿只有文字，未生成可用SRT"); });
        Button copyText = secondaryButton("复制全文");
        copyText.setOnClickListener(v -> copy(active[0].fullText));
        Button open = secondaryButton("下载目录");
        open.setOnClickListener(v -> openDownloadsFolder());
        actions.addView(copySrt, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams textLayout = new LinearLayout.LayoutParams(0, dp(44), 1f);
        textLayout.setMarginStart(dp(6));
        actions.addView(copyText, textLayout);
        LinearLayout.LayoutParams openLayout = new LinearLayout.LayoutParams(0, dp(44), 1f);
        openLayout.setMarginStart(dp(6));
        actions.addView(open, openLayout);
        card.addView(actions);
    }

    private void saveOne(MediaItem item, String title, Button button, String idleLabel) {
        boolean extracting = item.extractsAudioTrack();
        button.setEnabled(false);
        button.setAlpha(0.55f);
        button.setText(extracting ? "提取中…" : "下载中…");
        status.setText(extracting ? "正在下载视频并无损提取音轨…" : "正在保存 " + item.label + "…");
        executor.execute(() -> {
            try {
                FileSaver.save(this, item, title);
                main.post(() -> {
                    button.setText(extracting ? "已提取" : "已下载");
                    button.setEnabled(false);
                    button.setAlpha(0.65f);
                    status.setText(extracting
                            ? "音频已提取到系统 Download/下载目录"
                            : "已保存 " + item.label + " 到系统 Download/下载目录");
                });
            } catch (Exception e) {
                main.post(() -> {
                    button.setText("重试");
                    button.setEnabled(true);
                    button.setAlpha(1f);
                    status.setText("保存失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                });
            }
        });
    }

    private void saveAll(ParseResult result, Button button) {
        button.setEnabled(false);
        button.setAlpha(0.55f);
        button.setText("保存中…");
        status.setText("正在保存全部媒体…");
        executor.execute(() -> {
            int ok = 0;
            String lastError = "";
            for (MediaItem item : result.media) {
                try {
                    FileSaver.save(this, item, result.title);
                    ok++;
                } catch (Exception e) {
                    lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
            int saved = ok;
            String error = lastError;
            main.post(() -> {
                if (saved == result.media.size()) {
                    button.setText("已保存全部");
                    button.setEnabled(false);
                    button.setAlpha(0.65f);
                    status.setText("已保存全部 " + saved + " 个媒体文件到系统 Download/下载目录");
                } else {
                    button.setText("已处理 " + saved + "/" + result.media.size());
                    button.setEnabled(false);
                    button.setAlpha(0.65f);
                    status.setText("已保存 " + saved + "/" + result.media.size() + (error.isBlank() ? "" : "，最后错误：" + error) + "。可在下方单项重试失败文件。");
                }
            });
        });
    }

    private SubtitleProvider selectedProvider() {
        return SubtitleProvider.fromSaved(getSharedPreferences("subtitle_route", MODE_PRIVATE).getString("provider", "GEMINI"));
    }

    private String loadProviderKey(SubtitleProvider provider) {
        if(provider==SubtitleProvider.LOCAL||provider==SubtitleProvider.AUTO)return "local";
        if(provider==SubtitleProvider.ALIYUN||provider==SubtitleProvider.QWEN3)return AliyunKeyStore.load(this);
        if(provider==SubtitleProvider.DOUBAO)return DoubaoCredentialStore.load(this).configured()?"configured":"";
        return provider == SubtitleProvider.GROQ ? GroqKeyStore.load(this) : GeminiKeyStore.load(this);
    }

    private String routeDescription(SubtitleProvider p){
        if(p==SubtitleProvider.LOCAL)return LocalModelManager.selected(this).name+" · 音频不上传 · 生成 SRT + TXT";
        if(p==SubtitleProvider.ALIYUN)return "阿里云 Paraformer-v2 · 中文增强 · 公开直链服务端转写";
        if(p==SubtitleProvider.QWEN3)return "阿里云 Qwen3-ASR · 长音频 · 句/词时间戳 · 漏识别检测";
        if(p==SubtitleProvider.DOUBAO)return "豆包录音文件识别 · 句/词时间戳";
        if(p==SubtitleProvider.AUTO)return "自动选择 · 记录实际引擎 · 失败后继续备用";
        return p.label+" 云端字幕 · 生成 SRT + TXT";
    }
    private String providerState(SubtitleProvider p){
        if(p==SubtitleProvider.LOCAL){LocalModelManager.ModelSpec s=LocalModelManager.selected(this);return LocalModelManager.isInstalled(this,s)?"模型已安装并选中 · 全程本机处理":"模型未安装 · 请在下方本地模型管理下载";}
        if(p==SubtitleProvider.ALIYUN)return !AliyunKeyStore.hasKey(this)?"请在页面底部设置阿里云 Key 与 Workspace ID":AliyunSettings.workspace(this).isBlank()?"Key 已保存 · 还缺 Workspace ID":"阿里云设置已保存 · 提交时使用当前解析直链";
        if(p==SubtitleProvider.QWEN3)return !AliyunKeyStore.hasKey(this)?"请在页面底部设置阿里云 Key 与 Workspace ID":"Qwen3-ASR 使用已保存的阿里云设置";
        if(p==SubtitleProvider.DOUBAO)return DoubaoCredentialStore.load(this).configured()?"豆包凭证已加密保存":"请在页面底部设置豆包凭证";
        if(p==SubtitleProvider.AUTO)return "按主引擎与已配置备用顺序运行；付费保护默认关闭";
        return !loadProviderKey(p).isBlank()?"API Key 已设置 · 音频仅上传到所选 "+p.label+" 云端":"请先在页面底部设置 "+p.label+" API Key";
    }

    private void startVisionExport(MediaItem source,String title,Button button,TextView state){
        if(subtitleBusy)return;subtitleBusy=true;button.setEnabled(false);parseButton.setEnabled(false);button.setText("制作截图包中…");
        executor.execute(()->{try{VisionPackageExporter.Result r=VisionPackageExporter.export(this,source,title,lastSubtitleOutput,s->main.post(()->state.setText(s)));main.post(()->{subtitleBusy=false;button.setEnabled(true);parseButton.setEnabled(true);button.setText("重新制作硬字幕截图 ZIP");state.setText("已保存 "+r.locations.size()+" 个ZIP，共 "+r.frames+" 张去重截图 · "+(r.anchorGuided?"按原稿时间轴取帧":"每0.5秒抽样"));status.setText("截图包已保存到 Download；请手动上传给网页版视觉AI");});}
            catch(Exception e){String m=SubtitleExtractor.errorMessage(e,"");main.post(()->{subtitleBusy=false;button.setEnabled(true);parseButton.setEnabled(true);button.setText("重试制作截图 ZIP");state.setText("截图包失败："+m);});}});
    }

    private TranscriptionOptions recognitionOptions() {
        android.content.SharedPreferences prefs = getSharedPreferences("subtitle_recognition", MODE_PRIVATE);
        return new TranscriptionOptions(prefs.getString("language", "auto"),
                prefs.getBoolean("accurate", true), prefs.getString("terms", ""),prefs.getBoolean("visual_review",false));
    }

    private LinearLayout buildRecognitionCard() {
        LinearLayout card = panel();
        Button toggle = secondaryButton("识别设置 · 自动主引擎 / 付费保护 / 语言 ▾");
        card.addView(toggle);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> body.setVisibility(body.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        android.content.SharedPreferences prefs = getSharedPreferences("subtitle_recognition", MODE_PRIVATE);
        RadioGroup languages = new RadioGroup(this);
        languages.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton auto = new RadioButton(this);
        auto.setId(View.generateViewId()); auto.setText("自动语言");
        RadioButton chinese = new RadioButton(this);
        chinese.setId(View.generateViewId()); chinese.setText("中文（普通话）");
        languages.addView(auto); languages.addView(chinese);
        languages.check("zh".equals(prefs.getString("language", "auto")) ? chinese.getId() : auto.getId());
        body.addView(languages);
        CheckBox accurate = new CheckBox(this);
        accurate.setText("Groq 准确优先 · Large V3（取消为 Turbo）");
        accurate.setChecked(prefs.getBoolean("accurate", true));
        body.addView(accurate);
        body.addView(text("自动选择的主引擎（失败后按已配置情况切换备用）：",12,MUTED,true));
        RadioGroup primaryGroup=new RadioGroup(this);primaryGroup.setOrientation(LinearLayout.VERTICAL);
        LinkedHashMap<SubtitleProvider,RadioButton> primaryButtons=new LinkedHashMap<>();
        for(SubtitleProvider p:new SubtitleProvider[]{SubtitleProvider.LOCAL,SubtitleProvider.QWEN3,SubtitleProvider.DOUBAO,SubtitleProvider.ALIYUN,SubtitleProvider.GROQ,SubtitleProvider.GEMINI}){RadioButton b=new RadioButton(this);b.setId(View.generateViewId());b.setText(p.label);primaryGroup.addView(b);primaryButtons.put(p,b);}
        SubtitleProvider savedPrimary=SubtitleProvider.fromSaved(prefs.getString("auto_primary","LOCAL"));RadioButton savedPrimaryButton=primaryButtons.get(savedPrimary);primaryGroup.check(savedPrimaryButton==null?primaryButtons.get(SubtitleProvider.LOCAL).getId():savedPrimaryButton.getId());body.addView(primaryGroup);
        CheckBox allowPaid=new CheckBox(this);allowPaid.setText("允许自动使用可能产生费用的云端额度");allowPaid.setChecked(prefs.getBoolean("allow_paid",false));body.addView(allowPaid);
        body.addView(text("默认关闭。关闭时，每次云端任务都必须点“仅本次允许”；服务商无法查询余额时，App 会明确提示到控制台查看，不猜测免费余额。",12,BLUE,false));
        EditText terms = new EditText(this);
        terms.setHint("可选术语，逗号分隔，如 AE86、猎豹（按视频填写）");
        terms.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        terms.setMinLines(2);
        terms.setText(prefs.getString("terms", ""));
        body.addView(terms);
        body.addView(text("术语是识别提示，不会强制替换字幕；不同视频请调整或清空。中文设置适用于普通话，方言或外语请选自动。保留原始识别及时间轴，不能保证每句无误。", 12, MUTED, false));
        Button save = secondaryButton("保存识别设置");
        save.setOnClickListener(v -> {
            if (subtitleBusy) { toast("当前字幕完成后再修改设置"); return; }
            try {
                TranscriptionOptions options = new TranscriptionOptions(
                        languages.getCheckedRadioButtonId() == chinese.getId() ? "zh" : "auto",
                        accurate.isChecked(), terms.getText().toString(),false);
                SubtitleProvider primary=SubtitleProvider.LOCAL;for(Map.Entry<SubtitleProvider,RadioButton> e:primaryButtons.entrySet())if(e.getValue().getId()==primaryGroup.getCheckedRadioButtonId())primary=e.getKey();
                prefs.edit().putString("language", options.language).putBoolean("accurate", options.accurate)
                        .putString("terms", options.groqPrompt()).putBoolean("visual_review",false).putString("auto_primary",primary.name()).putBoolean("allow_paid",allowPaid.isChecked()).apply();
                terms.setText(options.groqPrompt());
                toast("识别设置已保存，下次生成生效");
                if (currentResult != null) showResult(currentResult);
            } catch (IllegalArgumentException e) { toast(e.getMessage()); }
        });
        body.addView(save);
        card.addView(body);
        return card;
    }

    private LinearLayout buildRouteCard() {
        LinearLayout card = panel();
        card.addView(text("字幕路线 · 单引擎 / 自动备用", 15, TEXT, true));
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(LinearLayout.VERTICAL);
        RadioButton automatic = new RadioButton(this);
        automatic.setId(View.generateViewId());automatic.setText("自动选择 · 主引擎失败后继续备用");
        RadioButton gemini = new RadioButton(this);
        gemini.setId(View.generateViewId());
        gemini.setText("Gemini");
        RadioButton groq = new RadioButton(this);
        groq.setId(View.generateViewId());
        groq.setText("Groq · Whisper");
        RadioButton local = new RadioButton(this);
        local.setId(View.generateViewId()); local.setText("本地离线 · 可切换模型（小米13推荐）");
        RadioButton aliyun = new RadioButton(this);
        aliyun.setId(View.generateViewId()); aliyun.setText("阿里云百炼 · Paraformer-v2");
        RadioButton qwen3 = new RadioButton(this);qwen3.setId(View.generateViewId());qwen3.setText("阿里云百炼 · Qwen3-ASR 长音频");
        RadioButton doubao = new RadioButton(this);doubao.setId(View.generateViewId());doubao.setText("豆包 / 火山引擎 · 录音文件 ASR");
        group.addView(automatic);
        group.addView(local);
        group.addView(aliyun);
        group.addView(qwen3);
        group.addView(doubao);
        group.addView(gemini);
        group.addView(groq);
        group.check(selectedProvider()==SubtitleProvider.AUTO?automatic.getId():selectedProvider() == SubtitleProvider.LOCAL?local.getId():selectedProvider()==SubtitleProvider.ALIYUN?aliyun.getId():selectedProvider()==SubtitleProvider.QWEN3?qwen3.getId():selectedProvider()==SubtitleProvider.DOUBAO?doubao.getId():selectedProvider() == SubtitleProvider.GROQ ? groq.getId() : gemini.getId());
        group.setOnCheckedChangeListener((g, id) -> {
            SubtitleProvider provider = id==automatic.getId()?SubtitleProvider.AUTO:id==local.getId()?SubtitleProvider.LOCAL:id==aliyun.getId()?SubtitleProvider.ALIYUN:id==qwen3.getId()?SubtitleProvider.QWEN3:id==doubao.getId()?SubtitleProvider.DOUBAO:id == groq.getId() ? SubtitleProvider.GROQ : SubtitleProvider.GEMINI;
            if (provider == selectedProvider()) return;
            if (subtitleBusy) {
                toast("当前字幕完成后再切换路线");
                group.check(selectedProvider()==SubtitleProvider.AUTO?automatic.getId():selectedProvider() == SubtitleProvider.LOCAL?local.getId():selectedProvider()==SubtitleProvider.ALIYUN?aliyun.getId():selectedProvider()==SubtitleProvider.QWEN3?qwen3.getId():selectedProvider()==SubtitleProvider.DOUBAO?doubao.getId():selectedProvider() == SubtitleProvider.GROQ ? groq.getId() : gemini.getId());
                return;
            }
            getSharedPreferences("subtitle_route", MODE_PRIVATE).edit().putString("provider", provider.name()).apply();
            if (currentResult != null) showResult(currentResult);
        });
        card.addView(group);
        card.addView(text("本地路线不上传音频；云端单路线只发给所选服务。自动模式记录实际使用引擎并在失败后继续备用。App 不再调用 Gemini API 校对，最终校正统一导出截图包交给网页版模型。", 12, MUTED, false));
        return card;
    }

    private LinearLayout buildLocalModelCard(){
        LinearLayout card=panel();card.addView(text("本地模型管理 · 下载一次，自由切换",15,TEXT,true));
        long ram=0;try{android.app.ActivityManager.MemoryInfo mi=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);ram=mi.totalMem;}catch(Exception ignored){}
        card.addView(text((ram>=8L*1024*1024*1024?"检测到高内存设备：推荐 Fun-ASR-Nano；中文/粤语可试 Paraformer。":"推荐先用 SenseVoice；内存足够时再试 Fun-ASR-Nano。")+" 模型只支持 arm64-v8a，安装包内不含模型文件。",12,BLUE,false));
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);card.addView(list);final Runnable[] refresh=new Runnable[1];
        refresh[0]=()->{list.removeAllViews();LocalModelManager.ModelSpec selected=LocalModelManager.selected(this);for(LocalModelManager.ModelSpec spec:LocalModelManager.CATALOG)addLocalModelRow(list,spec,selected,refresh[0]);};
        refresh[0].run();return card;
    }

    private void addLocalModelRow(LinearLayout list,LocalModelManager.ModelSpec spec,LocalModelManager.ModelSpec selected,Runnable refresh){
        boolean installed=LocalModelManager.isInstalled(this,spec),active=installed&&selected.id.equals(spec.id);long total=LocalModelManager.downloadBytes(spec),partial=LocalModelManager.partialBytes(this,spec);
        LinearLayout row=panel();row.setPadding(dp(10),dp(10),dp(10),dp(10));row.addView(text((active?"✓ ":"")+spec.name,13,TEXT,true));row.addView(text(spec.description+(installed?" · 已安装":" · 下载约 "+formatMb(total)),11,MUTED,false));
        TextView detail=text(active?"✓ 已安装并正在使用":installed?"✓ 已安装并通过 SHA-256 校验":partial>0?"下载未完成 · 已保留断点 "+formatMb(partial)+" / "+formatMb(total):"尚未安装",11,installed?BLUE:MUTED,false);row.addView(detail);
        ProgressBar downloadProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);downloadProgress.setMax(1000);downloadProgress.setProgress(total>0?(int)Math.min(1000,partial*1000/total):0);downloadProgress.setVisibility(partial>0&&!installed?View.VISIBLE:View.GONE);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(8));pp.topMargin=dp(7);row.addView(downloadProgress,pp);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);Button mainButton=active?secondaryButton("当前使用"):installed?primaryButton("切换使用"):primaryButton(partial>0?"继续下载":"下载并校验");mainButton.setEnabled(!active);
        mainButton.setOnClickListener(v->{if(subtitleBusy){toast("请等待当前操作结束");return;}if(installed){LocalModelManager.select(this,spec);toast("已切换到 "+spec.name);refresh.run();if(currentResult!=null)showResult(currentResult);return;}new AlertDialog.Builder(this).setTitle((partial>0?"继续下载 ":"下载 ")+spec.name).setMessage(spec.description+"\n下载期间会显示百分比、大小、速度和预计时间；支持断点续传。下载后将核对官方 SHA-256，校验失败不会安装。请保持 App 处于运行状态。").setNegativeButton("取消",null).setPositiveButton("开始下载",(d,w)->startLocalModelInstall(spec,mainButton,downloadProgress,detail,refresh)).show();});
        actions.addView(mainButton,new LinearLayout.LayoutParams(0,dp(42),1f));if(installed){Button del=secondaryButton("删除");LinearLayout.LayoutParams dp1=new LinearLayout.LayoutParams(dp(82),dp(42));dp1.setMarginStart(dp(8));actions.addView(del,dp1);del.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("删除本地模型？").setMessage(spec.name+" 将从本机移除，之后可重新下载。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{LocalModelManager.delete(this,spec);refresh.run();if(currentResult!=null)showResult(currentResult);}).show());}row.addView(actions);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);rp.topMargin=dp(8);list.addView(row,rp);
    }

    private void startLocalModelInstall(LocalModelManager.ModelSpec spec,Button button,ProgressBar bar,TextView detail,Runnable refresh){
        subtitleBusy=true;button.setEnabled(false);button.setText("准备下载…");parseButton.setEnabled(false);bar.setVisibility(View.VISIBLE);bar.setIndeterminate(true);detail.setTextColor(BLUE);detail.setText("正在连接下载服务器…");toast("模型开始下载，可在这里查看进度");long started=SystemClock.elapsedRealtime();java.util.concurrent.atomic.AtomicLong lastUi=new java.util.concurrent.atomic.AtomicLong(0);
        executor.execute(()->{try{LocalModelManager.install(this,spec,(stage,done,total)->{long now=SystemClock.elapsedRealtime(),last=lastUi.get();boolean downloading=stage.contains("下载");if(downloading&&done<total&&now-last<250)return;lastUi.set(now);main.post(()->{int percent=total>0?(int)Math.min(100,done*100/total):0;button.setText(downloading?"下载中 "+percent+"%":stage.contains("校验")?"校验中…":stage.contains("解压")?"安装中…":"处理中…");if(downloading){bar.setIndeterminate(false);bar.setMax(1000);bar.setProgress(total>0?(int)Math.min(1000,done*1000/total):0);long elapsed=Math.max(1,now-started),speed=done*1000/elapsed,remaining=speed>0&&total>done?(total-done)*1000/speed:0;detail.setText((stage.equals("准备下载")?"准备/续传":"正在下载")+" · "+formatMb(done)+" / "+formatMb(total)+(speed>0?" · "+formatDownloadSpeed(speed):"")+(remaining>0?" · 预计 "+formatEta(remaining):""));}else{bar.setIndeterminate(true);detail.setText(stage+"，请稍候…");}});});LocalModelManager.select(this,spec);main.post(()->{subtitleBusy=false;parseButton.setEnabled(true);toast("模型安装完成并已选中");refresh.run();if(currentResult!=null)showResult(currentResult);});}catch(Exception e){String m=SubtitleExtractor.errorMessage(e,"");main.post(()->{subtitleBusy=false;parseButton.setEnabled(true);button.setEnabled(true);button.setText("继续/重试下载");bar.setIndeterminate(false);bar.setVisibility(View.GONE);detail.setTextColor(Color.rgb(190,45,45));detail.setText("下载或安装失败 · 已保留可续传断点 · "+m);toast(m);});}});
    }

    private static String formatDownloadSpeed(long bytesPerSecond){if(bytesPerSecond>=1024L*1024L)return String.format(java.util.Locale.ROOT,"%.1f MB/s",bytesPerSecond/(1024d*1024d));return Math.max(1,bytesPerSecond/1024)+" KB/s";}

    private LinearLayout buildAliyunCard(){
        LinearLayout card=panel();card.addView(text("阿里云百炼字幕 · Paraformer / Qwen3-ASR",13,TEXT,true));card.addView(text("同一组北京地域 Key / Workspace 可测试 Paraformer-v2 与 qwen3-asr-flash-filetrans。试用额度属于首次/新人总额度，不是每月免费；接口不能读取剩余时长，请到百炼控制台查看。实际文件转写要求公网 HTTPS URL。",11,MUTED,false));
        aliyunKeyStatus=text("",12,MUTED,false);card.addView(aliyunKeyStatus);aliyunKeyInput=new EditText(this);aliyunKeyInput.setSingleLine(true);aliyunKeyInput.setHint("粘贴 DashScope / 百炼 API Key");aliyunKeyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);aliyunKeyInput.setBackground(roundRect(Color.WHITE,dp(10),BORDER,1));aliyunKeyInput.setPadding(dp(12),0,dp(12),0);card.addView(aliyunKeyInput,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(46)));aliyunWorkspaceInput=new EditText(this);aliyunWorkspaceInput.setSingleLine(true);aliyunWorkspaceInput.setHint("Workspace ID（北京地域）");aliyunWorkspaceInput.setText(AliyunSettings.workspace(this));aliyunWorkspaceInput.setBackground(roundRect(Color.WHITE,dp(10),BORDER,1));aliyunWorkspaceInput.setPadding(dp(12),0,dp(12),0);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(46));wp.topMargin=dp(8);card.addView(aliyunWorkspaceInput,wp);
        LinearLayout links=new LinearLayout(this);links.setOrientation(LinearLayout.HORIZONTAL);Button console=secondaryButton("查看试用余额");console.setOnClickListener(v->openExternalUrl("https://bailian.console.aliyun.com/cn-beijing?tab=costing-balance#/costing-balance/free-quota"));Button docs=secondaryButton("Qwen3 官方说明");docs.setOnClickListener(v->openExternalUrl("https://help.aliyun.com/en/model-studio/non-realtime-speech-recognition-user-guide"));links.addView(console,new LinearLayout.LayoutParams(0,dp(42),1));LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(0,dp(42),1);dl.setMarginStart(dp(8));links.addView(docs,dl);card.addView(links);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);Button clear=secondaryButton("清除");Button test=secondaryButton("测试两种模型");Button save=primaryButton("保存并验证");actions.addView(clear,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(0,dp(44),1.35f);tl.setMarginStart(dp(8));actions.addView(test,tl);LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(0,dp(44),1.55f);sl.setMarginStart(dp(8));actions.addView(save,sl);card.addView(actions);
        clear.setOnClickListener(v->{if(aliyunKeyValidating||subtitleBusy){toast("请等待当前操作结束");return;}AliyunSettings.clear(this);getSharedPreferences("aliyun_key_meta",MODE_PRIVATE).edit().clear().apply();aliyunKeyInput.setText("");aliyunWorkspaceInput.setText("");updateAliyunStatus();if(currentResult!=null)showResult(currentResult);});
        test.setOnClickListener(v->validateStoredAliyunSettings(test));
        save.setOnClickListener(v->saveAliyunSettings(save));
        updateAliyunStatus();return card;
    }
    private void saveAliyunSettings(Button button){
        if(aliyunKeyValidating||subtitleBusy){toast("请等待当前操作结束");return;}
        String entered=aliyunKeyInput.getText().toString().trim();String key=entered.isBlank()?AliyunKeyStore.load(this):entered;String ws=aliyunWorkspaceInput.getText().toString().trim();
        if(key.isBlank()){toast("请粘贴阿里云 API Key");return;}if(ws.isBlank()){toast("请填写 Workspace ID");return;}
        aliyunKeyValidating=true;button.setEnabled(false);button.setText("验证中…");aliyunKeyStatus.setText("正在验证 Paraformer-v2 与 Qwen3-ASR 权限…");
        executor.execute(()->{GeminiKeyValidator.Result result=AliyunKeyValidator.validate(key,ws);main.post(()->{aliyunKeyValidating=false;button.setEnabled(true);button.setText("保存并验证");if(result.usable()){try{if(!entered.isBlank())AliyunKeyStore.save(this,key);AliyunSettings.saveWorkspace(this,ws);getSharedPreferences("aliyun_key_meta",MODE_PRIVATE).edit().putBoolean("verified",true).putLong("verified_at",System.currentTimeMillis()).putString("verified_message",result.message).apply();aliyunKeyInput.setText("");updateAliyunStatus();toast(result.message);if(currentResult!=null&&!subtitleBusy)showResult(currentResult);}catch(Exception e){aliyunKeyStatus.setText("鉴权通过，但加密保存失败："+e.getMessage());}}else{aliyunKeyStatus.setText("验证失败："+result.message);toast(result.message);}});});
    }
    private void validateStoredAliyunSettings(Button button){
        if(aliyunKeyValidating||subtitleBusy){toast("请等待当前操作结束");return;}String key=AliyunKeyStore.load(this),ws=AliyunSettings.workspace(this);if(key.isBlank()||ws.isBlank()){aliyunKeyStatus.setText("请先保存 Key 与 Workspace ID");return;}
        aliyunKeyValidating=true;button.setEnabled(false);button.setText("测试中…");aliyunKeyStatus.setText("正在验证当前 Key 与 Workspace…");executor.execute(()->{GeminiKeyValidator.Result result=AliyunKeyValidator.validate(key,ws);main.post(()->{aliyunKeyValidating=false;button.setEnabled(true);button.setText("测试两种模型");getSharedPreferences("aliyun_key_meta",MODE_PRIVATE).edit().putBoolean("verified",result.usable()).putLong("verified_at",System.currentTimeMillis()).putString("verified_message",result.message).apply();updateAliyunStatus();if(!result.usable())aliyunKeyStatus.setText("验证失败："+result.message);toast(result.message);});});
    }
    private void updateAliyunStatus(){if(aliyunKeyStatus==null)return;String masked=AliyunKeyStore.masked(this),ws=AliyunSettings.workspace(this);boolean verified=getSharedPreferences("aliyun_key_meta",MODE_PRIVATE).getBoolean("verified",false);String message=getSharedPreferences("aliyun_key_meta",MODE_PRIVATE).getString("verified_message","");if(masked.isBlank())aliyunKeyStatus.setText("状态：未设置 API Key");else if(ws.isBlank())aliyunKeyStatus.setText("状态：已保存 "+masked+" · 缺 Workspace ID");else if(verified)aliyunKeyStatus.setText("状态：设置已验证 "+masked+" · "+ws+(message==null||message.isBlank()?"":" · "+message));else aliyunKeyStatus.setText("状态：已保存 "+masked+" · Workspace 已设置 · 尚未验证或上次验证失败");}

    private LinearLayout buildDoubaoCard(){
        LinearLayout card=panel();card.addView(text("豆包 / 火山引擎语音识别大模型",13,TEXT,true));card.addView(text("标准录音文件版先读取公网直链；遇到抖音防盗链 45000006 时，会自动用旧版 App ID + Access Token 切换到已开通的流式小时版，将本机音频直传。流式直传按音频实际时长运行。此接口不使用云账号 Secret Key。试用额度是首次总额度，不是每月刷新；官方接口无法查询剩余量，请到控制台查看。",11,MUTED,false));doubaoStatus=text("",12,MUTED,false);card.addView(doubaoStatus);
        doubaoApiKeyInput=secretInput("新版 X-Api-Key（有则只填这个）");card.addView(doubaoApiKeyInput,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(46)));
        doubaoAppIdInput=plainInput("旧版 App ID / App Key");doubaoAccessTokenInput=secretInput("旧版 Access Token");doubaoResourceInput=plainInput("Resource ID，默认 volc.bigasr.auc");doubaoResourceInput.setText(DoubaoCredentialStore.load(this).resourceId);for(EditText e:new EditText[]{doubaoAppIdInput,doubaoAccessTokenInput,doubaoResourceInput}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(46));lp.topMargin=dp(8);card.addView(e,lp);}
        LinearLayout links=new LinearLayout(this);Button console=secondaryButton("控制台 / 额度");console.setOnClickListener(v->openExternalUrl("https://console.volcengine.com/speech/service/10011"));Button docs=secondaryButton("官方接口说明");docs.setOnClickListener(v->openExternalUrl("https://www.volcengine.com/docs/6561/1354868?lang=zh"));links.addView(console,new LinearLayout.LayoutParams(0,dp(42),1));LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(0,dp(42),1);dlp.setMarginStart(dp(8));links.addView(docs,dlp);card.addView(links);
        LinearLayout actions=new LinearLayout(this);Button clear=secondaryButton("清除");Button test=secondaryButton("测试连接");Button save=primaryButton("加密保存并测试");actions.addView(clear,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(0,dp(44),1.2f);tl.setMarginStart(dp(8));actions.addView(test,tl);LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(0,dp(44),1.6f);sl.setMarginStart(dp(8));actions.addView(save,sl);card.addView(actions);
        clear.setOnClickListener(v->{if(doubaoValidating||subtitleBusy){toast("请等待当前操作结束");return;}DoubaoCredentialStore.clear(this);getSharedPreferences("doubao_key_meta",MODE_PRIVATE).edit().clear().apply();doubaoApiKeyInput.setText("");doubaoAppIdInput.setText("");doubaoAccessTokenInput.setText("");doubaoResourceInput.setText(DoubaoCredentialStore.DEFAULT_RESOURCE);updateDoubaoStatus();});
        save.setOnClickListener(v->saveDoubao(save));test.setOnClickListener(v->testDoubao(test,DoubaoCredentialStore.load(this),false));updateDoubaoStatus();return card;
    }
    private EditText secretInput(String hint){EditText e=plainInput(hint);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
    private EditText plainInput(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextSize(13);e.setBackground(roundRect(Color.WHITE,dp(10),BORDER,1));e.setPadding(dp(12),0,dp(12),0);return e;}
    private void saveDoubao(Button button){if(doubaoValidating||subtitleBusy){toast("请等待当前操作结束");return;}DoubaoCredentialStore.Credentials old=DoubaoCredentialStore.load(this);String api=doubaoApiKeyInput.getText().toString().trim(),app=doubaoAppIdInput.getText().toString().trim(),token=doubaoAccessTokenInput.getText().toString().trim(),resource=doubaoResourceInput.getText().toString().trim();boolean enteredNew=!api.isBlank(),enteredLegacy=!app.isBlank()||!token.isBlank();if(enteredNew&&enteredLegacy){toast("新版 API Key 与旧版 App ID/Token 只能选择一套");return;}DoubaoCredentialStore.Credentials c;if(enteredNew)c=new DoubaoCredentialStore.Credentials(api,"","",resource);else if(enteredLegacy)c=new DoubaoCredentialStore.Credentials("",app.isBlank()?old.appId:app,token.isBlank()?old.accessToken:token,resource);else c=new DoubaoCredentialStore.Credentials(old.apiKey,old.appId,old.accessToken,resource);if(!c.configured()){toast("请填新版 API Key，或旧版 App ID + Access Token");return;}try{DoubaoCredentialStore.save(this,c);doubaoApiKeyInput.setText("");doubaoAppIdInput.setText("");doubaoAccessTokenInput.setText("");testDoubao(button,c,true);}catch(Exception e){doubaoStatus.setText("加密保存失败："+e.getMessage());}}
    private void testDoubao(Button button,DoubaoCredentialStore.Credentials c,boolean saved){if(doubaoValidating||subtitleBusy)return;if(!c.configured()){doubaoStatus.setText("请先保存豆包凭证");return;}doubaoValidating=true;button.setEnabled(false);button.setText("测试中…");doubaoStatus.setText("发送空音频鉴权探测，不产生正式转写…");executor.execute(()->{GeminiKeyValidator.Result result=DoubaoCredentialValidator.validate(c);main.post(()->{doubaoValidating=false;button.setEnabled(true);button.setText(saved?"加密保存并测试":"测试连接");getSharedPreferences("doubao_key_meta",MODE_PRIVATE).edit().putBoolean("verified",result.usable()).putString("verified_message",result.message).putLong("verified_at",System.currentTimeMillis()).apply();updateDoubaoStatus();if(!result.usable())doubaoStatus.setText("测试未通过："+result.message);toast(result.message);if(currentResult!=null&&!subtitleBusy)showResult(currentResult);});});}
    private void updateDoubaoStatus(){if(doubaoStatus==null)return;DoubaoCredentialStore.Credentials c=DoubaoCredentialStore.load(this);boolean ok=getSharedPreferences("doubao_key_meta",MODE_PRIVATE).getBoolean("verified",false);String msg=getSharedPreferences("doubao_key_meta",MODE_PRIVATE).getString("verified_message","");String mode=!c.apiKey.isBlank()?"新版 X-Api-Key":"旧版 App ID + Access Token";doubaoStatus.setText(!c.configured()?"状态：未设置凭证":(ok?"状态：连接测试通过 ":"状态：已加密保存，尚未确认连接 ")+mode+" · "+c.masked()+" · "+c.resourceId+(msg==null||msg.isBlank()?"":" · "+msg));}

    private LinearLayout buildGeminiCard() {
        LinearLayout card = panel();
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout compact = new LinearLayout(this);
        compact.setOrientation(LinearLayout.HORIZONTAL);
        compact.setGravity(Gravity.CENTER_VERTICAL);
        geminiCompactStatus = text("", 13, TEXT, true);
        compact.addView(geminiCompactStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        geminiSettingsToggle = secondaryButton("设置");
        geminiSettingsToggle.setOnClickListener(v -> setGeminiSettingsExpanded(!geminiSettingsExpanded, false));
        compact.addView(geminiSettingsToggle, new LinearLayout.LayoutParams(dp(84), dp(40)));
        card.addView(compact);

        geminiSettingsBody = new LinearLayout(this);
        geminiSettingsBody.setOrientation(LinearLayout.VERTICAL);
        geminiSettingsBody.setVisibility(View.GONE);

        TextView explain = text("获取 Key：Google AI Studio → Get API key → Create API key。保存时会验证 Key 鉴权及模型可见性，不上传视频或音频。", 12, MUTED, false);
        explain.setPadding(0, dp(10), 0, dp(6));
        geminiSettingsBody.addView(explain);

        TextView free = text("免费层：Gemini 3.5 Transcribe 当前输入和输出可使用 Free Tier；具体 RPM / TPM / RPD 及 Free / Paid 层级以 Key 所属 AI Studio 项目为准。App 仅验证 Key 鉴权与模型列表，不保证实际转写成功，但不能仅凭 Key 判断项目是否已绑定付费账单。", 12, BLUE, false);
        free.setPadding(0, 0, 0, dp(8));
        geminiSettingsBody.addView(free);

        geminiKeyStatus = text("", 12, MUTED, false);
        geminiKeyStatus.setPadding(0, 0, 0, dp(7));
        geminiSettingsBody.addView(geminiKeyStatus);

        geminiKeyInput = new EditText(this);
        geminiKeyInput.setSingleLine(true);
        geminiKeyInput.setTextSize(14);
        geminiKeyInput.setTextColor(TEXT);
        geminiKeyInput.setHintTextColor(Color.rgb(145, 151, 162));
        geminiKeyInput.setHint("粘贴 Gemini API Key");
        geminiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        geminiKeyInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        geminiKeyInput.setBackground(roundRect(Color.WHITE, dp(10), BORDER, 1));
        geminiSettingsBody.addView(geminiKeyInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        Button getKey = secondaryButton("获取 Key");
        getKey.setOnClickListener(v -> openExternalUrl("https://aistudio.google.com/app/apikey"));
        Button quota = secondaryButton("查看额度/层级");
        quota.setOnClickListener(v -> openExternalUrl("https://aistudio.google.com/rate-limit?timeRange=last-28-days"));
        links.addView(getKey, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        qlp.setMarginStart(dp(8));
        links.addView(quota, qlp);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(8);
        geminiSettingsBody.addView(links, llp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button clearKey = secondaryButton("清除");
        clearKey.setOnClickListener(v -> {
            if (geminiKeyValidating || subtitleBusy) { toast("请等待当前操作结束"); return; }
            GeminiKeyStore.clear(this);
            getSharedPreferences("gemini_key_meta", MODE_PRIVATE).edit().clear().apply();
            geminiKeyInput.setText("");
            updateGeminiKeyStatus();
            if (currentResult != null && !subtitleBusy) showResult(currentResult);
        });
        Button testKey = secondaryButton("测试当前 Key");
        testKey.setOnClickListener(v -> validateStoredGeminiKey(testKey));
        Button saveKey = primaryButton("保存并验证");
        saveKey.setOnClickListener(v -> saveGeminiKey(saveKey));
        actions.addView(clearKey, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams tkp = new LinearLayout.LayoutParams(0, dp(44), 1.2f);
        tkp.setMarginStart(dp(8));
        actions.addView(testKey, tkp);
        LinearLayout.LayoutParams skp = new LinearLayout.LayoutParams(0, dp(44), 1.6f);
        skp.setMarginStart(dp(8));
        actions.addView(saveKey, skp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(8);
        geminiSettingsBody.addView(actions, alp);

        card.addView(geminiSettingsBody);
        updateGeminiKeyStatus();
        return card;
    }

    private void setGeminiSettingsExpanded(boolean expanded, boolean focusKey) {
        geminiSettingsExpanded = expanded;
        if (geminiSettingsBody != null) geminiSettingsBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (geminiSettingsToggle != null) geminiSettingsToggle.setText(expanded ? "收起" : "设置");
        if (expanded && focusKey && geminiKeyInput != null) {
            geminiKeyInput.requestFocus();
            geminiKeyInput.post(() -> geminiKeyInput.requestRectangleOnScreen(
                    new Rect(0, 0, geminiKeyInput.getWidth(), geminiKeyInput.getHeight()), false));
        }
    }

    private void saveGeminiKey(Button button) {
        if (geminiKeyValidating || subtitleBusy) { toast("请等待当前操作结束"); return; }
        String key = geminiKeyInput.getText().toString().trim();
        if (key.isBlank()) {
            toast("先粘贴 Gemini API Key");
            return;
        }
        geminiKeyValidating = true;
        button.setEnabled(false);
        button.setText("验证中…");
        geminiKeyStatus.setText("正在验证 Key 和 Gemini 3.5 Transcribe 访问权限…");
        executor.execute(() -> {
            GeminiKeyValidator.Result result = GeminiKeyValidator.validate(key);
            main.post(() -> {
                geminiKeyValidating = false;
                button.setEnabled(true);
                button.setText("保存并验证");
                if (result.usable()) {
                    try {
                        GeminiKeyStore.save(this, key);
                        getSharedPreferences("gemini_key_meta", MODE_PRIVATE).edit()
                                .putBoolean("verified", true)
                                .putLong("verified_at", System.currentTimeMillis())
                                .putString("verified_message", result.message)
                                .apply();
                        geminiKeyInput.setText("");
                        updateGeminiKeyStatus();
                        toast(result.message);
                        setGeminiSettingsExpanded(false, false);
                        if (currentResult != null && !subtitleBusy) showResult(currentResult);
                    } catch (Exception e) {
                        geminiKeyStatus.setText("Key 验证通过，但加密保存失败");
                    }
                } else {
                    geminiKeyStatus.setText("验证失败：" + result.message);
                    updateGeminiCompactStatus("Key 不可用");
                    toast(result.message);
                }
            });
        });
    }

    private void validateStoredGeminiKey(Button button) {
        if (geminiKeyValidating || subtitleBusy) { toast("请等待当前操作结束"); return; }
        String key = GeminiKeyStore.load(this);
        if (key.isBlank()) {
            geminiKeyStatus.setText("还没有已保存的 Key");
            return;
        }
        geminiKeyValidating = true;
        button.setEnabled(false);
        button.setText("测试中…");
        geminiKeyStatus.setText("正在验证当前 Key…");
        executor.execute(() -> {
            GeminiKeyValidator.Result result = GeminiKeyValidator.validate(key);
            main.post(() -> {
                geminiKeyValidating = false;
                button.setEnabled(true);
                button.setText("测试当前 Key");
                getSharedPreferences("gemini_key_meta", MODE_PRIVATE).edit()
                        .putBoolean("verified", result.usable())
                        .putLong("verified_at", System.currentTimeMillis())
                        .putString("verified_message", result.message)
                        .apply();
                updateGeminiKeyStatus();
                if (!result.usable()) geminiKeyStatus.setText("验证失败：" + result.message);
                toast(result.message);
            });
        });
    }

    private void updateGeminiCompactStatus(String forced) {
        if (geminiCompactStatus == null) return;
        if (forced != null && !forced.isBlank()) {
            geminiCompactStatus.setText("Gemini 字幕 · " + forced);
            return;
        }
        boolean has = GeminiKeyStore.hasKey(this);
        boolean verified = getSharedPreferences("gemini_key_meta", MODE_PRIVATE).getBoolean("verified", false);
        geminiCompactStatus.setText(!has ? "Gemini 字幕 · 未设置 Key" : (verified ? "Gemini 字幕 · 鉴权通过" : "Gemini 字幕 · Key 已保存，未验证"));
    }

    private void updateGeminiKeyStatus() {
        if (geminiKeyStatus == null) return;
        updateGeminiCompactStatus(null);
        String masked = GeminiKeyStore.masked(this);
        boolean verified = getSharedPreferences("gemini_key_meta", MODE_PRIVATE).getBoolean("verified", false);
        long verifiedAt = getSharedPreferences("gemini_key_meta", MODE_PRIVATE).getLong("verified_at", 0L);
        String verifiedMessage = getSharedPreferences("gemini_key_meta", MODE_PRIVATE).getString("verified_message", "");
        if (masked.isBlank()) {
            geminiKeyStatus.setText("状态：未设置 API Key");
        } else if (verified) {
            geminiKeyStatus.setText("状态：已验证 " + masked + (verifiedMessage == null || verifiedMessage.isBlank() ? "" : " · " + verifiedMessage));
        } else {
            geminiKeyStatus.setText("状态：已保存 " + masked + " · 尚未验证或上次验证失败");
        }
    }

    private LinearLayout buildGroqCard() {
        LinearLayout card = panel();
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout compact = new LinearLayout(this);
        compact.setOrientation(LinearLayout.HORIZONTAL);
        compact.setGravity(Gravity.CENTER_VERTICAL);
        groqCompactStatus = text("", 13, TEXT, true);
        compact.addView(groqCompactStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        groqSettingsToggle = secondaryButton("设置");
        groqSettingsToggle.setOnClickListener(v -> setGroqSettingsExpanded(!groqSettingsExpanded, false));
        compact.addView(groqSettingsToggle, new LinearLayout.LayoutParams(dp(84), dp(40)));
        card.addView(compact);

        groqSettingsBody = new LinearLayout(this);
        groqSettingsBody.setOrientation(LinearLayout.VERTICAL);
        groqSettingsBody.setVisibility(View.GONE);

        TextView explain = text("获取 Key：Groq Console → API Keys → Create API Key。保存时会验证 Key 鉴权及模型可见性，不上传视频或音频。", 12, MUTED, false);
        explain.setPadding(0, dp(10), 0, dp(6));
        groqSettingsBody.addView(explain);

        TextView free = text("Groq 云端 Whisper：可在识别设置选择 Large V3 或 Turbo。音频会上传到 Groq。可使用 Free Plan，具体速率、音频小时和层级以 Groq Console 为准。此 App 限制单次音频 25 MB / 30 分钟；鉴权测试不等于实际转写成功，也不能判断是否已开通付费。", 12, BLUE, false);
        free.setPadding(0, 0, 0, dp(8));
        groqSettingsBody.addView(free);

        groqKeyStatus = text("", 12, MUTED, false);
        groqKeyStatus.setPadding(0, 0, 0, dp(7));
        groqSettingsBody.addView(groqKeyStatus);

        groqKeyInput = new EditText(this);
        groqKeyInput.setSingleLine(true);
        groqKeyInput.setTextSize(14);
        groqKeyInput.setTextColor(TEXT);
        groqKeyInput.setHintTextColor(Color.rgb(145, 151, 162));
        groqKeyInput.setHint("粘贴 Groq API Key");
        groqKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        groqKeyInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        groqKeyInput.setBackground(roundRect(Color.WHITE, dp(10), BORDER, 1));
        groqSettingsBody.addView(groqKeyInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        Button getKey = secondaryButton("获取 Key");
        getKey.setOnClickListener(v -> openExternalUrl("https://console.groq.com/keys"));
        Button quota = secondaryButton("查看额度/层级");
        quota.setOnClickListener(v -> openExternalUrl("https://console.groq.com/settings/limits"));
        links.addView(getKey, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        qlp.setMarginStart(dp(8));
        links.addView(quota, qlp);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(8);
        groqSettingsBody.addView(links, llp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button clearKey = secondaryButton("清除");
        clearKey.setOnClickListener(v -> {
            if (groqKeyValidating || subtitleBusy) { toast("请等待当前操作结束"); return; }
            GroqKeyStore.clear(this);
            getSharedPreferences("groq_key_meta", MODE_PRIVATE).edit().clear().apply();
            groqKeyInput.setText("");
            updateGroqKeyStatus();
            if (currentResult != null && !subtitleBusy) showResult(currentResult);
        });
        Button testKey = secondaryButton("测试当前 Key");
        testKey.setOnClickListener(v -> validateStoredGroqKey(testKey));
        Button saveKey = primaryButton("保存并验证");
        saveKey.setOnClickListener(v -> saveGroqKey(saveKey));
        actions.addView(clearKey, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams tkp = new LinearLayout.LayoutParams(0, dp(44), 1.2f);
        tkp.setMarginStart(dp(8));
        actions.addView(testKey, tkp);
        LinearLayout.LayoutParams skp = new LinearLayout.LayoutParams(0, dp(44), 1.6f);
        skp.setMarginStart(dp(8));
        actions.addView(saveKey, skp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(8);
        groqSettingsBody.addView(actions, alp);

        card.addView(groqSettingsBody);
        updateGroqKeyStatus();
        return card;
    }

    private void setGroqSettingsExpanded(boolean expanded, boolean focusKey) {
        groqSettingsExpanded = expanded;
        if (groqSettingsBody != null) groqSettingsBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (groqSettingsToggle != null) groqSettingsToggle.setText(expanded ? "收起" : "设置");
        if (expanded && focusKey && groqKeyInput != null) {
            groqKeyInput.requestFocus();
            groqKeyInput.post(() -> groqKeyInput.requestRectangleOnScreen(
                    new Rect(0, 0, groqKeyInput.getWidth(), groqKeyInput.getHeight()), false));
        }
    }

    private void saveGroqKey(Button button) {
        if (groqKeyValidating || subtitleBusy) { toast("请等待当前操作结束"); return; }
        String key = groqKeyInput.getText().toString().trim();
        if (key.isBlank()) {
            toast("先粘贴 Groq API Key");
            return;
        }
        groqKeyValidating = true;
        button.setEnabled(false);
        button.setText("验证中…");
        groqKeyStatus.setText("正在验证 Key 和所选 Groq Whisper 模型访问权限…");
        executor.execute(() -> {
            GeminiKeyValidator.Result result = GroqKeyValidator.validate(key, recognitionOptions().groqModel());
            main.post(() -> {
                groqKeyValidating = false;
                button.setEnabled(true);
                button.setText("保存并验证");
                if (result.usable()) {
                    try {
                        GroqKeyStore.save(this, key);
                        getSharedPreferences("groq_key_meta", MODE_PRIVATE).edit()
                                .putBoolean("verified", true)
                                .putLong("verified_at", System.currentTimeMillis())
                                .putString("verified_message", result.message)
                                .apply();
                        groqKeyInput.setText("");
                        updateGroqKeyStatus();
                        toast(result.message);
                        setGroqSettingsExpanded(false, false);
                        if (currentResult != null && !subtitleBusy) showResult(currentResult);
                    } catch (Exception e) {
                        groqKeyStatus.setText("Key 验证通过，但加密保存失败");
                    }
                } else {
                    groqKeyStatus.setText("验证失败：" + result.message);
                    updateGroqCompactStatus("Key 不可用");
                    toast(result.message);
                }
            });
        });
    }

    private void validateStoredGroqKey(Button button) {
        if (groqKeyValidating || subtitleBusy) { toast("请等待当前操作结束"); return; }
        String key = GroqKeyStore.load(this);
        if (key.isBlank()) {
            groqKeyStatus.setText("还没有已保存的 Key");
            return;
        }
        groqKeyValidating = true;
        button.setEnabled(false);
        button.setText("测试中…");
        groqKeyStatus.setText("正在验证当前 Key…");
        executor.execute(() -> {
            GeminiKeyValidator.Result result = GroqKeyValidator.validate(key, recognitionOptions().groqModel());
            main.post(() -> {
                groqKeyValidating = false;
                button.setEnabled(true);
                button.setText("测试当前 Key");
                getSharedPreferences("groq_key_meta", MODE_PRIVATE).edit()
                        .putBoolean("verified", result.usable())
                        .putLong("verified_at", System.currentTimeMillis())
                        .putString("verified_message", result.message)
                        .apply();
                updateGroqKeyStatus();
                if (!result.usable()) groqKeyStatus.setText("验证失败：" + result.message);
                toast(result.message);
            });
        });
    }

    private void updateGroqCompactStatus(String forced) {
        if (groqCompactStatus == null) return;
        if (forced != null && !forced.isBlank()) {
            groqCompactStatus.setText("Groq 字幕 · " + forced);
            return;
        }
        boolean has = GroqKeyStore.hasKey(this);
        boolean verified = getSharedPreferences("groq_key_meta", MODE_PRIVATE).getBoolean("verified", false);
        groqCompactStatus.setText(!has ? "Groq 字幕 · 未设置 Key" : (verified ? "Groq 字幕 · 鉴权通过" : "Groq 字幕 · Key 已保存，未验证"));
    }

    private void updateGroqKeyStatus() {
        if (groqKeyStatus == null) return;
        updateGroqCompactStatus(null);
        String masked = GroqKeyStore.masked(this);
        boolean verified = getSharedPreferences("groq_key_meta", MODE_PRIVATE).getBoolean("verified", false);
        long verifiedAt = getSharedPreferences("groq_key_meta", MODE_PRIVATE).getLong("verified_at", 0L);
        String verifiedMessage = getSharedPreferences("groq_key_meta", MODE_PRIVATE).getString("verified_message", "");
        if (masked.isBlank()) {
            groqKeyStatus.setText("状态：未设置 API Key");
        } else if (verified) {
            groqKeyStatus.setText("状态：已验证 " + masked + (verifiedMessage == null || verifiedMessage.isBlank() ? "" : " · " + verifiedMessage));
        } else {
            groqKeyStatus.setText("状态：已保存 " + masked + " · 尚未验证或上次验证失败");
        }
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("无法打开浏览器");
        }
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(14), dp(14), dp(14), dp(14));
        p.setBackground(roundRect(PANEL, dp(14), BORDER, 1));
        return p;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setLineSpacing(0, 1.1f);
        return v;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(roundRect(BLUE, dp(12), BLUE, 0));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setMinHeight(dp(42));
        b.setBackground(roundRect(Color.WHITE, dp(10), BORDER, 1));
        return b;
    }

    private GradientDrawable roundRect(int fill, int radius, int strokeColor, int strokeWidthDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidthDp > 0) d.setStroke(dp(strokeWidthDp), strokeColor);
        return d;
    }


    private void openDownloadsFolder() {
        Uri folderUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Download"
        );
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
            return;
        } catch (Exception ignored) {
        }

        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(Uri.parse("content://downloads/public_downloads"), "*/*");
            startActivity(view);
        } catch (Exception e) {
            toast("文件已保存到系统 Download/下载目录");
        }
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("media_url", s));
        toast("已复制直链");
    }

    private String displayPlatform(String key) {
        if (key == null) return "未识别";
        switch (key) {
            case "douyin": return "抖音";
            case "xhs": return "小红书";
            case "kuaishou": return "快手";
            case "bilibili": return "B站";
            case "weibo": return "微博";
            default: return key;
        }
    }


    private String audioHint(String platform) {
        if (platform == null) return "";
        switch (platform) {
            case "抖音": return "音频策略：完整视频音轨 + 平台原声/配乐（若作品提供）";
            case "B站": return "音频策略：优先最高码率独立 DASH 音频，不重新编码";
            case "小红书": return "音频策略：视频完整音轨无损抽取为 M4A；图文/Live Photo 不强制生成音频";
            case "快手": return "音频策略：从视频文件无损抽取完整音轨为 M4A";
            case "微博": return "音频策略：从视频文件无损抽取完整音轨为 M4A";
            default: return "";
        }
    }

    private String typeName(MediaItem.Type t) {
        if (t == MediaItem.Type.IMAGE) return "图片";
        if (t == MediaItem.Type.AUDIO) return "音频";
        return "视频";
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}


