package com.example.mediaparser.parser;

import org.junit.Test;

import static org.junit.Assert.*;

public final class ZhihuParserTest {
    @Test public void readsMetadataAndCleansHtml(){String h="<meta property=\"og:title\" content=\"测试 &amp; 标题\"><meta name=\"author\" content=\"李四\"><title>备用</title>";assertEquals("测试 & 标题",ZhihuParser.meta(h,"property","og:title"));assertEquals("李四",ZhihuParser.meta(h,"name","author"));assertEquals("第一段\n第二段",ZhihuParser.cleanHtml("<p>第一段</p><p>第二段</p>"));}
    @Test public void extractsLongestJsonContent(){String h="{\"content\":\"短内容但超过二十个字符用于忽略\",\"x\":{\"content\":\"<p>这是更长的知乎回答正文，包含中文标点。</p><p>第二段内容。</p>\"}}";assertTrue(ZhihuParser.cleanHtml(ZhihuParser.firstJsonContent(h)).contains("第二段内容"));}
    @Test public void extractsVideoUrls(){String h="{\"url\":\"https:\\/\\/video.zhimg.com\\/a.mp4?sign=1\"}<video src=\"https://video.zhimg.com/b.m3u8\">";assertEquals(2,ZhihuParser.mediaUrls(h).size());}
    @Test public void rejectsGenericZhihuSiteDescription(){assertTrue(ZhihuParser.isGenericSiteText("知乎，中文互联网高质量的问答社区和创作者聚集的原创内容平台"));assertFalse(ZhihuParser.isGenericSiteText("这是用户回答的真实内容。"));}
    @Test public void removesZhihuSuffixFromTitle(){assertEquals("这是问题标题？",ZhihuParser.cleanTitle("这是问题标题？ - 知乎"));}
}
