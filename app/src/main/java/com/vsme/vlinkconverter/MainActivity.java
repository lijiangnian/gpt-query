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
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(12, 13, 16);
    private static final int CARD = Color.rgb(29, 30, 35);
    private static final int CARD_2 = Color.rgb(38, 39, 45);
    private static final int ACCENT = Color.rgb(76, 132, 255);
    private static final int GREEN = Color.rgb(71, 202, 143);
    private static final int MUTED = Color.rgb(166, 170, 180);
    private static final int REQ_SAVE_YAML = 7001;

    private EditText input;
    private EditText output;
    private Spinner target;
    private TextView status;
    private TextView nodeInfo;
    private TextView modeHint;
    private Button primaryAction;
    private Button qrButton;
    private Button saveButton;
    private Button openButton;
    private VlessNode current;
    private String pendingYaml;
    private String pendingFileName = "VLink-config.yaml";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        scroll.addView(root);

        TextView title = text("VLink", 30, true);
        root.addView(title);
        TextView sub = text("VLESS 一键整理 · 能直连就不转换", 14, false);
        sub.setTextColor(MUTED);
        root.addView(sub);

        TextView privacy = text("● 本地处理 · 不上传节点信息", 13, false);
        privacy.setTextColor(GREEN);
        LinearLayout.LayoutParams privacyLp = lp();
        privacyLp.topMargin = dp(10);
        root.addView(privacy, privacyLp);

        LinearLayout inputCard = card();
        LinearLayout.LayoutParams cardLp = lp();
        cardLp.topMargin = dp(18);
        root.addView(inputCard, cardLp);
        inputCard.addView(text("1  粘贴节点", 16, true));

        input = new EditText(this);
        input.setHint("vless://...");
        input.setHintTextColor(Color.rgb(110, 112, 122));
        input.setTextColor(Color.WHITE);
        input.setTextSize(13);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(4);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(round(CARD_2, 18));
        LinearLayout.LayoutParams inputLp = lp();
        inputLp.topMargin = dp(10);
        inputCard.addView(input, inputLp);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams inputRowLp = lp();
        inputRowLp.topMargin = dp(10);
        inputCard.addView(inputRow, inputRowLp);

        Button paste = primaryButton("粘贴并识别");
        paste.setOnClickListener(v -> pasteAndParse());
        inputRow.addView(paste, weightLp());

        Button clear = secondaryButton("清空");
        clear.setOnClickListener(v -> clearAll());
        LinearLayout.LayoutParams clearLp = weightLp();
        clearLp.leftMargin = dp(8);
        inputRow.addView(clear, clearLp);

        status = text("等待输入", 13, false);
        status.setTextColor(MUTED);
        LinearLayout.LayoutParams statusLp = lp();
        statusLp.topMargin = dp(10);
        inputCard.addView(status, statusLp);

        nodeInfo = text("", 12, false);
        nodeInfo.setTextColor(Color.rgb(205, 207, 214));
        nodeInfo.setVisibility(View.GONE);
        LinearLayout.LayoutParams infoLp = lp();
        infoLp.topMargin = dp(4);
        inputCard.addView(nodeInfo, infoLp);

        LinearLayout targetCard = card();
        LinearLayout.LayoutParams targetLp = lp();
        targetLp.topMargin = dp(14);
        root.addView(targetCard, targetLp);
        targetCard.addView(text("2  选择怎么导入", 16, true));

        target = new Spinner(this);
        String[] items = {
                "直接导入 URL｜v2rayNG / Hiddify / Xray / 其他 VLESS",
                "FlClash｜生成本地 YAML 配置文件",
                "Clash Meta for Android｜生成本地 YAML 配置文件",
                "其他 Clash / Mihomo｜生成 YAML 配置文件"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(Color.WHITE);
                    ((TextView) v).setTextSize(14);
                    ((TextView) v).setPadding(dp(12), dp(10), dp(12), dp(10));
                }
                return v;
            }
        };
        target.setAdapter(adapter);
        target.setBackground(round(CARD_2, 16));
        LinearLayout.LayoutParams targetSpinnerLp = lp();
        targetSpinnerLp.topMargin = dp(10);
        targetCard.addView(target, targetSpinnerLp);
        target.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { render(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        modeHint = text("支持 VLESS URL 的客户端不做二次转换，直接导入最稳。", 12, false);
        modeHint.setTextColor(GREEN);
        LinearLayout.LayoutParams modeLp = lp();
        modeLp.topMargin = dp(10);
        targetCard.addView(modeHint, modeLp);

        LinearLayout resultCard = card();
        LinearLayout.LayoutParams resultLp = lp();
        resultLp.topMargin = dp(14);
        root.addView(resultCard, resultLp);
        resultCard.addView(text("3  导入结果", 16, true));

        output = new EditText(this);
        output.setTextColor(Color.rgb(232, 233, 237));
        output.setTextSize(12);
        output.setBackground(round(Color.rgb(20, 21, 25), 16));
        output.setPadding(dp(14), dp(14), dp(14), dp(14));
        output.setMinLines(7);
        output.setGravity(Gravity.TOP | Gravity.START);
        output.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams outputLp = lp();
        outputLp.topMargin = dp(10);
        resultCard.addView(output, outputLp);

        LinearLayout actionRow1 = new LinearLayout(this);
        actionRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionRowLp = lp();
        actionRowLp.topMargin = dp(10);
        resultCard.addView(actionRow1, actionRowLp);

        primaryAction = primaryButton("复制 URL");
        primaryAction.setOnClickListener(v -> copyOutput());
        actionRow1.addView(primaryAction, weightLp());

        qrButton = secondaryButton("二维码");
        qrButton.setOnClickListener(v -> showQr());
        LinearLayout.LayoutParams qrLp = weightLp();
        qrLp.leftMargin = dp(8);
        actionRow1.addView(qrButton, qrLp);

        saveButton = secondaryButton("保存 YAML");
        saveButton.setOnClickListener(v -> saveYaml());
        LinearLayout.LayoutParams saveLp = weightLp();
        saveLp.leftMargin = dp(8);
        actionRow1.addView(saveButton, saveLp);

        LinearLayout actionRow2 = new LinearLayout(this);
        actionRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams action2Lp = lp();
        action2Lp.topMargin = dp(8);
        resultCard.addView(actionRow2, action2Lp);

        Button share = secondaryButton("分享结果");
        share.setOnClickListener(v -> shareOutput());
        actionRow2.addView(share, weightLp());

        openButton = secondaryButton("打开客户端");
        openButton.setOnClickListener(v -> openSelectedClient());
        LinearLayout.LayoutParams openLp = weightLp();
        openLp.leftMargin = dp(8);
        actionRow2.addView(openButton, openLp);

        TextView guideTitle = text("导入方式", 15, true);
        LinearLayout.LayoutParams guideTitleLp = lp();
        guideTitleLp.topMargin = dp(18);
        root.addView(guideTitle, guideTitleLp);

        TextView guide = text(
                "直接导入集合：v2rayNG / Hiddify / Xray 等直接使用原始 vless:// URL。\n" +
                "FlClash：配置 → ＋ → 文件 → 选择 VLink 保存的 .yaml。它本身也支持远程 URL 和二维码配置。\n" +
                "Clash Meta for Android：配置 → ＋ → 文件 → 选择 VLink 保存的 .yaml；它也支持远程订阅 URL。\n" +
                "这样不需要搭建服务器，也不会为了单个节点强行制造假订阅地址。", 12, false);
        guide.setTextColor(MUTED);
        guide.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams guideLp = lp();
        guideLp.topMargin = dp(6);
        root.addView(guide, guideLp);

        updateActionVisibility();
        return scroll;
    }

    private void pasteAndParse() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (s != null) input.setText(s.toString().trim());
        }
        parseInput();
    }

    private void parseInput() {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) { toast("请先粘贴 VLESS URL"); return; }
        try {
            current = VlessNode.parse(raw);
            status.setText("✓ 已识别 VLESS" + ("reality".equalsIgnoreCase(current.security) ? " · Reality" : ""));
            status.setTextColor(GREEN);
            nodeInfo.setText(current.host + ":" + current.port + " · " + current.type.toUpperCase() + " · " + current.name);
            nodeInfo.setVisibility(View.VISIBLE);
            render();
        } catch (Exception e) {
            current = null;
            output.setText("");
            status.setText("✕ " + e.getMessage());
            status.setTextColor(Color.rgb(255, 113, 113));
            nodeInfo.setVisibility(View.GONE);
        }
    }

    private void render() {
        updateActionVisibility();
        if (current == null || output == null || target == null) return;
        int p = target.getSelectedItemPosition();
        if (p == 0) {
            output.setText(current.raw);
            modeHint.setText("无需转换：复制 URL、扫码或直接尝试交给支持 VLESS 的客户端打开。");
            modeHint.setTextColor(GREEN);
        } else {
            String yaml = current.toMihomoYaml();
            output.setText(yaml);
            pendingYaml = yaml;
            pendingFileName = "VLink-" + safeName(current.name) + ".yaml";
            if (p == 1) modeHint.setText("FlClash：生成完整 YAML，保存后从“配置 → ＋ → 文件”导入。");
            else if (p == 2) modeHint.setText("Clash Meta：生成完整 YAML，保存后从“配置 → ＋ → 文件”导入。");
            else modeHint.setText("Clash / Mihomo：生成完整 YAML 配置文件供本地导入。");
            modeHint.setTextColor(Color.rgb(255, 194, 92));
        }
    }

    private void updateActionVisibility() {
        if (target == null || primaryAction == null) return;
        boolean direct = target.getSelectedItemPosition() == 0;
        primaryAction.setText(direct ? "复制 URL" : "复制 YAML");
        qrButton.setVisibility(direct ? View.VISIBLE : View.GONE);
        saveButton.setVisibility(direct ? View.GONE : View.VISIBLE);
        openButton.setText(direct ? "直接打开" : "打开客户端");
    }

    private void copyOutput() {
        String s = output.getText().toString();
        if (s.isEmpty()) { toast("没有可复制的内容"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("VLink", s));
        toast(target.getSelectedItemPosition() == 0 ? "VLESS URL 已复制" : "YAML 已复制");
    }

    private void saveYaml() {
        if (current == null) { toast("请先识别节点"); return; }
        pendingYaml = current.toMihomoYaml();
        pendingFileName = "VLink-" + safeName(current.name) + ".yaml";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/yaml");
        i.putExtra(Intent.EXTRA_TITLE, pendingFileName);
        startActivityForResult(i, REQ_SAVE_YAML);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE_YAML && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                if (os != null) {
                    os.write(pendingYaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.flush();
                    toast("配置文件已保存，可去客户端选择“文件”导入");
                }
            } catch (Exception e) {
                toast("保存失败：" + e.getMessage());
            }
        }
    }

    private void shareOutput() {
        String s = output.getText().toString();
        if (s.isEmpty()) { toast("没有可分享的内容"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, s);
        startActivity(Intent.createChooser(i, "分享 VLink 结果"));
    }

    private void openSelectedClient() {
        if (current == null) { toast("请先识别节点"); return; }
        int p = target.getSelectedItemPosition();
        if (p == 0) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(current.raw));
                startActivity(Intent.createChooser(i, "选择支持 VLESS 的客户端"));
            } catch (Exception e) {
                toast("没有找到可直接打开 VLESS URL 的应用，请使用复制或二维码导入");
            }
            return;
        }
        String pkg = p == 1 ? "com.follow.clash" : (p == 2 ? "com.github.metacubex.clash.meta" : null);
        if (pkg == null) { toast("请在目标 Clash / Mihomo 客户端中选择“从文件导入”"); return; }
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) startActivity(launch);
        else toast("未找到该客户端，请先安装或手动打开");
    }

    private void showQr() {
        if (current == null) { toast("请先识别节点"); return; }
        try {
            String s = current.raw;
            int size = Math.min(getResources().getDisplayMetrics().widthPixels - dp(72), dp(420));
            BitMatrix m = new MultiFormatWriter().encode(s, BarcodeFormat.QR_CODE, size, size);
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) b.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(b);
            iv.setPadding(dp(14), dp(14), dp(14), dp(14));
            new AlertDialog.Builder(this).setTitle("VLESS URL 二维码").setView(iv).setPositiveButton("关闭", null).show();
        } catch (Exception e) {
            toast("二维码生成失败");
        }
    }

    private void clearAll() {
        current = null;
        input.setText("");
        output.setText("");
        status.setText("等待输入");
        status.setTextColor(MUTED);
        nodeInfo.setVisibility(View.GONE);
        pendingYaml = null;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(15), dp(15), dp(15), dp(15));
        c.setBackground(round(CARD, 22));
        return c;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button primaryButton(String s) { return styledButton(s, ACCENT, Color.WHITE); }
    private Button secondaryButton(String s) { return styledButton(s, CARD_2, Color.rgb(235, 235, 240)); }

    private Button styledButton(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(fg);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(round(bg, 14));
        b.setMinHeight(dp(46));
        return b;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private static String safeName(String s) {
        if (s == null || s.trim().isEmpty()) return "node";
        String v = s.replaceAll("[^a-zA-Z0-9._-]", "-");
        return v.length() > 30 ? v.substring(0, 30) : v;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private LinearLayout.LayoutParams lp() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weightLp() { return new LinearLayout.LayoutParams(0, dp(46), 1f); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    static class VlessNode {
        String raw, uuid, host, name, type, security, sni, pbk, sid, fp, flow, path, hostHeader;
        int port;
        Map<String, String> q = new LinkedHashMap<>();

        static VlessNode parse(String raw) {
            if (raw == null || !raw.startsWith("vless://")) throw new IllegalArgumentException("必须以 vless:// 开头");
            Uri u = Uri.parse(raw);
            VlessNode n = new VlessNode();
            n.raw = raw;
            n.uuid = u.getUserInfo();
            n.host = u.getHost();
            n.port = u.getPort();
            n.name = u.getFragment() == null || u.getFragment().isEmpty() ? "VLESS-Node" : u.getFragment();
            if (n.uuid == null || n.uuid.isEmpty() || n.host == null || n.port <= 0) throw new IllegalArgumentException("缺少 UUID、服务器或端口");
            for (String key : u.getQueryParameterNames()) n.q.put(key, u.getQueryParameter(key));
            n.type = val(n.q.get("type"), "tcp");
            n.security = val(n.q.get("security"), "none");
            n.sni = val(n.q.get("sni"), n.host);
            n.pbk = val(n.q.get("pbk"), "");
            n.sid = val(n.q.get("sid"), "");
            n.fp = val(n.q.get("fp"), "chrome");
            n.flow = val(n.q.get("flow"), "");
            n.path = val(n.q.get("path"), "/");
            n.hostHeader = val(n.q.get("host"), "");
            return n;
        }

        String toMihomoYaml() {
            String node = safeYamlName(name);
            StringBuilder s = new StringBuilder();
            s.append("mixed-port: 7890\n");
            s.append("allow-lan: true\n");
            s.append("mode: rule\n");
            s.append("log-level: info\n");
            s.append("ipv6: false\n\n");
            s.append("proxies:\n");
            s.append("  - name: ").append(yaml(node)).append("\n");
            s.append("    type: vless\n");
            s.append("    server: ").append(host).append("\n");
            s.append("    port: ").append(port).append("\n");
            s.append("    uuid: ").append(uuid).append("\n");
            s.append("    network: ").append(type).append("\n");
            s.append("    udp: true\n");
            if (!flow.isEmpty()) s.append("    flow: ").append(flow).append("\n");
            if ("reality".equalsIgnoreCase(security)) {
                s.append("    tls: true\n");
                s.append("    servername: ").append(sni).append("\n");
                s.append("    client-fingerprint: ").append(fp).append("\n");
                s.append("    reality-opts:\n");
                s.append("      public-key: ").append(pbk).append("\n");
                if (!sid.isEmpty()) s.append("      short-id: ").append(yaml(sid)).append("\n");
            } else if ("tls".equalsIgnoreCase(security)) {
                s.append("    tls: true\n");
                s.append("    servername: ").append(sni).append("\n");
            }
            if ("ws".equalsIgnoreCase(type)) {
                s.append("    ws-opts:\n");
                s.append("      path: ").append(yaml(path)).append("\n");
                if (!hostHeader.isEmpty()) {
                    s.append("      headers:\n");
                    s.append("        Host: ").append(yaml(hostHeader)).append("\n");
                }
            }
            s.append("\nproxy-groups:\n");
            s.append("  - name: PROXY\n");
            s.append("    type: select\n");
            s.append("    proxies:\n");
            s.append("      - ").append(yaml(node)).append("\n");
            s.append("      - DIRECT\n\n");
            s.append("rules:\n");
            s.append("  - MATCH,PROXY\n");
            return s.toString();
        }

        static String val(String v, String d) { return v == null || v.isEmpty() ? d : v; }
        static String safeYamlName(String s) { return s == null || s.trim().isEmpty() ? "VLESS-Node" : s.replace("\n", " ").replace("\r", " "); }
        static String yaml(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    }
}
