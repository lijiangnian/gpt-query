package com.example.mediaparser.cloud;

import org.junit.Test;

import static org.junit.Assert.*;

public final class OpenListClientTest {
    @Test public void buildsEncodedStableDownloadUrl(){assertEquals("https://drive.example.com/d/115/%E8%A7%86%E9%A2%91/AE86%20%E6%B5%8B%E8%AF%95.mp4",OpenListClient.stableUrl("https://drive.example.com/","/115/视频/AE86 测试.mp4"));}
    @Test public void normalizesPathAndRejectsTraversal(){assertEquals("/115/a.mp4",OpenListClient.normalizePath("115//a.mp4"));try{OpenListClient.normalizePath("/115/../secret");fail("expected traversal rejection");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains(".."));}}
    @Test public void parsesRawUrlAndKeepsOnlyPlaybackHeaders()throws Exception{String body="{\"code\":200,\"data\":{\"name\":\"课程.mp4\",\"size\":123,\"raw_url\":\"https://cdn.example.com/v.mp4\",\"header\":{\"User-Agent\":\"115Browser\",\"Referer\":\"https://115.com/\",\"Authorization\":\"must-not-leak\"}}}";OpenListClient.FileResult r=OpenListClient.parseFileResult("https://drive.example.com","/115/课程.mp4",body);assertEquals("https://cdn.example.com/v.mp4",r.rawUrl);assertEquals("115Browser",r.headers.get("User-Agent"));assertFalse(r.headers.containsKey("Authorization"));assertEquals(123,r.size);}
}
