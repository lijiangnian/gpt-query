package com.example.mediaparser.parser;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.model.ParseResult;
import com.example.mediaparser.net.HttpClient;
import com.example.mediaparser.util.JsonUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class XhsParser implements PlatformParser {
    @Override public String platformName() { return "小红书"; }
    @Override public boolean supports(String url) { return url.contains("xhslink.com") || url.contains("xhslink.cn") || url.contains("xiaohongshu.com"); }

    @Override
    public ParseResult parse(String inputUrl) throws ParseException {
        try {
            String url = normalizeShareUrl(inputUrl);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", HttpClient.DESKTOP_UA);
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            HttpClient.Response r = HttpClient.get(url, headers);
            if (r.body.isBlank()) throw new ParseException("小红书页面为空。");
            String raw = extractInitialState(r.body);
            if (raw == null) throw new ParseException("没有找到小红书页面数据；将尝试浏览器兼容解析。");
            raw = sanitizeJson(raw);
            JSONObject root = new JSONObject(raw);
            JSONObject note = resolveNote(root, r.finalUrl);
            if (note == null) throw new ParseException("小红书笔记数据结构不匹配；将尝试浏览器兼容解析。");

            String title = note.optString("title", "");
            String desc = note.optString("desc", note.optString("description", ""));
            String author = JsonUtil.str(note, "user", "nickName");
            if (author.isBlank()) author = JsonUtil.str(note, "user", "nickname");
            if (author.isBlank()) author = JsonUtil.str(note, "user", "name");

            ParseResult.Builder b = ParseResult.builder(platformName(), r.finalUrl)
                    .title(title)
                    .description(desc)
                    .author(author);

            Map<String, String> mediaHeaders = new LinkedHashMap<>();
            mediaHeaders.put("Referer", "https://www.xiaohongshu.com/");
            mediaHeaders.put("User-Agent", HttpClient.DESKTOP_UA);

            Set<String> seen = new LinkedHashSet<>();
            String video = pickVideo(note);
            JSONArray images = note.optJSONArray("imageList");
            String firstImage = "";
            if (images != null && images.length() > 0) firstImage = originalImageUrl(images.optJSONObject(0));

            if (!video.isBlank()) {
                if (seen.add(video)) {
                    b.add(new MediaItem(MediaItem.Type.VIDEO, "小红书视频", video, mediaHeaders));
                    if (!video.toLowerCase().contains(".m3u8")) {
                        b.add(MediaItem.audioTrack("视频完整音轨 · M4A", video, mediaHeaders));
                    }
                }
            } else if (images != null) {
                for (int i = 0; i < images.length(); i++) {
                    JSONObject img = images.optJSONObject(i);
                    String u = originalImageUrl(img);
                    if (!u.isBlank() && seen.add(u)) {
                        if (firstImage.isBlank()) firstImage = u;
                        b.add(new MediaItem(MediaItem.Type.IMAGE, "图片 " + (i + 1), u, mediaHeaders));
                    }
                }
            }

            // Live Photo applies to image/live-photo notes. A normal video note can also expose
            // imageList as its cover, which must not create extra image/live media entries.
            if (video.isBlank() && images != null) {
                int liveIndex = 1;
                for (int i = 0; i < images.length(); i++) {
                    String live = pickLivePhoto(images.optJSONObject(i));
                    if (!live.isBlank() && seen.add(live)) {
                        b.add(new MediaItem(MediaItem.Type.VIDEO, "Live Photo " + liveIndex++, live, mediaHeaders));
                    }
                }
            }

            b.coverUrl(firstImage);
            ParseResult result = b.build();
            if (!result.hasMedia()) throw new ParseException("笔记已读取，但页面状态没有媒体地址；将尝试浏览器兼容解析。");
            return result;
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("小红书解析失败：" + friendly(e), e);
        }
    }

    static String normalizeShareUrl(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (s.startsWith("http://xhslink.com") || s.startsWith("http://xhslink.cn")) {
            s = "https://" + s.substring("http://".length());
        }
        return s;
    }

    static String sanitizeJson(String raw) {
        return raw.replace("undefined", "null").replace("NaN", "null");
    }

    static String extractInitialState(String html) {
        String marker = "window.__INITIAL_STATE__";
        int p = html.indexOf(marker);
        if (p < 0) return null;
        int eq = html.indexOf('=', p + marker.length());
        if (eq < 0) return null;
        int end = html.indexOf("</script>", eq + 1);
        if (end < 0) return null;
        String s = html.substring(eq + 1, end).trim();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    static JSONObject resolveNote(JSONObject root, String finalUrl) {
        String urlId = noteIdFromUrl(finalUrl);
        JSONObject noteRoot = root.optJSONObject("note");
        if (noteRoot != null) {
            JSONObject map = noteRoot.optJSONObject("noteDetailMap");
            String current = noteRoot.optString("currentNoteId", "");
            String[] ids = {urlId, current};
            if (map != null) {
                for (String id : ids) {
                    if (id == null || id.isBlank()) continue;
                    JSONObject entry = map.optJSONObject(id);
                    JSONObject note = unwrapNote(entry);
                    if (looksLikeNote(note)) return note;
                }
                Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    JSONObject note = unwrapNote(map.optJSONObject(keys.next()));
                    if (looksLikeNote(note)) return note;
                }
            }
            if (looksLikeNote(noteRoot)) return noteRoot;
        }

        Object[][] candidates = {
                {"noteData", "data", "noteData"},
                {"note", "data"},
                {"noteDetail", "data"},
                {"data", "noteData"}
        };
        for (Object[] path : candidates) {
            Object cur = root;
            for (Object key : path) {
                if (!(cur instanceof JSONObject)) { cur = null; break; }
                cur = ((JSONObject) cur).opt(String.valueOf(key));
            }
            if (cur instanceof JSONObject && looksLikeNote((JSONObject) cur)) return (JSONObject) cur;
        }
        return findNoteDeep(root, 0);
    }

    private static JSONObject unwrapNote(JSONObject entry) {
        if (entry == null) return null;
        JSONObject n = entry.optJSONObject("note");
        return n != null ? n : entry;
    }

    private static String noteIdFromUrl(String url) {
        if (url == null) return "";
        try {
            String p = URI.create(url).getPath();
            if (p == null) return "";
            String[] parts = p.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if (("explore".equals(parts[i]) || "item".equals(parts[i])) && !parts[i + 1].isBlank()) return parts[i + 1];
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static boolean looksLikeNote(JSONObject o) {
        if (o == null) return false;
        JSONArray imgs = o.optJSONArray("imageList");
        if (imgs != null && imgs.length() > 0) return true;
        if (JsonUtil.object(o, "video") != null) return true;
        return (o.has("noteId") || o.has("id")) && (o.has("title") || o.has("desc") || o.has("description"));
    }

    private static JSONObject findNoteDeep(Object node, int depth) {
        if (node == null || depth > 10) return null;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (looksLikeNote(o)) return o;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                JSONObject found = findNoteDeep(o.opt(keys.next()), depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject found = findNoteDeep(a.opt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String pickVideo(JSONObject note) {
        String origin = JsonUtil.str(note, "video", "consumer", "originVideoKey");
        if (!origin.isBlank()) {
            if (origin.startsWith("http://") || origin.startsWith("https://")) return https(origin);
            return "https://sns-video-bd.xhscdn.com/" + origin.replaceFirst("^/+", "");
        }
        JSONObject stream = JsonUtil.object(note, "video", "media", "stream");
        return pickBestStream(stream);
    }

    private static String pickLivePhoto(JSONObject img) {
        if (img == null) return "";
        return pickBestStream(img.optJSONObject("stream"));
    }

    private static String pickBestStream(JSONObject stream) {
        if (stream == null) return "";
        String best = "";
        long bestScore = Long.MIN_VALUE;
        Iterator<String> keys = stream.keys();
        while (keys.hasNext()) {
            JSONArray arr = stream.optJSONArray(keys.next());
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                String u = "";
                JSONArray backups = e.optJSONArray("backupUrls");
                if (backups != null) u = JsonUtil.firstUrl(backups);
                if (u.isBlank()) u = e.optString("masterUrl", "");
                if (u.isBlank()) continue;
                long height = longValue(e, "height");
                long bitrate = Math.max(longValue(e, "videoBitrate"), longValue(e, "avgBitrate"));
                long size = longValue(e, "size");
                long score = height * 1_000_000L + Math.min(bitrate, 999_999L) + Math.min(size / 1000L, 999L);
                if (best.isBlank() || score > bestScore) {
                    best = u;
                    bestScore = score;
                }
            }
        }
        return https(JsonUtil.normalizeUrl(best));
    }

    private static long longValue(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception ignored) { return 0L; }
    }

    private static String originalImageUrl(JSONObject img) {
        if (img == null) return "";
        String u = img.optString("urlDefault", "");
        if (u.isBlank()) u = img.optString("urlPre", "");
        if (u.isBlank()) u = img.optString("url", "");
        if (u.isBlank()) {
            JSONArray infos = img.optJSONArray("infoList");
            if (infos != null && infos.length() > 0) {
                for (int i = infos.length() - 1; i >= 0 && u.isBlank(); i--) {
                    JSONObject info = infos.optJSONObject(i);
                    if (info != null) u = info.optString("url", "");
                }
            }
        }
        u = https(JsonUtil.normalizeUrl(u));
        if (u.isBlank()) return "";
        int bang = u.indexOf('!');
        if (bang > 0) u = u.substring(0, bang);
        return u;
    }

    private static String https(String u) {
        if (u == null) return "";
        return u.replaceFirst("^http:", "https:");
    }

    private static String friendly(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
