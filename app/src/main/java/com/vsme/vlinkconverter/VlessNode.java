package com.vsme.vlinkconverter;

import android.net.Uri;
import java.util.LinkedHashMap;
import java.util.Map;

final class VlessNode {
    final String raw, uuid, host, name, type, security, encryption, sni, pbk, sid, fp, flow, path, hostHeader, serviceName, xhttpMode, spx;
    final int port;
    final Map<String, String> q;

    private VlessNode(String raw, String uuid, String host, int port, String name, Map<String,String> q) {
        this.raw = raw;
        this.uuid = uuid;
        this.host = host;
        this.port = port;
        this.name = name;
        this.q = q;
        this.type = val(q.get("type"), "tcp");
        this.security = val(q.get("security"), "none");
        this.encryption = val(q.get("encryption"), "none");
        this.sni = val(q.get("sni"), host);
        this.pbk = val(q.get("pbk"), "");
        this.sid = val(q.get("sid"), "");
        this.fp = val(q.get("fp"), "chrome");
        this.flow = val(q.get("flow"), "");
        this.path = val(q.get("path"), "/");
        this.hostHeader = val(q.get("host"), "");
        this.serviceName = val(q.get("serviceName"), val(q.get("service-name"), ""));
        this.xhttpMode = val(q.get("mode"), "");
        this.spx = val(q.get("spx"), "/");
    }

    static VlessNode parse(String raw) {
        if (raw == null || !raw.startsWith("vless://")) throw new IllegalArgumentException("必须以 vless:// 开头");
        Uri u = Uri.parse(raw.trim());
        String uuid = u.getUserInfo();
        String host = u.getHost();
        int port = u.getPort();
        String name = u.getFragment() == null || u.getFragment().trim().isEmpty() ? "VLESS-Node" : u.getFragment();
        if (uuid == null || uuid.trim().isEmpty()) throw new IllegalArgumentException("缺少 UUID");
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("缺少服务器地址");
        if (port <= 0) throw new IllegalArgumentException("缺少端口");
        Map<String,String> q = new LinkedHashMap<>();
        for (String key : u.getQueryParameterNames()) q.put(key, u.getQueryParameter(key));
        VlessNode n = new VlessNode(raw.trim(), uuid, host, port, name, q);
        if ("reality".equalsIgnoreCase(n.security)) {
            if (n.pbk.isEmpty()) throw new IllegalArgumentException("Reality 缺少 public key (pbk)");
            if (n.sni.isEmpty()) throw new IllegalArgumentException("Reality 缺少 SNI");
        }
        return n;
    }

    String summary() {
        StringBuilder s = new StringBuilder();
        s.append(host).append(":").append(port).append("  ·  ").append(type.toUpperCase()).append("  ·  ").append(security.toUpperCase());
        if ("reality".equalsIgnoreCase(security)) {
            s.append("\nSNI: ").append(sni).append("  ·  FP: ").append(fp);
            if (!sid.isEmpty()) s.append("  ·  SID: ").append(sid);
        }
        return s.toString();
    }

    String toMihomoYaml() {
        String node = safeName(name);
        StringBuilder s = new StringBuilder();
        s.append("mixed-port: 7890\nallow-lan: true\nmode: rule\nlog-level: info\nipv6: false\n\nproxies:\n");
        s.append("  - name: ").append(yaml(node)).append("\n");
        s.append("    type: vless\n    server: ").append(host).append("\n    port: ").append(port).append("\n");
        s.append("    uuid: ").append(uuid).append("\n    udp: true\n");
        if (encryption == null || encryption.isEmpty() || "none".equalsIgnoreCase(encryption)) s.append("    encryption: \"\"\n");
        else s.append("    encryption: ").append(yaml(encryption)).append("\n");
        if (!flow.isEmpty()) s.append("    flow: ").append(flow).append("\n");
        if (!"tcp".equalsIgnoreCase(type)) s.append("    network: ").append(type).append("\n");

        if ("reality".equalsIgnoreCase(security)) {
            s.append("    tls: true\n    servername: ").append(sni).append("\n");
            s.append("    client-fingerprint: ").append(fp).append("\n");
            s.append("    reality-opts:\n      public-key: ").append(pbk).append("\n");
            if (!sid.isEmpty()) s.append("      short-id: ").append(yaml(sid)).append("\n");
        } else if ("tls".equalsIgnoreCase(security)) {
            s.append("    tls: true\n    servername: ").append(sni).append("\n");
            if (!fp.isEmpty()) s.append("    client-fingerprint: ").append(fp).append("\n");
        }

        if ("ws".equalsIgnoreCase(type)) {
            s.append("    ws-opts:\n      path: ").append(yaml(path)).append("\n");
            if (!hostHeader.isEmpty()) s.append("      headers:\n        Host: ").append(yaml(hostHeader)).append("\n");
        } else if ("grpc".equalsIgnoreCase(type)) {
            s.append("    grpc-opts:\n      grpc-service-name: ").append(yaml(serviceName)).append("\n");
        } else if ("xhttp".equalsIgnoreCase(type)) {
            s.append("    xhttp-opts:\n      path: ").append(yaml(path)).append("\n");
            if (!hostHeader.isEmpty()) s.append("      host: ").append(yaml(hostHeader)).append("\n");
            if (!xhttpMode.isEmpty()) s.append("      mode: ").append(yaml(xhttpMode)).append("\n");
        } else if ("h2".equalsIgnoreCase(type) || "http".equalsIgnoreCase(type)) {
            s.append("    h2-opts:\n");
            if (!path.isEmpty()) s.append("      path: ").append(yaml(path)).append("\n");
            if (!hostHeader.isEmpty()) s.append("      host:\n        - ").append(yaml(hostHeader)).append("\n");
        }

        s.append("\nproxy-groups:\n  - name: PROXY\n    type: select\n    proxies:\n      - ").append(yaml(node)).append("\n      - DIRECT\n\n");
        s.append("rules:\n  - MATCH,PROXY\n");
        return s.toString();
    }

    private static String val(String v, String d) { return v == null || v.isEmpty() ? d : v; }
    private static String safeName(String v) { return v == null || v.trim().isEmpty() ? "VLESS-Node" : v.replace("\n", " ").replace("\r", " "); }
    private static String yaml(String v) { String x = v == null ? "" : v; return "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
