package com.example.mediaparser.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure share-text inspection. It never logs or opens a provider account. */
public final class ShareTextAnalyzer {
    private static final Pattern CODE = Pattern.compile("(?i)(?:提取码|访问码|密码|口令|code)\\s*[:：]?\\s*([A-Za-z0-9]{3,12})");
    private ShareTextAnalyzer() {}

    public static Analysis analyze(String text) {
        String raw = text == null ? "" : text.trim();
        String url = LinkExtractor.extractFirstUrl(raw);
        if (url == null && raw.startsWith("/")) return new Analysis("openlist", "", "", raw, "OpenList 文件路径");
        url = url == null ? "" : LinkExtractor.ensureScheme(url);
        String host = "";
        try { host = URI.create(url).getHost(); } catch (Exception ignored) {}
        host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String provider = provider(host);
        Matcher m = CODE.matcher(raw);
        String code = m.find() ? m.group(1) : "";
        String path = openListPath(url);
        return new Analysis(provider, url, code, path, label(provider));
    }

    public static String openListPath(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            URI u = URI.create(url);
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.startsWith("/d/")) path = path.substring(2);
            else if ((path.equals("/") || path.isBlank()) && u.getFragment() != null) {
                String f = u.getFragment();
                int q = f.indexOf('?'); if (q >= 0) f = f.substring(0, q);
                path = f.startsWith("/") ? f : "/" + f;
            }
            return URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception e) { return ""; }
    }

    private static String provider(String host) {
        if (host.contains("115.com") || host.contains("115cdn")) return "115";
        if (host.contains("alipan.com") || host.contains("aliyundrive.com")) return "alipan";
        if (host.contains("quark.cn") || host.contains("pan.quark")) return "quark";
        if (host.contains("pan.baidu.com") || host.contains("yun.baidu.com")) return "baidu";
        if (host.contains("cloud.189.cn")) return "tianyi";
        if (host.contains("caiyun.139.com")) return "caiyun";
        if (host.contains("weiyun.com")) return "weiyun";
        return host.isBlank() ? "unknown" : "openlist";
    }

    private static String label(String p) {
        switch (p) {
            case "115": return "115 网盘";
            case "alipan": return "阿里云盘";
            case "quark": return "夸克网盘";
            case "baidu": return "百度网盘";
            case "tianyi": return "天翼云盘";
            case "caiyun": return "中国移动云盘";
            case "weiyun": return "腾讯微云";
            case "openlist": return "OpenList / AList";
            default: return "未识别来源";
        }
    }

    public static final class Analysis {
        public final String provider, url, code, path, label;
        Analysis(String provider, String url, String code, String path, String label) {
            this.provider = provider; this.url = url; this.code = code; this.path = path; this.label = label;
        }
        public boolean providerShare() { return !provider.equals("openlist") && !provider.equals("unknown"); }
    }
}
