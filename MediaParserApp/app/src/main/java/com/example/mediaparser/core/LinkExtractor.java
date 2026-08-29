package com.example.mediaparser.core;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LinkExtractor {
    private static final Pattern HTTP = Pattern.compile("(https?://[^\\s\\u3000\\u00A0，。！？、；：【】（）《》“”‘’]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE = Pattern.compile("(?:^|\\s)((?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}/[^\\s\\u3000\\u00A0，。！？、；：【】（）《》“”‘’]+)");

    private LinkExtractor() {}

    public static String extractFirstUrl(String text) {
        if (text == null) return null;
        Matcher m = HTTP.matcher(text);
        if (m.find()) return clean(m.group(1));
        m = BARE.matcher(text);
        if (m.find()) return clean(m.group(1));
        return null;
    }

    public static String ensureScheme(String url) {
        if (url == null) return null;
        return url.matches("(?i)^https?://.*") ? url : "https://" + url;
    }

    public static String detectPlatform(String textOrUrl) {
        String raw = extractFirstUrl(textOrUrl);
        if (raw == null && textOrUrl != null && !textOrUrl.contains(" ")) raw = textOrUrl.trim();
        if (raw == null) return null;
        String u = ensureScheme(raw);
        String host;
        try {
            host = URI.create(u).getHost();
        } catch (Exception e) {
            return null;
        }
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("v.douyin.com") || host.endsWith(".douyin.com") || host.endsWith(".iesdouyin.com") || host.endsWith(".snssdk.com")) return "douyin";
        if (host.equals("xhslink.com") || host.equals("xhslink.cn") || host.endsWith(".xiaohongshu.com")) return "xhs";
        if (host.equals("v.kuaishou.com") || host.endsWith(".kuaishou.com")) return "kuaishou";
        if (host.equals("b23.tv") || host.endsWith(".bilibili.com")) return "bilibili";
        if (host.equals("weibo.com") || host.endsWith(".weibo.com") || host.equals("m.weibo.cn") || host.endsWith(".weibo.cn")) return "weibo";
        return null;
    }

    private static String clean(String s) {
        return s.replaceAll("[，。！？、；：.,!?;]+$", "");
    }
}
