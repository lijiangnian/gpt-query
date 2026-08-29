package com.example.mediaparser.parser;

import com.example.mediaparser.core.LinkExtractor;
import com.example.mediaparser.model.ParseResult;

import java.util.List;

public final class ParserRegistry {
    private final List<PlatformParser> parsers = List.of(
            new DouyinParser(),
            new XhsParser(),
            new KuaishouParser(),
            new BilibiliParser(),
            new WeiboParser()
    );

    public ParseResult parseText(String input) throws ParseException {
        String extracted = LinkExtractor.extractFirstUrl(input);
        if (extracted == null && input != null && input.trim().matches("(?i)^(https?://)?[^\\s]+$")) extracted = input.trim();
        if (extracted == null) throw new ParseException("没有检测到分享链接。可以直接粘贴 App 生成的整段分享文案。");
        String url = LinkExtractor.ensureScheme(extracted);
        String platform = LinkExtractor.detectPlatform(url);
        if (platform == null) throw new ParseException("暂不支持这个链接。当前支持：抖音、小红书、快手、B站、微博。");
        for (PlatformParser p : parsers) {
            if (p.supports(url)) return p.parse(url);
        }
        throw new ParseException("已识别平台，但没有找到对应解析器。");
    }
}
