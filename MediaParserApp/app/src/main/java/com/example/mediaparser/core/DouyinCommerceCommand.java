package com.example.mediaparser.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the clipboard-only product command emitted by Douyin Shopping. */
public final class DouyinCommerceCommand {
    private static final Pattern TOKEN = Pattern.compile("##([A-Za-z0-9_-]{6,64})##");
    private static final Pattern TITLE = Pattern.compile("[【〖]([^】〗]{1,160})[】〗]");

    public final String raw;
    public final String title;
    public final String token;

    private DouyinCommerceCommand(String raw, String title, String token) {
        this.raw = raw;
        this.title = title;
        this.token = token;
    }

    public static DouyinCommerceCommand parse(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher token = TOKEN.matcher(text);
        if (!token.find()) return null;
        boolean commerceWords = text.contains("查看商品详情") || text.contains("商品详情")
                || text.contains("抖音商城") || text.contains("长按此条消息");
        if (!commerceWords) return null;
        Matcher title = TITLE.matcher(text);
        String cleanTitle = title.find() ? title.group(1).trim() : "抖音商城商品";
        return new DouyinCommerceCommand(text.trim(), cleanTitle, token.group(1));
    }

    public static boolean isCommand(String text) { return parse(text) != null; }

    public String maskedToken() {
        if (token.length() <= 6) return "••••••";
        return token.substring(0, 2) + "••••" + token.substring(token.length() - 2);
    }
}
