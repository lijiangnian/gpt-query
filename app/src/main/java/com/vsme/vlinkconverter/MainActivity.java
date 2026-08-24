package com.vsme.vlinkconverter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private EditText input;
    private EditText output;
    private Spinner format;
    private VlessNode current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(14, 16, 20));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = text("VLink 转换器", 28, true);
        root.addView(title);
        TextView sub = text("VLESS → Clash Meta / sing-box / Xray", 14, false);
        sub.setTextColor(Color.rgb(160, 170, 185));
        root.addView(sub);

        TextView local = text("● 本地离线处理 · 不上传节点信息", 13, false);
        local.setTextColor(Color.rgb(110, 220, 160));
        LinearLayout.LayoutParams localLp = lp();
        localLp.topMargin = dp(10);
        root.addView(local, localLp);

        input = new EditText(this);
        input.setHint("粘贴 vless:// 链接");
        input.setHintTextColor(Color.rgb(115, 120, 130));
        input.setTextColor(Color.WHITE);
        input.setBackgroundColor(Color.rgb(30, 34, 42));
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        input.setMinLines(5);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams inputLp = lp();
        inputLp.topMargin = dp(18);
        root.addView(input, inputLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = lp();
        rowLp.topMargin = dp(12);
        root.addView(row, rowLp);

        Button paste = button("粘贴并解析");
        paste.setOnClickListener(v -> pasteAndParse());
        row.addView(paste, weightLp());

        Button importUrl = button("从URL导入");
        importUrl.setOnClickListener(v -> showUrlDialog());
        LinearLayout.LayoutParams b2 = weightLp();
        b2.leftMargin = dp(8);
        row.addView(importUrl, b2);

        Button clear = button("清空");
        clear.setOnClickListener(v -> clearAll());
        LinearLayout.LayoutParams b3 = weightLp();
        b3.leftMargin = dp(8);
        row.addView(clear, b3);

        TextView fmtLabel = text("输出格式", 15, true);
        LinearLayout.LayoutParams flp = lp();
        flp.topMargin = dp(22);
        root.addView(fmtLabel, flp);

        format = new Spinner(this);
        String[] formats = {"Clash Meta YAML", "sing-box JSON", "Xray JSON"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, formats);
        format.setAdapter(adapter);
        format.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { render(); }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        root.addView(format, lp());

        output = new EditText(this);
        output.setTextColor(Color.rgb(225, 230, 238));
        output.setBackgroundColor(Color.rgb(24, 28, 35));
        output.setPadding(dp(14), dp(14), dp(14), dp(14));
        output.setMinLines(15);
        output.setGravity(Gravity.TOP | Gravity.START);
        output.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        output.setHorizontallyScrolling(false);
        LinearLayout.LayoutParams outLp = lp();
        outLp.topMargin = dp(10);
        root.addView(output, outLp);

        Button copy = button("复制结果");
        copy.setOnClickListener(v -> copyOutput());
        LinearLayout.LayoutParams copyLp = lp();
        copyLp.topMargin = dp(12);
        root.addView(copy, copyLp);

        TextView tip = text("说明：Clash Meta 通常使用 YAML 配置，不存在通用的单节点 clash:// 链接。本工具会把 VLESS Reality 参数转换成可直接加入 proxies 的配置。", 12, false);
        tip.setTextColor(Color.rgb(130, 138, 150));
        LinearLayout.LayoutParams tipLp = lp();
        tipLp.topMargin = dp(14);
        root.addView(tip, tipLp);

        return scroll;
    }

    private void pasteAndParse() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (text != null) input.setText(text.toString().trim());
        }
        parseInput();
    }

    private void showUrlDialog() {
        final EditText field = new EditText(this);
        field.setHint("vless://...");
        field.setSingleLine(false);
        field.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("从URL导入")
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("提交", (d, w) -> {
                    String s = field.getText().toString().trim();
                    if (!s.startsWith("vless://")) {
                        Toast.makeText(this, "必须为 vless:// URL", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    input.setText(s);
                    parseInput();
                }).show();
    }

    private void parseInput() {
        try {
            current = VlessNode.parse(input.getText().toString().trim());
            render();
            Toast.makeText(this, "解析成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            current = null;
            output.setText("");
            Toast.makeText(this, "解析失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void render() {
        if (current == null || output == null || format == null) return;
        int p = format.getSelectedItemPosition();
        if (p == 1) output.setText(current.toSingBox());
        else if (p == 2) output.setText(current.toXray());
        else output.setText(current.toClash());
    }

    private void copyOutput() {
        String s = output.getText().toString();
        if (s.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("VLink output", s));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private void clearAll() {
        current = null;
        input.setText("");
        output.setText("");
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams lp() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weightLp() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    static class VlessNode {
        String uuid, host, name, type, security, sni, pbk, sid, fp, flow;
        int port;
        Map<String, String> q = new LinkedHashMap<>();

        static VlessNode parse(String raw) {
            if (raw == null || !raw.startsWith("vless://")) throw new IllegalArgumentException("不是有效的 vless:// 链接");
            Uri u = Uri.parse(raw);
            VlessNode n = new VlessNode();
            n.uuid = u.getUserInfo();
            n.host = u.getHost();
            n.port = u.getPort();
            n.name = u.getFragment() == null || u.getFragment().isEmpty() ? "VLESS-Node" : u.getFragment();
            if (n.uuid == null || n.host == null || n.port <= 0) throw new IllegalArgumentException("缺少 UUID、服务器或端口");
            for (String key : u.getQueryParameterNames()) n.q.put(key, u.getQueryParameter(key));
            n.type = val(n.q.get("type"), "tcp");
            n.security = val(n.q.get("security"), "none");
            n.sni = val(n.q.get("sni"), n.host);
            n.pbk = val(n.q.get("pbk"), "");
            n.sid = val(n.q.get("sid"), "");
            n.fp = val(n.q.get("fp"), "chrome");
            n.flow = val(n.q.get("flow"), "");
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
            }
            return s.toString();
        }

        String toSingBox() {
            return "{\n" +
                    "  \"type\": \"vless\",\n" +
                    "  \"tag\": " + json(name) + ",\n" +
                    "  \"server\": " + json(host) + ",\n" +
                    "  \"server_port\": " + port + ",\n" +
                    "  \"uuid\": " + json(uuid) + ",\n" +
                    (flow.isEmpty() ? "" : "  \"flow\": " + json(flow) + ",\n") +
                    "  \"tls\": {\"enabled\": " + ("reality".equalsIgnoreCase(security) ? "true" : "false") + ", \"server_name\": " + json(sni) + ", \"utls\": {\"enabled\": true, \"fingerprint\": " + json(fp) + "}, \"reality\": {\"enabled\": " + ("reality".equalsIgnoreCase(security) ? "true" : "false") + ", \"public_key\": " + json(pbk) + ", \"short_id\": " + json(sid) + "}}\n" +
                    "}";
        }

        String toXray() {
            return "{\n" +
                    "  \"protocol\": \"vless\",\n" +
                    "  \"tag\": " + json(name) + ",\n" +
                    "  \"settings\": {\"vnext\": [{\"address\": " + json(host) + ", \"port\": " + port + ", \"users\": [{\"id\": " + json(uuid) + (flow.isEmpty() ? "" : ", \"flow\": " + json(flow)) + "}]}]},\n" +
                    "  \"streamSettings\": {\"network\": " + json(type) + ", \"security\": " + json(security) + ", \"realitySettings\": {\"serverName\": " + json(sni) + ", \"fingerprint\": " + json(fp) + ", \"publicKey\": " + json(pbk) + ", \"shortId\": " + json(sid) + "}}\n" +
                    "}";
        }

        static String val(String v, String d) { return v == null || v.isEmpty() ? d : v; }
        static String yaml(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
        static String json(String s) { return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")) + "\""; }
    }
}
