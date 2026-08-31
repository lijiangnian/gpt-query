package com.example.mediaparser.parser;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DouyinCommerceDataTest {
    @Test public void parsesVideoAndProductImages() throws Exception {
        String response = "{\"promotion_h5\":{" +
                "\"basic_info_data\":{\"title_info\":{\"title\":\"水龙头置物架\"}}," +
                "\"shop_info\":{\"basic_info\":{\"shop_name\":\"测试旗舰店\"}}," +
                "\"head_figure_data\":{\"media_list\":[" +
                "{\"type\":\"video\",\"content_list\":[{\"url\":\"https://v.example/a.mp4\",\"cover_url\":\"https://i.example/c.webp\"}]}," +
                "{\"type\":\"image\",\"content_list\":[{\"url\":\"https://i.example/1.webp\"},{\"url\":\"https://i.example/2.webp\"}]}" +
                "]}}}";
        JSONObject result = DouyinCommerceData.parse(response, "https://v.douyin.com/test/");
        assertEquals("抖音商城", result.getString("platform"));
        assertEquals("水龙头置物架", result.getString("title"));
        assertEquals("测试旗舰店", result.getString("author"));
        assertEquals("https://i.example/c.webp", result.getString("coverUrl"));
        assertEquals(3, result.getJSONArray("media").length());
        assertEquals("video", result.getJSONArray("media").getJSONObject(0).getString("type"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsErrorPayload() throws Exception {
        DouyinCommerceData.parse("{\"code\":11001,\"msg\":\"当前网络不稳定\"}", "");
    }
}
