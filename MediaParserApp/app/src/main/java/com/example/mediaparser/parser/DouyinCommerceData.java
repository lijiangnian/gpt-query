package com.example.mediaparser.parser;

import org.json.JSONArray;
import org.json.JSONObject;

/** Converts Douyin Shop's public H5 product response into the app's neutral WebView result. */
public final class DouyinCommerceData {
    private DouyinCommerceData() {}

    public static JSONObject parse(String response, String sourceUrl) throws Exception {
        JSONObject root = new JSONObject(response == null ? "{}" : response);
        JSONObject promotion = root.optJSONObject("promotion_h5");
        if (promotion == null) {
            String msg = root.optString("msg", "商品页没有返回公开媒体数据");
            throw new IllegalStateException(msg + (root.has("code") ? "（" + root.optInt("code") + "）" : ""));
        }

        JSONObject basic = promotion.optJSONObject("basic_info_data");
        JSONObject titleInfo = basic == null ? null : basic.optJSONObject("title_info");
        JSONObject shop = promotion.optJSONObject("shop_info");
        JSONObject shopBasic = shop == null ? null : shop.optJSONObject("basic_info");
        String title = titleInfo == null ? "" : titleInfo.optString("title", "");
        String author = shopBasic == null ? "" : shopBasic.optString("shop_name", "");

        JSONArray outputMedia = new JSONArray();
        String cover = "";
        JSONObject head = promotion.optJSONObject("head_figure_data");
        JSONArray groups = head == null ? null : head.optJSONArray("media_list");
        int videoNumber = 0, imageNumber = 0;
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String type = group.optString("type", "image");
            JSONArray content = group.optJSONArray("content_list");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject item = content.optJSONObject(j);
                if (item == null) continue;
                String url = item.optString("url", "").replaceFirst("^http:", "https:");
                if (!url.startsWith("https://")) continue;
                JSONObject media = new JSONObject();
                if ("video".equals(type)) {
                    videoNumber++;
                    media.put("type", "video");
                    media.put("label", videoNumber == 1 ? "商品主视频" : "商品视频 " + videoNumber);
                    String videoCover = item.optString("cover_url", "").replaceFirst("^http:", "https:");
                    if (cover.isBlank() && videoCover.startsWith("https://")) cover = videoCover;
                } else {
                    imageNumber++;
                    media.put("type", "image");
                    media.put("label", "商品图片 " + imageNumber);
                    if (cover.isBlank()) cover = url;
                }
                media.put("url", url);
                outputMedia.put(media);
            }
        }
        if (outputMedia.length() == 0) throw new IllegalStateException("商品页已打开，但商品没有返回可下载的视频或图片");

        JSONObject out = new JSONObject();
        out.put("platform", "抖音商城");
        out.put("sourceUrl", sourceUrl == null ? "" : sourceUrl);
        out.put("title", title);
        out.put("author", author);
        out.put("description", title);
        out.put("coverUrl", cover);
        out.put("media", outputMedia);
        return out;
    }
}
