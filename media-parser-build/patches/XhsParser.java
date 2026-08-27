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

public final class XhsParser implements PlatformParser {
    @Override public String platformName() { return "小红书"; }
    @Override public boolean supports(String url) { return url.contains("xhslink.com") || url.contains("xhslink.cn") || url.contains("xiaohongshu.com"); }

    @Override
    public ParseResult parse(String inputUrl) throws ParseException {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", HttpClient.DESKTOP_UA);
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            HttpClient.Response r = HttpClient.get(inputUrl, headers);
            if (r.body.isBlank()) throw new ParseException("小红书页面为空。");
            String raw = extractInitialState(r.body);
            if (raw == null) throw new ParseException("没有找到小红书页面数据；可能是页面结构更新、作品不可访问或风控拦截。");
            raw = raw.replace("undefined", "null");
            JSONObject root = new JSONObject(raw);
            JSONObject note = resolveNote(root);
            if (note == null) throw new ParseException("小红书笔记数据结构不匹配。");

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

            String video = pickVideo(note);
            JSONArray images = note.optJSONArray("imageList");
            String firstImage = "";
            if (images != null && images.length() > 0) firstImage = imageUrl(images.optJSONObject(0));
            if (!video.isBlank()) {
                b.add(new MediaItem(MediaItem.Type.VIDEO, "小红书视频", video, mediaHeaders));
            } else if (images != null) {
                for (int i = 0; i < images.length(); i++) {
                    JSONObject img = images.optJSONObject(i);
                    String u = imageUrl(img);
                    if (u.isBlank()) continue;
                    if (firstImage.isBlank()) firstImage = u;
                    b.add(new MediaItem(MediaItem.Type.IMAGE, "图片 " + (i + 1), u, mediaHeaders));
                }
            }
            b.coverUrl(firstImage);
            ParseResult result = b.build();
            if (!result.hasMedia()) throw new ParseException("笔记已读取，但没有找到可下载的视频或图片。");
            return result;
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("小红书解析失败：" + friendly(e), e);
        }
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

    static JSONObject resolveNote(JSONObject root) {
        JSONObject noteRoot = root.optJSONObject("note");
        if (noteRoot != null) {
            String id = noteRoot.optString("currentNoteId", "");
            JSONObject map = noteRoot.optJSONObject("noteDetailMap");
            if (!id.isBlank() && map != null) {
                JSONObject entry = map.optJSONObject(id);
                if (entry != null) {
                    JSONObject note = entry.optJSONObject("note");
                    if (note != null) return note;
                    if (entry.has("title") || entry.has("imageList")) return entry;
                }
            }
        }
        if (noteRoot != null) {
            JSONObject map = noteRoot.optJSONObject("noteDetailMap");
            if (map != null) {
                Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    JSONObject entry = map.optJSONObject(keys.next());
                    if (entry == null) continue;
                    JSONObject note = entry.optJSONObject("note");
                    if (looksLikeNote(note)) return note;
                    if (looksLikeNote(entry)) return entry;
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

    private static boolean looksLikeNote(JSONObject o) {
        if (o == null) return false;
        if (o.optJSONArray("imageList") != null && o.optJSONArray("imageList").length() > 0) return true;
        if (JsonUtil.object(o, "video", "media") != null) return true;
        return (o.has("noteId") || o.has("id")) && (o.has("title") || o.has("desc") || o.has("description"));
    }

    private static JSONObject findNoteDeep(Object node, int depth) {
        if (node == null || depth > 9) return null;
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
        JSONObject stream = JsonUtil.object(note, "video", "media", "stream");
        if (stream == null) return "";
        String u = firstStream(stream.optJSONArray("h264"));
        if (u.isBlank()) u = firstStream(stream.optJSONArray("h265"));
        return JsonUtil.normalizeUrl(u).replaceFirst("^http:", "https:");
    }

    private static String firstStream(JSONArray arr) {
        if (arr == null) return "";
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            JSONArray backups = e.optJSONArray("backupUrls");
            String u = JsonUtil.firstUrl(backups);
            if (!u.isBlank()) return u;
            u = e.optString("masterUrl", "");
            if (!u.isBlank()) return u;
        }
        return "";
    }

    private static String imageUrl(JSONObject img) {
        if (img == null) return "";
        String u = img.optString("urlDefault", "");
        if (u.isBlank()) u = img.optString("urlPre", "");
        if (u.isBlank()) u = img.optString("url", "");
        if (u.isBlank()) {
            JSONArray infos = img.optJSONArray("infoList");
            if (infos != null && infos.length() > 0) {
                JSONObject first = infos.optJSONObject(0);
                if (first != null) u = first.optString("url", "");
            }
        }
        return JsonUtil.normalizeUrl(u).replaceFirst("^http:", "https:");
    }

    private static String friendly(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
