package com.example.mediaparser.parser;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.model.ParseResult;
import com.example.mediaparser.net.HttpClient;
import com.example.mediaparser.util.JsonUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WeiboParser implements PlatformParser {
    private static final Pattern FID = Pattern.compile("(?:fid=|tv/show/)([^?&/]+)");
    private static final Pattern COLON_ID = Pattern.compile("(\\d+:\\d+)");
    private static final Pattern MID = Pattern.compile("(?:/|\\b)(\\d{16})(?:[/?#]|$)");
    private static final Pattern STATUS_TOKEN = Pattern.compile("(?:weibo\\.com/[^/]+/|m\\.weibo\\.cn/(?:status|detail)/)([0-9A-Za-z]{6,20})");
    private static volatile String visitorCookie = "";
    private static volatile long visitorExpireAt = 0;

    @Override public String platformName() { return "微博"; }
    @Override public boolean supports(String url) { return url.contains("weibo.com") || url.contains("weibo.cn"); }

    @Override
    public ParseResult parse(String inputUrl) throws ParseException {
        try {
            String id = extractId(inputUrl);
            if (id == null) {
                HttpClient.Response rr = HttpClient.get(inputUrl, Map.of("User-Agent", HttpClient.DESKTOP_UA));
                id = extractId(rr.finalUrl);
            }
            if (id == null) throw new ParseException("没有从微博链接中识别到视频/微博编号。");

            String cookie = getVisitorCookie();
            ParseResult fromComponent = parseComponent(id, inputUrl, cookie);
            if (fromComponent != null) return fromComponent;

            String statusId = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            ParseResult fromMobile = parseMobileStatus(statusId, inputUrl, cookie);
            if (fromMobile != null) return fromMobile;
            throw new ParseException("微博公开接口没有返回可解析的媒体，可能是登录墙、权限限制或作品已失效。");
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("微博解析失败：" + friendly(e), e);
        }
    }

    private ParseResult parseComponent(String id, String source, String cookie) throws Exception {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", HttpClient.DESKTOP_UA);
        h.put("Referer", "https://weibo.com/tv/show/" + id);
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("X-Requested-With", "XMLHttpRequest");
        if (!cookie.isBlank()) h.put("Cookie", cookie);
        String payload = "{\"Component_Play_Playinfo\":{\"oid\":\"" + id.replace("\"", "") + "\"}}";
        String body = "data=" + enc(payload);
        HttpClient.Response r = HttpClient.post("https://weibo.com/tv/api/component?page=/tv/show/" + enc(id), h, body);
        if (r.body.isBlank() || !r.body.trim().startsWith("{")) return null;
        JSONObject root = new JSONObject(r.body);
        JSONObject data = JsonUtil.object(root, "data", "Component_Play_Playinfo");
        if (data == null) return null;
        JSONObject urls = data.optJSONObject("urls");
        String video = bestUrl(urls);
        if (video.isBlank()) return null;
        String title = stripHtml(data.optString("title", ""));
        String author = data.optString("author", data.optString("nickname", ""));
        String cover = JsonUtil.normalizeUrl(data.optString("cover_image", ""));
        String mid = String.valueOf(data.opt("mid"));
        ParseResult meta = null;
        if (!mid.isBlank() && !mid.equals("null")) meta = parseMobileStatus(mid, source, cookie);

        ParseResult.Builder b = ParseResult.builder(platformName(), source)
                .title(meta != null && !meta.title.isBlank() ? meta.title : title)
                .author(meta != null && !meta.author.isBlank() ? meta.author : author)
                .description(meta != null ? meta.description : title)
                .coverUrl(meta != null && !meta.coverUrl.isBlank() ? meta.coverUrl : cover);
        Map<String, String> mh = new LinkedHashMap<>();
        mh.put("Referer", "https://weibo.com/");
        mh.put("User-Agent", HttpClient.DESKTOP_UA);
        b.add(new MediaItem(MediaItem.Type.VIDEO, "微博视频", video, mh));
        if (!video.toLowerCase().contains(".m3u8")) b.add(MediaItem.audioTrack("视频完整音轨 · M4A", video, mh));
        return b.build();
    }

    private ParseResult parseMobileStatus(String id, String source, String cookie) throws Exception {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", HttpClient.IPHONE_UA);
        h.put("Referer", "https://m.weibo.cn/");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("MWeibo-Pwa", "1");
        h.put("Accept", "application/json, text/plain, */*");
        if (!cookie.isBlank()) h.put("Cookie", cookie);
        HttpClient.Response r = HttpClient.get("https://m.weibo.cn/statuses/show?id=" + enc(id), h);
        if (r.body.isBlank() || !r.body.trim().startsWith("{")) return null;
        JSONObject root = new JSONObject(r.body);
        JSONObject mblog = root.optJSONObject("data");
        if (mblog != null && mblog.optJSONObject("mblog") != null) mblog = mblog.optJSONObject("mblog");
        if (mblog == null) return null;

        String title = stripHtml(mblog.optString("text", ""));
        String author = JsonUtil.str(mblog, "user", "screen_name");
        ParseResult.Builder b = ParseResult.builder(platformName(), source).title(title).description(title).author(author);
        JSONObject pageInfo = mblog.optJSONObject("page_info");
        JSONObject media = pageInfo == null ? null : pageInfo.optJSONObject("media_info");
        String video = firstNonBlank(
                pageInfo == null ? "" : pageInfo.optString("media_url", ""),
                media == null ? "" : media.optString("stream_url_hd", ""),
                media == null ? "" : media.optString("stream_url", ""),
                media == null ? "" : media.optString("mp4_hd_url", ""),
                media == null ? "" : media.optString("mp4_720p_mp4", ""),
                media == null ? "" : media.optString("mp4_hd_mp4", ""),
                media == null ? "" : media.optString("mp4_ld_mp4", ""),
                media == null ? "" : media.optString("mp4_url", ""),
                media == null ? "" : media.optString("origin_url", "")
        );
        video = JsonUtil.normalizeUrl(video);
        Map<String, String> mh = new LinkedHashMap<>();
        mh.put("Referer", "https://m.weibo.cn/");
        mh.put("User-Agent", HttpClient.IPHONE_UA);
        if (!video.isBlank()) {
            b.add(new MediaItem(MediaItem.Type.VIDEO, "微博视频", video, mh));
            if (!video.toLowerCase().contains(".m3u8")) b.add(MediaItem.audioTrack("视频完整音轨 · M4A", video, mh));
        }

        JSONArray pics = mblog.optJSONArray("pics");
        String firstImage = "";
        if (pics != null) {
            for (int i = 0; i < pics.length(); i++) {
                JSONObject p = pics.optJSONObject(i);
                String u = p == null ? pics.optString(i, "") : firstNonBlank(JsonUtil.str(p, "large", "url"), p.optString("url", ""));
                u = originalImage(JsonUtil.normalizeUrl(u));
                if (u.isBlank()) continue;
                if (firstImage.isBlank()) firstImage = u;
                b.add(new MediaItem(MediaItem.Type.IMAGE, "图片 " + (i + 1), u, mh));
            }
        }
        String cover = firstImage;
        if (cover.isBlank() && media != null) cover = JsonUtil.normalizeUrl(media.optString("poster", ""));
        b.coverUrl(cover);
        ParseResult result = b.build();
        return result.hasMedia() || !result.title.isBlank() ? result : null;
    }

    private static synchronized String getVisitorCookie() {
        if (!visitorCookie.isBlank() && System.currentTimeMillis() < visitorExpireAt) return visitorCookie;
        try {
            Map<String, String> h1 = new LinkedHashMap<>();
            h1.put("User-Agent", HttpClient.IPHONE_UA);
            h1.put("Content-Type", "application/x-www-form-urlencoded");
            h1.put("Referer", "https://m.weibo.cn/");
            String fp = "cb=gen_callback&fp=" + enc("{\"os\":\"1\",\"browser\":\"Safari16\",\"fonts\":\"undefined\",\"screen\":\"*\",\"plugins\":\"\"}");
            String t1 = HttpClient.post("https://visitor.passport.weibo.cn/visitor/genvisitor", h1, fp).body;
            Matcher mt = Pattern.compile("\\\"tid\\\":\\\"([^\\\"]+)\\\"").matcher(t1);
            if (!mt.find()) return "";
            String tid = mt.group(1);
            String url = "https://visitor.passport.weibo.cn/visitor/visitor?a=incarnate&t=" + enc(tid) + "&w=2&c=100&gc=&cb=cross_domain&from=weibo&_rand=" + Math.random();
            String t2 = HttpClient.get(url, Map.of("User-Agent", HttpClient.IPHONE_UA, "Referer", "https://m.weibo.cn/")).body;
            Matcher ms = Pattern.compile("\\\"sub\\\":\\\"([^\\\"]+)\\\"").matcher(t2);
            Matcher mp = Pattern.compile("\\\"subp\\\":\\\"([^\\\"]+)\\\"").matcher(t2);
            if (!ms.find()) return "";
            String cookie = "SUB=" + ms.group(1);
            if (mp.find()) cookie += "; SUBP=" + mp.group(1);
            visitorCookie = cookie;
            visitorExpireAt = System.currentTimeMillis() + 25 * 60 * 1000L;
            return cookie;
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractId(String raw) {
        Matcher m = FID.matcher(raw); if (m.find()) return decode(m.group(1));
        m = COLON_ID.matcher(raw); if (m.find()) return m.group(1);
        m = MID.matcher(raw); if (m.find()) return m.group(1);
        m = STATUS_TOKEN.matcher(raw); if (m.find()) return m.group(1);
        return null;
    }

    private static String bestUrl(JSONObject urls) {
        if (urls == null) return "";
        String[] order = {"2K", "1080", "蓝光", "720", "480", "标清", "流畅"};
        JSONArray names = urls.names();
        if (names == null) return "";
        for (String q : order) for (int i = 0; i < names.length(); i++) {
            String k = names.optString(i); if (k.contains(q)) return JsonUtil.normalizeUrl(urls.optString(k, ""));
        }
        return JsonUtil.normalizeUrl(urls.optString(names.optString(0), ""));
    }

    private static String firstNonBlank(String... v) { for (String s : v) if (s != null && !s.isBlank()) return s; return ""; }
    private static String originalImage(String u) { return u.replace("/thumb150/", "/large/").replace("/bmiddle/", "/large/").replace("/orj360/", "/large/"); }
    private static String stripHtml(String s) { return s == null ? "" : s.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&").trim(); }
    private static String enc(String s){try{return URLEncoder.encode(s,StandardCharsets.UTF_8.name());}catch(Exception e){return s;}}
    private static String decode(String s) { try { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; } }
    private static String friendly(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
