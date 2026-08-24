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

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(10, 12, 16);
    private static final int CARD = Color.rgb(24, 28, 36);
    private static final int CARD_2 = Color.rgb(31, 36, 46);
    private static final int ACCENT = Color.rgb(99, 102, 241);
    private static final int GREEN = Color.rgb(69, 214, 151);
    private static final int MUTED = Color.rgb(151, 160, 178);

    private EditText input;
    private EditText output;
    private Spinner format;
    private TextView status;
    private TextView nodeInfo;
    private VlessNode current;

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
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(root);

        TextView brand = text("VLINK", 12, true);
        brand.setTextColor(Color.rgb(135, 145, 255));
        root.addView(brand);

        TextView title = text("节点转换器", 30, true);
        LinearLayout.LayoutParams titleLp = lp();
        titleLp.topMargin = dp(4);
        root.addView(title, titleLp);

        TextView sub = text("一个链接，多客户端直接使用", 14, false);
        sub.setTextColor(MUTED);
        root.addView(sub);

        TextView local = text("● 本地离线转换 · 节点信息不会上传", 13, false);
        local.setTextColor(GREEN);
        LinearLayout.LayoutParams localLp = lp();
        localLp.topMargin = dp(12);
        root.addView(local, localLp);

        LinearLayout inputCard = card();
        LinearLayout.LayoutParams cardLp = lp();
        cardLp.topMargin = dp(20);
        root.addView(inputCard, cardLp);

        TextView inLabel = text("输入节点", 15, true);
        inputCard.addView(inLabel);

        input = new EditText(this);
        input.setHint("粘贴 vless:// 链接…");
        input.setHintTextColor(Color.rgb(105, 114, 130));
        input.setTextColor(Color.WHITE);
        input.setTextSize(13);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(4);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(round(CARD_2, 16));
        LinearLayout.LayoutParams inputLp = lp();
        inputLp.topMargin = dp(10);
        inputCard.addView(input, inputLp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = lp();
        rowLp.topMargin = dp(10);
        inputCard.addView(row1, rowLp);

        Button paste = primaryButton("粘贴并转换");
        paste.setOnClickListener(v -> pasteAndParse());
        row1.addView(paste, weightLp());

        Button url = secondaryButton("URL 导入");
        url.setOnClickListener(v -> showUrlDialog());
        LinearLayout.LayoutParams urlLp = weightLp();
        urlLp.leftMargin = dp(8);
        row1.addView(url, urlLp);

        Button clear = secondaryButton("清空");
        clear.setOnClickListener(v -> clearAll());
        LinearLayout.LayoutParams clearLp = weightLp();
        clearLp.leftMargin = dp(8);
        row1.addView(clear, clearLp);

        status = text("等待输入", 13, false);
        status.setTextColor(MUTED);
        LinearLayout.LayoutParams statusLp = lp();
        statusLp.topMargin = dp(10);
        inputCard.addView(status, statusLp);

        nodeInfo = text("", 12, false);
        nodeInfo.setTextColor(Color.rgb(188, 196, 210));
        nodeInfo.setVisibility(View.GONE);
        LinearLayout.LayoutParams infoLp = lp();
        infoLp.topMargin = dp(5);
        inputCard.addView(nodeInfo, infoLp);

        LinearLayout outputCard = card();
        LinearLayout.LayoutParams outCardLp = lp();
        outCardLp.topMargin = dp(14);
        root.addView(outputCard, outCardLp);

        TextView outLabel = text("目标客户端 / 格式", 15, true);
        outputCard.addView(outLabel);

        format = new Spinner(this);
        String[] formats = {
                "Clash Meta / Mihomo · YAML",
                "Hiddify / sing-box · JSON",
                "v2rayNG / Xray · JSON",
                "通用 VLESS · 原始链接"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, formats) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) { ((TextView)v).setTextColor(Color.WHITE); ((TextView)v).setTextSize(14); }
                return v;
            }
        };
        format.setAdapter(adapter);
        format.setBackground(round(CARD_2, 14));
        LinearLayout.LayoutParams fmtLp = lp();
        fmtLp.topMargin = dp(10);
        outputCard.addView(format, fmtLp);
        format.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { render(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        output = new EditText(this);
        output.setTextColor(Color.rgb(226, 231, 240));
        output.setTextSize(12);
        output.setBackground(round(Color.rgb(18, 21, 28), 16));
        output.setPadding(dp(14), dp(14), dp(14), dp(14));
        output.setMinLines(13);
        output.setGravity(Gravity.TOP | Gravity.START);
        output.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        output.setHorizontallyScrolling(false);
        LinearLayout.LayoutParams outputLp = lp();
        outputLp.topMargin = dp(10);
        outputCard.addView(output, outputLp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionLp = lp();
        actionLp.topMargin = dp(10);
        outputCard.addView(row2, actionLp);

        Button copy = primaryButton("复制结果");
        copy.setOnClickListener(v -> copyOutput());
        row2.addView(copy, weightLp());

        Button share = secondaryButton("分享");
        share.setOnClickListener(v -> shareOutput());
        LinearLayout.LayoutParams shareLp = weightLp();
        shareLp.leftMargin = dp(8);
        row2.addView(share, shareLp);

        Button qr = secondaryButton("二维码");
        qr.setOnClickListener(v -> showQr());
        LinearLayout.LayoutParams qrLp = weightLp();
        qrLp.leftMargin = dp(8);
        row2.addView(qr, qrLp);

        TextView compatTitle = text("兼容说明", 14, true);
        LinearLayout.LayoutParams ctLp = lp();
        ctLp.topMargin = dp(18);
        root.addView(compatTitle, ctLp);

        TextView compat = text("Clash Meta / Mihomo：输出可加入 proxies 的 YAML\nHiddify：输出 sing-box VLESS outbound JSON\nv2rayNG：输出 Xray VLESS outbound JSON\n其他支持 VLESS 的客户端：可直接复制原始 VLESS 链接", 12, false);
        compat.setTextColor(MUTED);
        compat.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams compatLp = lp();
        compatLp.topMargin = dp(6);
        root.addView(compat, compatLp);

        return scroll;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setBackground(round(CARD, 22));
        return c;
    }

    private void pasteAndParse() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (s != null) input.setText(s.toString().trim());
        }
        parseInput();
    }

    private void showUrlDialog() {
        final EditText field = new EditText(this);
        field.setHint("vless://...");
        field.setMinLines(3);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle("URL 导入")
                .setMessage("粘贴完整 VLESS 节点 URL")
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) -> {
                    input.setText(field.getText().toString().trim());
                    parseInput();
                }).show();
    }

    private void parseInput() {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) { toast("请先粘贴 VLESS 链接"); return; }
        try {
            current = VlessNode.parse(raw);
            status.setText("✓ 已识别 VLESS" + ("reality".equalsIgnoreCase(current.security) ? " · Reality" : ""));
            status.setTextColor(GREEN);
            nodeInfo.setText(current.host + ":" + current.port + "  ·  " + current.type.toUpperCase() + "  ·  " + current.name);
            nodeInfo.setVisibility(View.VISIBLE);
            render();
            toast("转换完成");
        } catch (Exception e) {
            current = null;
            output.setText("");
            status.setText("✕ 解析失败 · " + e.getMessage());
            status.setTextColor(Color.rgb(255, 118, 118));
            nodeInfo.setVisibility(View.GONE);
        }
    }

    private void render() {
        if (current == null || output == null || format == null) return;
        switch (format.getSelectedItemPosition()) {
            case 1: output.setText(current.toSingBox()); break;
            case 2: output.setText(current.toXray()); break;
            case 3: output.setText(current.raw); break;
            default: output.setText(current.toClash());
        }
    }

    private void copyOutput() {
        String s = output.getText().toString();
        if (s.isEmpty()) { toast("没有可复制的内容"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("VLink output", s));
        toast("已复制，可直接粘贴到客户端");
    }

    private void shareOutput() {
        String s = output.getText().toString();
        if (s.isEmpty()) { toast("没有可分享的内容"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, s);
        startActivity(Intent.createChooser(i, "分享转换结果"));
    }

    private void showQr() {
        String s = output.getText().toString();
        if (s.isEmpty()) { toast("没有可生成二维码的内容"); return; }
        try {
            int size = Math.min(getResources().getDisplayMetrics().widthPixels - dp(72), dp(420));
            BitMatrix m = new MultiFormatWriter().encode(s, BarcodeFormat.QR_CODE, size, size);
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) b.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(b);
            iv.setPadding(dp(12), dp(12), dp(12), dp(12));
            new AlertDialog.Builder(this).setTitle("扫码导入").setView(iv).setPositiveButton("关闭", null).show();
        } catch (Exception e) {
            toast("当前内容太长，建议使用“复制结果”导入");
        }
    }

    private void clearAll() {
        current = null;
        input.setText("");
        output.setText("");
        status.setText("等待输入");
        status.setTextColor(MUTED);
        nodeInfo.setVisibility(View.GONE);
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
    private Button secondaryButton(String s) { return styledButton(s, CARD_2, Color.rgb(226, 231, 240)); }

    private Button styledButton(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(fg);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(6), 0, dp(6), 0);
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

        String toClash() {
            StringBuilder s = new StringBuilder();
            s.append("proxies:\n");
            s.append("  - name: ").append(yaml(name)).append("\n");
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
                if (!sid.isEmpty()) s.append("      short-id: ").append(sid).append("\n");
            } else if ("tls".equalsIgnoreCase(security)) {
                s.append("    tls: true\n    servername: ").append(sni).append("\n");
            }
            if ("ws".equalsIgnoreCase(type)) {
                s.append("    ws-opts:\n      path: ").append(yaml(path)).append("\n");
                if (!hostHeader.isEmpty()) s.append("      headers:\n        Host: ").append(yaml(hostHeader)).append("\n");
            }
            return s.toString();
        }

        String toSingBox() {
            StringBuilder s = new StringBuilder();
            s.append("{\n  \"type\": \"vless\",\n  \"tag\": ").append(json(name)).append(",\n");
            s.append("  \"server\": ").append(json(host)).append(",\n  \"server_port\": ").append(port).append(",\n");
            s.append("  \"uuid\": ").append(json(uuid));
            if (!flow.isEmpty()) s.append(",\n  \"flow\": ").append(json(flow));
            if ("reality".equalsIgnoreCase(security) || "tls".equalsIgnoreCase(security)) {
                s.append(",\n  \"tls\": {\n    \"enabled\": true,\n    \"server_name\": ").append(json(sni));
                s.append(",\n    \"utls\": {\"enabled\": true, \"fingerprint\": ").append(json(fp)).append("}");
                if ("reality".equalsIgnoreCase(security)) {
                    s.append(",\n    \"reality\": {\"enabled\": true, \"public_key\": ").append(json(pbk)).append(", \"short_id\": ").append(json(sid)).append("}");
                }
                s.append("\n  }");
            }
            if ("ws".equalsIgnoreCase(type)) {
                s.append(",\n  \"transport\": {\"type\": \"ws\", \"path\": ").append(json(path));
                if (!hostHeader.isEmpty()) s.append(", \"headers\": {\"Host\": ").append(json(hostHeader)).append("}");
                s.append("}");
            }
            s.append("\n}");
            return s.toString();
        }

        String toXray() {
            StringBuilder s = new StringBuilder();
            s.append("{\n  \"protocol\": \"vless\",\n  \"tag\": ").append(json(name)).append(",\n");
            s.append("  \"settings\": {\"vnext\": [{\"address\": ").append(json(host)).append(", \"port\": ").append(port).append(", \"users\": [{\"id\": ").append(json(uuid));
            if (!flow.isEmpty()) s.append(", \"flow\": ").append(json(flow));
            s.append("}]}]},\n");
            s.append("  \"streamSettings\": {\"network\": ").append(json(type)).append(", \"security\": ").append(json(security));
            if ("reality".equalsIgnoreCase(security)) {
                s.append(", \"realitySettings\": {\"serverName\": ").append(json(sni)).append(", \"fingerprint\": ").append(json(fp)).append(", \"publicKey\": ").append(json(pbk)).append(", \"shortId\": ").append(json(sid)).append("}");
            } else if ("tls".equalsIgnoreCase(security)) {
                s.append(", \"tlsSettings\": {\"serverName\": ").append(json(sni)).append("}");
            }
            if ("ws".equalsIgnoreCase(type)) {
                s.append(", \"wsSettings\": {\"path\": ").append(json(path));
                if (!hostHeader.isEmpty()) s.append(", \"headers\": {\"Host\": ").append(json(hostHeader)).append("}");
                s.append("}");
            }
            s.append("}\n}");
            return s.toString();
        }

        static String val(String v, String d) { return v == null || v.isEmpty() ? d : v; }
        static String yaml(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
        static String json(String s) { return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")) + "\""; }
    }
}
