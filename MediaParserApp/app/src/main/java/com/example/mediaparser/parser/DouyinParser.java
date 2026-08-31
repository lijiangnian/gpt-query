package com.example.mediaparser.parser;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.model.ParseResult;
import com.example.mediaparser.net.HttpClient;
import com.example.mediaparser.util.JsonUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DouyinParser implements PlatformParser {
    private static final Pattern ID = Pattern.compile("/(video|note|story)/(\\d{17,19})");
    private static final Pattern LONG_ID = Pattern.compile("(\\d{17,19})");
    private static final String APP_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 aweme/32.7.0 NetType/WIFI Channel/App Store";

    @Override public String platformName() { return "抖音"; }
    @Override public boolean supports(String url) { return url.contains("douyin.com") || url.contains("iesdouyin.com") || url.contains("snssdk.com"); }

    @Override
    public ParseResult parse(String inputUrl) throws ParseException {
        try {
            Map<String, String> firstHeaders = new LinkedHashMap<>();
            firstHeaders.put("User-Agent", APP_UA);
            firstHeaders.put("Accept", "text/html,application/xhtml+xml");
            HttpClient.Response first = HttpClient.get(inputUrl, firstHeaders);
            String finalUrl = first.finalUrl;
            if (isHaohuoProduct(finalUrl)) {
                throw new ParseException("DOUYIN_COMMERCE_WEB:" + finalUrl);
            }
            if (isJingxuanVideo(finalUrl) || isJingxuanVideo(inputUrl)) {
                ParseResult commerce = parseJingxuan(first.body, finalUrl);
                if (commerce != null) return commerce;
                throw new ParseException("已打开抖音精选商品视频页，但页面没有返回可下载的视频；商品可能没有视频、链接已过期或需要在抖音内登录后重新分享。");
            }
            IdInfo info = extractId(finalUrl);
            if (info == null) info = extractId(inputUrl);
            if (info == null) throw new ParseException("没有从抖音分享链接中识别到作品 ID；请分享具体视频/图文，不要分享用户主页。");

            String cookie = "";
            String ttwid = first.cookies.get("ttwid");
            if (ttwid != null && !ttwid.isBlank()) cookie = "ttwid=" + ttwid;

            String[] urls = {
                    finalUrl,
                    "https://www.iesdouyin.com/share/" + info.type + "/" + info.id,
                    "https://m.douyin.com/share/" + info.type + "/" + info.id,
                    "https://www.douyin.com/video/" + info.id
            };
            String[] uas = { APP_UA, HttpClient.IPHONE_UA, HttpClient.MOBILE_UA };
            JSONObject data = null;
            for (int round = 0; round < 2 && data == null; round++) {
                for (String ua : uas) {
                    for (String u : urls) {
                        Map<String, String> h = new LinkedHashMap<>();
                        h.put("User-Agent", ua);
                        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                        if (!cookie.isBlank()) h.put("Cookie", cookie);
                        HttpClient.Response r;
                        try { r = HttpClient.get(u, h); } catch (Exception ignored) { continue; }
                        String newT = r.cookies.get("ttwid");
                        if (newT != null && !newT.isBlank()) cookie = "ttwid=" + newT;
                        JSONObject parsed = tryParseEmbedded(r.body);
                        if (parsed != null && findAweme(parsed) != null) { data = parsed; break; }
                    }
                    if (data != null) break;
                }
            }
            if (data == null) throw new ParseException("抖音页面未返回可解析数据，可能被风控、作品已删除或页面结构刚发生变化。");
            JSONObject aweme = findAweme(data);
            if (aweme == null) throw new ParseException("已读取抖音页面，但没有找到作品数据。");

            String author = JsonUtil.str(aweme, "author", "nickname");
            String title = aweme.optString("desc", "");
            ParseResult.Builder b = ParseResult.builder(platformName(), finalUrl)
                    .title(title).description(title).author(author);

            Map<String, String> mediaHeaders = new LinkedHashMap<>();
            mediaHeaders.put("Referer", "https://www.douyin.com/");
            mediaHeaders.put("User-Agent", HttpClient.IPHONE_UA);

            JSONArray images = aweme.optJSONArray("images");
            String firstImage = "";
            if (images != null) {
                for (int i = 0; i < images.length(); i++) {
                    JSONObject img = images.optJSONObject(i);
                    String u = JsonUtil.firstUrl(img == null ? null : img.optJSONArray("url_list"));
                    if (u.isBlank()) continue;
                    if (firstImage.isBlank()) firstImage = u;
                    b.add(new MediaItem(MediaItem.Type.IMAGE, "图片 " + (i + 1), u, mediaHeaders));
                }
            }

            JSONObject play = JsonUtil.object(aweme, "video", "play_addr");
            String video = "";
            if (play != null) {
                video = JsonUtil.firstUrl(play.optJSONArray("url_list"));
                video = video.replace("playwm", "play").replace("play_wm", "play");
                if (video.isBlank()) {
                    String uri = play.optString("uri", "");
                    if (!uri.isBlank() && !uri.startsWith("http")) {
                        video = "https://www.iesdouyin.com/aweme/v1/play/?video_id=" + uri + "&ratio=1080p&line=0";
                    }
                }
            }
            if (!video.isBlank()) {
                b.add(new MediaItem(MediaItem.Type.VIDEO, "抖音视频", video, mediaHeaders));
                if (!video.toLowerCase().contains(".m3u8")) {
                    b.add(MediaItem.audioTrack("视频完整音轨 · M4A", video, mediaHeaders));
                }
            }

            String audio = JsonUtil.firstUrl(JsonUtil.array(aweme, "music", "play_url", "url_list"));
            if (!audio.isBlank()) {
                String musicTitle = JsonUtil.str(aweme, "music", "title");
                String label = musicTitle.isBlank() ? "平台原声/配乐" : "平台原声/配乐 · " + musicTitle;
                b.add(MediaItem.directAudio(label, audio, mediaHeaders, ".mp3", "audio/mpeg"));
            }

            String cover = firstImage;
            if (cover.isBlank()) cover = JsonUtil.firstUrl(JsonUtil.array(aweme, "video", "cover", "url_list"));
            b.coverUrl(cover);
            ParseResult result = b.build();
            if (!result.hasMedia()) throw new ParseException("抖音作品已读取，但没有找到视频或图片直链。");
            return result;
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("抖音解析失败：" + friendly(e), e);
        }
    }

    private static boolean isJingxuanVideo(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("jingxuan.douyin.com/") && lower.contains("/video/");
    }

    private static boolean isHaohuoProduct(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("haohuo.jinritemai.com/") && lower.contains("/trade/detail/");
    }

    /** Reads the public SSR payload used by jingxuan.douyin.com/m/video/{id}. */
    static ParseResult parseJingxuan(String html, String sourceUrl) {
        JSONObject root = tryParseEmbedded(html);
        if (root == null) return null;
        JSONObject result = JsonUtil.object(root, "data", "storeState", "detail", "videoData", "result");
        if (result == null) return null;
        JSONObject model;
        Object rawModel = result.opt("video_model");
        try {
            model = rawModel instanceof JSONObject ? (JSONObject) rawModel
                    : rawModel instanceof String ? new JSONObject((String) rawModel) : null;
        } catch (Exception e) { return null; }
        if (model == null) return null;
        String video = bestJingxuanUrl(model.optJSONArray("video_list"));
        if (video.isBlank()) return null;

        String title = result.optString("title", "").trim();
        String description = result.optString("abstract", "").trim();
        String cover = result.optString("cover_image_url", "").trim();
        String author = JsonUtil.str(result, "media_user", "screen_name");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://jingxuan.douyin.com/");
        headers.put("User-Agent", HttpClient.IPHONE_UA);
        return ParseResult.builder("抖音商城视频", sourceUrl)
                .title(title).description(description).author(author).coverUrl(cover)
                .add(new MediaItem(MediaItem.Type.VIDEO, "商品主视频 · 兼容清晰版", video, headers))
                .add(MediaItem.audioTrack("商品视频完整音轨 · M4A", video, headers))
                .build();
    }

    static String bestJingxuanUrl(JSONArray list) {
        if (list == null) return "";
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("main_url", "");
            if (!url.startsWith("https://")) continue;
            String gear = item.optString("gear_des_key", "").toLowerCase();
            int resolution = gear.contains("1080p") ? 1080 : gear.contains("720p") ? 720 : gear.contains("540p") ? 540 : 0;
            // H.264 is broadly playable on Android and is preferred over a higher H.265-only variant.
            int score = (gear.contains("h264") ? 10000 : 0) + resolution;
            if (gear.contains("|5:normal")) score += 100;
            else if (gear.contains("|5:low")) score += 50;
            if (score > bestScore) { bestScore = score; best = url; }
        }
        return best;
    }

    static JSONObject tryParseEmbedded(String html) {
        if (html == null || html.isBlank()) return null;
        String raw = between(html, "window._ROUTER_DATA", "</script>");
        if (raw != null) {
            int eq = raw.indexOf('=');
            if (eq >= 0) raw = raw.substring(eq + 1).trim();
            if (raw.endsWith(";")) raw = raw.substring(0, raw.length() - 1);
            try { return new JSONObject(raw); } catch (Exception ignored) {}
        }
        raw = scriptById(html, "RENDER_DATA");
        if (raw != null) {
            try { return new JSONObject(URLDecoder.decode(raw.trim(), StandardCharsets.UTF_8.name())); } catch (Exception ignored) {}
        }
        raw = between(html, "window._SSR_DATA", "</script>");
        if (raw == null) raw = between(html, "window._SSR_HYDRATED_DATA", "</script>");
        if (raw != null) {
            int eq = raw.indexOf('=');
            if (eq >= 0) raw = raw.substring(eq + 1).trim();
            if (raw.endsWith(";")) raw = raw.substring(0, raw.length() - 1);
            try { return new JSONObject(raw); } catch (Exception ignored) {}
        }
        return null;
    }

    static JSONObject findAweme(JSONObject root) {
        JSONObject loader = root.optJSONObject("loaderData");
        if (loader != null) {
            String[] keys = {"video_(id)/page", "note_(id)/page", "story_(id)/page"};
            for (String k : keys) {
                JSONArray items = JsonUtil.array(loader, k, "videoInfoRes", "item_list");
                if (items != null && items.length() > 0 && items.optJSONObject(0) != null) return items.optJSONObject(0);
            }
        }
        return deepFindAweme(root, 0);
    }

    private static JSONObject deepFindAweme(Object node, int depth) {
        if (node == null || depth > 12) return null;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has("author") && (o.has("video") || o.has("images")) && (o.has("desc") || o.has("aweme_id"))) return o;
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                JSONObject hit = deepFindAweme(o.opt(names.optString(i)), depth + 1);
                if (hit != null) return hit;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject hit = deepFindAweme(a.opt(i), depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static IdInfo extractId(String url) {
        Matcher m = ID.matcher(url);
        if (m.find()) return new IdInfo(m.group(2), m.group(1).equals("story") ? "video" : m.group(1));
        m = LONG_ID.matcher(url);
        if (m.find()) return new IdInfo(m.group(1), "video");
        return null;
    }

    private static String between(String text, String start, String end) {
        int p = text.indexOf(start); if (p < 0) return null;
        int q = text.indexOf(end, p); if (q < 0) return null;
        return text.substring(p, q);
    }

    private static String scriptById(String html, String id) {
        int p = html.indexOf("id=\"" + id + "\"");
        if (p < 0) p = html.indexOf("id='" + id + "'");
        if (p < 0) return null;
        int gt = html.indexOf('>', p); if (gt < 0) return null;
        int end = html.indexOf("</script>", gt); if (end < 0) return null;
        return html.substring(gt + 1, end);
    }

    private static String friendly(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static final class IdInfo { final String id, type; IdInfo(String id, String type) { this.id = id; this.type = type; } }
}
