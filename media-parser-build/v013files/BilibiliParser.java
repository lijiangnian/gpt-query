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

public final class BilibiliParser implements PlatformParser {
    private static final Pattern BV = Pattern.compile("BV[0-9A-Za-z]{10,}");
    private static final Pattern AV = Pattern.compile("(?:^|/|\\b)av(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override public String platformName() { return "B站"; }
    @Override public boolean supports(String url) { return url.contains("b23.tv") || url.contains("bilibili.com"); }

    @Override
    public ParseResult parse(String inputUrl) throws ParseException {
        try {
            Map<String, String> head = new LinkedHashMap<>();
            head.put("User-Agent", HttpClient.DESKTOP_UA);
            head.put("Referer", "https://www.bilibili.com/");
            HttpClient.Response first = HttpClient.get(inputUrl, head);
            String finalUrl = first.finalUrl;
            if (finalUrl.contains("/bangumi/")) throw new ParseException("当前版本暂不解析 B站番剧，请使用普通视频链接。");

            Matcher bvm = BV.matcher(finalUrl);
            Matcher avm = AV.matcher(finalUrl);
            String idQuery;
            String idValue;
            if (bvm.find()) {
                idQuery = "bvid";
                idValue = bvm.group();
            } else if (avm.find()) {
                idQuery = "aid";
                idValue = avm.group(1);
            } else {
                throw new ParseException("没有从 B站链接中识别到 BV/av 视频编号。");
            }

            String viewApi = "https://api.bilibili.com/x/web-interface/view?" + idQuery + "=" + enc(idValue);
            JSONObject view = new JSONObject(HttpClient.get(viewApi, head).body);
            if (view.optInt("code", -1) != 0) throw new ParseException("B站作品信息接口返回失败：" + view.optString("message", "unknown"));
            JSONObject data = view.getJSONObject("data");
            long cid = data.optLong("cid", 0L);
            if (cid <= 0) throw new ParseException("B站返回的 cid 无效。");

            Map<String, String> mediaHeaders = new LinkedHashMap<>();
            mediaHeaders.put("Referer", "https://www.bilibili.com/");
            mediaHeaders.put("User-Agent", HttpClient.DESKTOP_UA);

            ParseResult.Builder b = ParseResult.builder(platformName(), finalUrl)
                    .title(data.optString("title", ""))
                    .author(JsonUtil.str(data, "owner", "name"))
                    .description(data.optString("desc", ""))
                    .coverUrl(data.optString("pic", ""));

            String videoUrl = loadMuxedVideo(idQuery, idValue, cid, head);
            if (!videoUrl.isBlank()) b.add(new MediaItem(MediaItem.Type.VIDEO, "B站视频", videoUrl, mediaHeaders));

            AudioChoice audio = loadBestDashAudio(idQuery, idValue, cid, head);
            if (audio != null && !audio.url.isBlank()) {
                String label = "B站独立音频 · " + (audio.kbps > 0 ? audio.kbps + " kbps" : "最高可用码率");
                if (!audio.codec.isBlank()) label += " · " + audio.codec;
                b.add(MediaItem.directAudio(label, audio.url, mediaHeaders, ".m4a", audio.mime.isBlank() ? "audio/mp4" : audio.mime));
            } else if (!videoUrl.isBlank()) {
                b.add(MediaItem.audioTrack("B站视频完整音轨 · M4A", videoUrl, mediaHeaders));
            }

            ParseResult result = b.build();
            if (!result.hasMedia()) throw new ParseException("B站没有返回可下载的视频或音频地址。");
            return result;
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("B站解析失败：" + friendly(e), e);
        }
    }

    private static String loadMuxedVideo(String idQuery, String idValue, long cid, Map<String, String> head) {
        try {
            String api = "https://api.bilibili.com/x/player/playurl?" + idQuery + "=" + enc(idValue) + "&cid=" + cid + "&qn=64&fnval=0&fourk=0";
            JSONObject play = new JSONObject(HttpClient.get(api, head).body);
            if (play.optInt("code", -1) != 0) return "";
            JSONArray durl = JsonUtil.array(play, "data", "durl");
            if (durl == null || durl.length() == 0 || durl.optJSONObject(0) == null) return "";
            return JsonUtil.normalizeUrl(durl.optJSONObject(0).optString("url", ""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static AudioChoice loadBestDashAudio(String idQuery, String idValue, long cid, Map<String, String> head) {
        try {
            String api = "https://api.bilibili.com/x/player/playurl?" + idQuery + "=" + enc(idValue) + "&cid=" + cid + "&qn=80&fnval=4048&fourk=1";
            JSONObject play = new JSONObject(HttpClient.get(api, head).body);
            if (play.optInt("code", -1) != 0) return null;
            JSONArray audio = JsonUtil.array(play, "data", "dash", "audio");
            if (audio == null || audio.length() == 0) return null;
            AudioChoice best = null;
            for (int i = 0; i < audio.length(); i++) {
                JSONObject a = audio.optJSONObject(i);
                if (a == null) continue;
                String url = firstNonBlank(a.optString("baseUrl", ""), a.optString("base_url", ""));
                if (url.isBlank()) {
                    JSONArray backups = a.optJSONArray("backupUrl");
                    if (backups == null) backups = a.optJSONArray("backup_url");
                    if (backups != null) url = backups.optString(0, "");
                }
                url = JsonUtil.normalizeUrl(url);
                if (url.isBlank()) continue;
                long bandwidth = a.optLong("bandwidth", 0L);
                AudioChoice c = new AudioChoice(url, bandwidth, a.optString("mimeType", a.optString("mime_type", "audio/mp4")), a.optString("codecs", ""));
                if (best == null || c.bandwidth > best.bandwidth) best = c;
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String friendly(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private static final class AudioChoice {
        final String url;
        final long bandwidth;
        final int kbps;
        final String mime;
        final String codec;
        AudioChoice(String url, long bandwidth, String mime, String codec) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.kbps = bandwidth > 0 ? (int) Math.round(bandwidth / 1000.0) : 0;
            this.mime = mime == null ? "" : mime;
            this.codec = codec == null ? "" : codec;
        }
    }
}
