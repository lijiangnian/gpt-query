package com.example.mediaparser.parser;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.model.ParseResult;
import com.example.mediaparser.net.HttpClient;
import com.example.mediaparser.util.JsonUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KuaishouParser implements PlatformParser {
    private static final Pattern PHOTO_ID = Pattern.compile("(?:short-video|photo)/([^?/#]+)");
    private static final Pattern[] VIDEO_PATTERNS = {
            Pattern.compile("\\\"photoUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""),
            Pattern.compile("\\\"playUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""),
            Pattern.compile("\\\"videoUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""),
            Pattern.compile("\\\"mp4Url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""),
            Pattern.compile("(?:photoUrl|playUrl|videoUrl|mp4Url)['\\\"]\\s*:\\s*['\\\"]([^'\\\"]+)['\\\"]"),
            Pattern.compile("(?i)<meta[^>]+(?:property|name)=[\\\"']og:video(?::url)?[\\\"'][^>]+content=[\\\"']([^\\\"']+)[\\\"']"),
            Pattern.compile("(?i)<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+(?:property|name)=[\\\"']og:video(?::url)?[\\\"']"),
            Pattern.compile("(https?:\\/\\/[^\\\"'\\s]+\\.(?:mp4|m3u8|flv)(?:[^\\\"'\\s]*)?)", Pattern.CASE_INSENSITIVE)
    };
    private static final Pattern TITLE = Pattern.compile("\\\"caption\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern AUTHOR = Pattern.compile("\\\"(?:userName|name)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern COVER = Pattern.compile("\\\"(?:coverUrl|cover|poster|thumbnail)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");

    private static final String DETAIL_QUERY =
            "query visionVideoDetail($photoId: String, $type: String, $page: String, $webPageArea: String) {" +
            " visionVideoDetail(photoId: $photoId, type: $type, page: $page, webPageArea: $webPageArea) {" +
            " status type author { id name following headerUrl __typename }" +
            " photo { id duration caption likeCount realLikeCount coverUrl photoUrl liked timestamp expTag llsid viewCount videoRatio stereoType musicBlocked" +
            " manifest { mediaType businessType version adaptationSet { id duration representation { id defaultSelect backupUrl codecs url height width avgBitrate maxBitrate m3u8Slice qualityType qualityLabel frameRate featureP2sp hidden disableAdaptive __typename } __typename } __typename }" +
            " manifestH265 photoH265Url coronaCropManifest coronaCropManifestH265 croppedPhotoH265Url croppedPhotoUrl videoResource __typename }" +
            " tags { type name __typename } commentLimit { canAddComment __typename } llsid danmakuSwitch __typename } }";

    @Override public String platformName() { return "快手"; }
    @Override public boolean supports(String url) { return url.contains("kuaishou.com"); }

    @Override
    public ParseResult parse(String inputUrl) throws ParseException {
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", HttpClient.IPHONE_UA);
            h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            HttpClient.Response first = HttpClient.get(inputUrl, h);
            String finalUrl = first.finalUrl;

            String photoId = photoId(inputUrl);
            if (photoId.isBlank()) photoId = photoId(finalUrl);

            ParseResult graph = photoId.isBlank() ? null : parseGraphQl(photoId, finalUrl, first.cookies);
            if (graph != null && graph.hasMedia()) return graph;

            HttpClient.Response r = first;
            if (!first.ok() || first.body.length() < 500) r = HttpClient.get(finalUrl, h);
            String html = r.body;

            String video = clean(firstMatch(VIDEO_PATTERNS, html));
            if (video.isBlank()) video = videoFromEmbeddedState(html);
            if (video.isBlank()) throw new ParseException("快手页面已打开，但没有找到视频直链；可能需要登录态、作品类型暂不支持或页面被风控。");

            String title = cleanText(firstMatch(new Pattern[]{TITLE}, html));
            String author = cleanText(firstMatch(new Pattern[]{AUTHOR}, html));
            String cover = clean(firstMatch(new Pattern[]{COVER}, html));
            Map<String, String> mediaHeaders = mediaHeaders(finalUrl);

            return ParseResult.builder(platformName(), finalUrl)
                    .title(title).author(author).description(title).coverUrl(cover)
                    .add(new MediaItem(MediaItem.Type.VIDEO, "快手视频", video, mediaHeaders))
                    .build();
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("快手解析失败：" + friendly(e), e);
        }
    }

    private static ParseResult parseGraphQl(String photoId, String referer, Map<String, String> cookies) {
        try {
            JSONObject variables = new JSONObject();
            variables.put("photoId", photoId);
            variables.put("page", "search");
            JSONObject req = new JSONObject();
            req.put("operationName", "visionVideoDetail");
            req.put("variables", variables);
            req.put("query", DETAIL_QUERY);

            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", HttpClient.DESKTOP_UA);
            h.put("Accept", "application/json, text/plain, */*");
            h.put("Content-Type", "application/json");
            h.put("Origin", "https://www.kuaishou.com");
            h.put("Referer", referer);
            if (cookies != null && !cookies.isEmpty()) {
                StringBuilder c = new StringBuilder();
                for (Map.Entry<String, String> e : cookies.entrySet()) {
                    if (c.length() > 0) c.append("; ");
                    c.append(e.getKey()).append('=').append(e.getValue());
                }
                h.put("Cookie", c.toString());
            }

            HttpClient.Response r = HttpClient.post("https://www.kuaishou.com/graphql", h, req.toString());
            if (!r.ok() || r.body.isBlank()) return null;
            JSONObject root = new JSONObject(r.body);
            JSONObject detail = JsonUtil.object(root, "data", "visionVideoDetail");
            JSONObject photo = detail == null ? null : detail.optJSONObject("photo");
            if (photo == null) return null;

            String video = firstNonBlank(photo.optString("photoUrl", ""), photo.optString("croppedPhotoUrl", ""), photo.optString("photoH265Url", ""), photo.optString("croppedPhotoH265Url", ""));
            if (video.isBlank()) video = manifestVideo(photo.optJSONObject("manifest"));
            video = clean(video);
            if (video.isBlank()) return null;

            String title = photo.optString("caption", "");
            String cover = clean(photo.optString("coverUrl", ""));
            String author = detail.optJSONObject("author") == null ? "" : detail.optJSONObject("author").optString("name", "");
            return ParseResult.builder("快手", referer)
                    .title(title).description(title).author(author).coverUrl(cover)
                    .add(new MediaItem(MediaItem.Type.VIDEO, "快手视频", video, mediaHeaders(referer)))
                    .build();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String manifestVideo(JSONObject manifest) {
        if (manifest == null) return "";
        JSONArray sets = manifest.optJSONArray("adaptationSet");
        if (sets == null) return "";
        String first = "";
        long best = -1;
        for (int i = 0; i < sets.length(); i++) {
            JSONObject set = sets.optJSONObject(i);
            JSONArray reps = set == null ? null : set.optJSONArray("representation");
            if (reps == null) continue;
            for (int j = 0; j < reps.length(); j++) {
                JSONObject rep = reps.optJSONObject(j);
                if (rep == null) continue;
                String u = rep.optString("url", "");
                if (u.isBlank()) u = rep.optString("backupUrl", "");
                if (u.isBlank()) continue;
                if (first.isBlank()) first = u;
                long bitrate = rep.optLong("avgBitrate", 0);
                if (rep.optBoolean("defaultSelect", false) || bitrate > best) {
                    best = bitrate;
                    first = u;
                }
            }
        }
        return first;
    }

    private static String videoFromEmbeddedState(String html) {
        if (html == null || html.isBlank()) return "";
        for (String marker : new String[]{"window.__APOLLO_STATE__", "window.__INITIAL_STATE__"}) {
            int p = html.indexOf(marker);
            if (p < 0) continue;
            int eq = html.indexOf('=', p + marker.length());
            int end = html.indexOf("</script>", Math.max(eq, 0));
            if (eq < 0 || end < 0) continue;
            String raw = html.substring(eq + 1, end).trim();
            if (raw.endsWith(";")) raw = raw.substring(0, raw.length() - 1).trim();
            raw = raw.replace("undefined", "null");
            try {
                String u = findVideoDeep(new JSONObject(raw), 0);
                if (!u.isBlank()) return clean(u);
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static String findVideoDeep(Object node, int depth) {
        if (node == null || depth > 9) return "";
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String k : new String[]{"photoUrl", "playUrl", "videoUrl", "mp4Url", "src"}) {
                String u = o.optString(k, "");
                if (isVideoCandidate(u)) return u;
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String u = findVideoDeep(o.opt(keys.next()), depth + 1);
                if (!u.isBlank()) return u;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String u = findVideoDeep(a.opt(i), depth + 1);
                if (!u.isBlank()) return u;
            }
        }
        return "";
    }

    private static boolean isVideoCandidate(String u) {
        if (u == null) return false;
        String s = clean(u).toLowerCase();
        return s.startsWith("http") && (s.contains(".mp4") || s.contains(".m3u8") || s.contains("video") || s.contains("play") || s.contains("stream"));
    }

    private static String photoId(String url) {
        Matcher m = PHOTO_ID.matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "";
    }

    private static Map<String, String> mediaHeaders(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Referer", referer);
        h.put("User-Agent", HttpClient.DESKTOP_UA);
        return h;
    }

    private static String firstMatch(Pattern[] ps, String text) {
        if (text == null) return "";
        for (Pattern p : ps) {
            Matcher m = p.matcher(text);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    private static String clean(String s) {
        if (s == null) return "";
        return JsonUtil.normalizeUrl(s)
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("\\u0025", "%")
                .replace("\\u003f", "?")
                .replace("\\u002F", "/");
    }

    private static String cleanText(String s) {
        return clean(s).replace("\\n", " ").replace("\\\"", "\"");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String friendly(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
