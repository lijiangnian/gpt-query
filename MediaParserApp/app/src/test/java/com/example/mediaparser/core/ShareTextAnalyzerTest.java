package com.example.mediaparser.core;

import org.junit.Test;

import static org.junit.Assert.*;

public final class ShareTextAnalyzerTest {
    @Test public void recognizesDomesticDriveShareAndCode(){ShareTextAnalyzer.Analysis a=ShareTextAnalyzer.analyze("复制这段内容 https://pan.baidu.com/s/1abc 提取码：A8e6");assertEquals("baidu",a.provider);assertEquals("A8e6",a.code);assertTrue(a.providerShare());}
    @Test public void recognizesCommonProviders(){assertEquals("115",ShareTextAnalyzer.analyze("https://115.com/s/abc?password=1234 访问码 7x9K").provider);assertEquals("alipan",ShareTextAnalyzer.analyze("https://www.alipan.com/s/abc").provider);assertEquals("quark",ShareTextAnalyzer.analyze("https://pan.quark.cn/s/abc").provider);assertEquals("tianyi",ShareTextAnalyzer.analyze("https://cloud.189.cn/t/abc").provider);}
    @Test public void extractsOpenListDownloadPath(){ShareTextAnalyzer.Analysis a=ShareTextAnalyzer.analyze("https://drive.example.com/d/115/%E8%A7%86%E9%A2%91/a.mp4");assertEquals("/115/视频/a.mp4",a.path);assertFalse(a.providerShare());}
    @Test public void preservesExplicitOpenListPath(){ShareTextAnalyzer.Analysis a=ShareTextAnalyzer.analyze("/夸克/课程/第一课.mp4");assertEquals("/夸克/课程/第一课.mp4",a.path);}
}
