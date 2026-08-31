package com.example.mediaparser.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class LinkExtractorTest {
    @Test public void acceptsFullZhihuShareCopy() {
        String text = "复制打开知乎，查看这个回答：https://www.zhihu.com/question/123/answer/456?utm_psn=abc，作者补充了一段文字";
        assertEquals("https://www.zhihu.com/question/123/answer/456?utm_psn=abc", LinkExtractor.extractFirstUrl(text));
        assertEquals("zhihu", LinkExtractor.detectPlatform(text));
    }

    @Test public void acceptsBareZhihuArticleLinkInsideArbitraryText() {
        String text = "任意前缀 zhuanlan.zhihu.com/p/123456 任意后缀";
        assertEquals("zhuanlan.zhihu.com/p/123456", LinkExtractor.extractFirstUrl(text));
        assertEquals("zhihu", LinkExtractor.detectPlatform(text));
    }

    @Test public void recognizesGenericHttpsMediaDirectLink() {
        assertEquals("direct", LinkExtractor.detectPlatform("课程 https://cdn.example.com/video/lesson-01.mp4"));
    }

    @Test public void recognizesRealDouyinCommerceCommandWithoutUrl() {
        String text = "0X:/ o\\@d.aA :3pm 10/22 【立式水龙头置物架卫生间浴室厕所洗漱台壁挂式免打孔洗手台收纳架】长按此条消息，查看商品详情##jdfvc5ael99##";
        DouyinCommerceCommand command = DouyinCommerceCommand.parse(text);
        assertEquals("立式水龙头置物架卫生间浴室厕所洗漱台壁挂式免打孔洗手台收纳架", command.title);
        assertEquals("jdfvc5ael99", command.token);
        assertEquals("douyin_commerce", LinkExtractor.detectPlatform(text));
    }
}
