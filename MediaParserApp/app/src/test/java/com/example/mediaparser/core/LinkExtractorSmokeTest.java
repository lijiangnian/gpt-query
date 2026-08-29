package com.example.mediaparser.core;

public final class LinkExtractorSmokeTest {
    private static int passed = 0;
    public static void main(String[] args) {
        check("抖音短链", "5.35 去抖音看看 https://v.douyin.com/kB9dI20w7vk/ 复制此链接", "douyin", "https://v.douyin.com/kB9dI20w7vk/");
        check("抖音ies", "https://www.iesdouyin.com/share/video/7373737373737373737/", "douyin", "https://www.iesdouyin.com/share/video/7373737373737373737/");
        check("抖音中文标点", "看看这个：https://v.douyin.com/abc123/，真的不错", "douyin", "https://v.douyin.com/abc123/");
        check("抖音无协议", "v.douyin.com/abc123/ 复制此链接", "douyin", "v.douyin.com/abc123/");
        check("小红书com", "小红书笔记 http://xhslink.com/o/1fRz2qqwhkI 复制后打开", "xhs", "http://xhslink.com/o/1fRz2qqwhkI");
        check("小红书cn", "看看 https://xhslink.cn/o/9hocmhutQ15 打开小红书", "xhs", "https://xhslink.cn/o/9hocmhutQ15");
        check("小红书长链", "https://www.xiaohongshu.com/explore/66f8f8f8f8f8f8f8f8f8f8f8?xhsshare=WeixinSession", "xhs", "https://www.xiaohongshu.com/explore/66f8f8f8f8f8f8f8f8f8f8f8?xhsshare=WeixinSession");
        check("小红书标点", "http://xhslink.com/A1B2C3。", "xhs", "http://xhslink.com/A1B2C3");
        check("快手短链", "快手看看：https://v.kuaishou.com/abcdEF，开黑走起", "kuaishou", "https://v.kuaishou.com/abcdEF");
        check("快手长链", "https://www.kuaishou.com/short-video/3x7m8nbnxyg2s3q", "kuaishou", "https://www.kuaishou.com/short-video/3x7m8nbnxyg2s3q");
        check("快手photo", "https://www.kuaishou.com/photo/abc123", "kuaishou", "https://www.kuaishou.com/photo/abc123");
        check("快手标点", "https://v.kuaishou.com/xyz123!", "kuaishou", "https://v.kuaishou.com/xyz123");
        check("B站短链", "B站视频：https://b23.tv/abcDEFg 分享给你", "bilibili", "https://b23.tv/abcDEFg");
        check("B站BV", "https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=333.1007", "bilibili", "https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=333.1007");
        check("B站av", "https://www.bilibili.com/video/av170001", "bilibili", "https://www.bilibili.com/video/av170001");
        check("B站逗号", "https://b23.tv/abcdefg, 超好看", "bilibili", "https://b23.tv/abcdefg");
        check("微博tv", "微博视频：https://weibo.com/tv/show/1034:4912345678901234", "weibo", "https://weibo.com/tv/show/1034:4912345678901234");
        check("微博video", "https://video.weibo.com/show?fid=1034:4912345678901234&from=old", "weibo", "https://video.weibo.com/show?fid=1034:4912345678901234&from=old");
        check("微博详情", "https://weibo.com/1234567890/5000000000000000", "weibo", "https://weibo.com/1234567890/5000000000000000");
        check("微博移动", "https://m.weibo.cn/status/5000000000000000", "weibo", "https://m.weibo.cn/status/5000000000000000");
        check("混合优先第一个", "先看：https://b23.tv/xyz 然后：https://v.douyin.com/xyz123/", "bilibili", "https://b23.tv/xyz");
        check("换行", "看看：\nhttps://www.kuaishou.com/short-video/9x9x9x9x9x9，\n再看微博", "kuaishou", "https://www.kuaishou.com/short-video/9x9x9x9x9x9");
        invalid("普通文本", "这是普通文本，没有链接", null);
        invalid("不支持网站", "https://example.com/a", "https://example.com/a");
        if (passed != 24) throw new AssertionError("expected 24 cases, got " + passed);
        System.out.println("LinkExtractor regression: 24/24 PASS");
    }

    private static void check(String name, String input, String platform, String url) {
        String gotUrl = LinkExtractor.extractFirstUrl(input);
        String gotPlatform = LinkExtractor.detectPlatform(input);
        if (!url.equals(gotUrl)) throw new AssertionError(name + " url expected=" + url + " got=" + gotUrl);
        if (!platform.equals(gotPlatform)) throw new AssertionError(name + " platform expected=" + platform + " got=" + gotPlatform);
        passed++;
    }

    private static void invalid(String name, String input, String expectedUrl) {
        String gotUrl = LinkExtractor.extractFirstUrl(input);
        String gotPlatform = LinkExtractor.detectPlatform(input);
        if (expectedUrl == null ? gotUrl != null : !expectedUrl.equals(gotUrl)) throw new AssertionError(name + " url got=" + gotUrl);
        if (gotPlatform != null) throw new AssertionError(name + " misdetected=" + gotPlatform);
        passed++;
    }
}
