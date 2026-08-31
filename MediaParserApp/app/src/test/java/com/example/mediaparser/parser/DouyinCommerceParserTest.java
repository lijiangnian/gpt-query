package com.example.mediaparser.parser;

import com.example.mediaparser.model.ParseResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class DouyinCommerceParserTest {
    @Test public void prefersCompatibleH264Normal720p() throws Exception {
        JSONArray list = new JSONArray()
                .put(item("https://cdn.example/h265-1080.mp4", "0:MP4|2:h265_hvc1|4:1080p|5:adapt"))
                .put(item("https://cdn.example/h264-540.mp4", "0:MP4|2:h264|4:540p|5:normal"))
                .put(item("https://cdn.example/h264-720.mp4", "0:MP4|2:h264|4:720p|5:normal"));
        assertEquals("https://cdn.example/h264-720.mp4", DouyinParser.bestJingxuanUrl(list));
    }

    @Test public void parsesJingxuanSsrPayload() throws Exception {
        JSONObject result = new JSONObject()
                .put("title", "商品演示视频")
                .put("abstract", "公开商品视频")
                .put("cover_image_url", "https://img.example/cover.jpg")
                .put("media_user", new JSONObject().put("screen_name", "测试商家"))
                .put("video_model", new JSONObject().put("video_list", new JSONArray()
                        .put(item("https://cdn.example/product.mp4", "0:MP4|2:h264|4:720p|5:normal"))).toString());
        JSONObject root = new JSONObject().put("data", new JSONObject().put("storeState", new JSONObject()
                .put("detail", new JSONObject().put("videoData", new JSONObject().put("result", result)))));
        String html = "<script>window._SSR_DATA=" + root + ";</script>";
        ParseResult parsed = DouyinParser.parseJingxuan(html, "https://jingxuan.douyin.com/m/video/1234567890123456789");
        assertNotNull(parsed);
        assertEquals("抖音商城视频", parsed.platform);
        assertEquals("商品演示视频", parsed.title);
        assertEquals("测试商家", parsed.author);
        assertEquals(2, parsed.media.size());
        assertTrue(parsed.media.get(0).url.endsWith("product.mp4"));
    }

    private static JSONObject item(String url, String gear) throws Exception {
        return new JSONObject().put("main_url", url).put("gear_des_key", gear);
    }
}
