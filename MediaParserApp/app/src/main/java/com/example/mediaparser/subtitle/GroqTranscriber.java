package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Groq cloud Whisper; no local model, no cross-provider fallback. */
public final class GroqTranscriber {
    static final String API = "https://api.groq.com/openai/v1";
    static final String MODEL = TranscriptionOptions.ACCURATE_MODEL;
    // Conservative decimal MB limit, also used on paid accounts to avoid accidental large jobs.
    static final long MAX_BYTES = 25_000_000L;

    private GroqTranscriber() {}

    static JSONObject transcribe(String key, File audio, String mime, long durationMs,
                                 SubtitleExtractor.Listener listener) throws Exception {
        return transcribe(key, audio, mime, durationMs, TranscriptionOptions.defaults(), listener);
    }

    static JSONObject transcribe(String key, File audio, String mime, long durationMs,
                                 TranscriptionOptions options, SubtitleExtractor.Listener listener) throws Exception {
        if (options == null) throw new IllegalArgumentException("缺少识别设置");
        validateAudio(audio.length(), mime);
        return GeminiHttp.retry(() -> once(key, audio, mime, durationMs, options, listener),
                "Groq 转写", listener == null ? null : listener::onStage, Thread::sleep);
    }

    static void validateAudio(long size, String mime) throws IOException {
        if (size <= 0) throw new IOException("Groq：音频文件为空");
        if (size > MAX_BYTES) throw new IOException("Groq 免费路线单次音频上限 25 MB；请缩短音频或手动选择 Gemini");
        extension(mime);
    }

    static String extension(String mime) throws IOException {
        switch (mime) {
            case "audio/mp4": return "m4a";
            case "audio/mpeg": return "mp3";
            case "audio/wav": return "wav";
            case "audio/flac": return "flac";
            case "audio/ogg": return "ogg";
            case "audio/webm": return "webm";
            default: throw new IOException("Groq 暂不接受此音频格式（" + mime + "）；请使用 M4A/MP3/WAV 或手动选择 Gemini");
        }
    }

    static byte[] multipartPrefix(String boundary, String mime) throws IOException {
        return multipartPrefix(boundary, mime, TranscriptionOptions.defaults());
    }

    static byte[] multipartPrefix(String boundary, String mime, TranscriptionOptions options) throws IOException {
        StringBuilder b = new StringBuilder();
        field(b, boundary, "model", options.groqModel());
        if (!"auto".equals(options.language)) field(b, boundary, "language", options.language);
        if (!options.vocabulary.isEmpty()) field(b, boundary, "prompt", options.groqPrompt());
        field(b, boundary, "response_format", "verbose_json");
        field(b, boundary, "timestamp_granularities[]", "word");
        field(b, boundary, "timestamp_granularities[]", "segment");
        field(b, boundary, "temperature", "0");
        b.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.")
                .append(extension(mime)).append("\"\r\nContent-Type: ").append(mime).append("\r\n\r\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void field(StringBuilder b, String boundary, String name, String value) {
        b.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
                .append(name).append("\"\r\n\r\n").append(value).append("\r\n");
    }

    private static JSONObject once(String key, File audio, String mime, long durationMs,
                                   TranscriptionOptions options, SubtitleExtractor.Listener listener) throws Exception {
        String boundary = "MediaParser_" + UUID.randomUUID().toString().replace("-", "");
        byte[] prefix = multipartPrefix(boundary, mime, options);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long size = audio.length();
        HttpURLConnection c = open("/audio/transcriptions", "POST", key);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setFixedLengthStreamingMode(prefix.length + size + suffix.length);
            if (listener != null) listener.onStage("正在上传音频到 Groq…");
            try (OutputStream out = c.getOutputStream(); FileInputStream in = new FileInputStream(audio)) {
                out.write(prefix);
                byte[] buf = new byte[64 * 1024];
                long sent = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("已取消 Groq 上传");
                    out.write(buf, 0, n);
                    sent += n;
                    if (listener != null) listener.onUploadProgress((int) Math.min(100, sent * 100 / size), sent, size);
                }
                out.write(suffix);
            }
            if (listener != null) {
                listener.onStage("Groq 正在生成字幕…");
                listener.onTranscribeStart(durationMs);
            }
            int code = c.getResponseCode();
            String body = GeminiHttp.readBody(c, code);
            if (code < 200 || code >= 300) throw GeminiHttp.error("Groq 上传/转写", code, body, key, c.getHeaderField("Retry-After"));
            try { return new JSONObject(body); }
            catch (Exception e) { throw new IOException("Groq 未返回有效 JSON：" + GeminiHttp.redact(body, key)); }
        } catch (IOException e) {
            // Do not replay an ambiguous network failure: the server may already have processed it.
            throw new IOException("Groq 网络/响应失败：" + GeminiHttp.redact(e.getMessage(), key));
        } finally { c.disconnect(); }
    }

    static HttpURLConnection open(String path, String method, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(API + path).toURL().openConnection();
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(180_000);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("User-Agent", "MediaParser/0.1.16 Android");
        return c;
    }

    /** Use only actual provider segment boundaries if word alignment is unusable. */
    static SubtitleExtractor.Parsed parse(JSONObject root, long duration) throws Exception {
        try {
            // Validate the raw sequence before any token joining could hide a reversal.
            List<SubtitleExtractor.Word> raw = new ArrayList<>();
            JSONArray items = root.optJSONArray("words");
            if (items != null) for (int i=0;i<items.length();i++) {
                JSONObject item=items.optJSONObject(i);
                if(item==null)throw new IOException("Groq 返回了无效的字级字幕");
                if(!item.optString("word", "").isBlank())
                    raw.add(new SubtitleExtractor.Word(item.getString("word"),timeMs(item,"start"),timeMs(item,"end"),""));
            }
            raw=SubtitleTimeline.providerWords(raw,duration);
            if(raw.isEmpty())throw new IOException("Groq 未返回字级时间轴");
            String full=root.optString("text","").trim();
            raw=joinWrittenTokens(raw,full);
            List<SubtitleSegment> grouped=SubtitleTimeline.providerSegments(SubtitleExtractor.groupWords(raw),duration);
            return new SubtitleExtractor.Parsed(full,root.optString("language",""),grouped,raw,duration,"已校验服务商字级时间轴");
        } catch (IllegalStateException | IOException wordError) {
            try {
                JSONObject segmentOnly=new JSONObject().put("text",root.optString("text", ""))
                        .put("language",root.optString("language", ""))
                        .put("segments",root.optJSONArray("segments"));
                SubtitleExtractor.Parsed parsed=parse(segmentOnly);
                List<SubtitleSegment> fixed=SubtitleTimeline.providerSegments(parsed.segments,duration);
                return new SubtitleExtractor.Parsed(parsed.fullText,parsed.language,fixed,
                        java.util.Collections.emptyList(),duration,
                        "词级时间轴已自动降级；当前 SRT 使用并校验过的服务端句级时间轴（"+wordError.getMessage()+"）");
            } catch (IllegalStateException | IOException segmentError) {
                throw new IOException("字级："+wordError.getMessage()+"；句级："+segmentError.getMessage());
            }
        }
    }

    static SubtitleExtractor.Parsed parse(JSONObject root) throws IOException {
        String full = root.optString("text", "").trim();
        List<SubtitleExtractor.Word> words = new ArrayList<>();
        JSONArray rawWords = root.optJSONArray("words");
        if (rawWords != null) for (int i = 0; i < rawWords.length(); i++) {
            JSONObject w = rawWords.optJSONObject(i);
            if (w == null) throw new IOException("Groq 返回了无效的字级字幕");
            String text = w.optString("word", "").trim();
            if (text.isEmpty()) continue;
            long start = timeMs(w, "start"), end = timeMs(w, "end");
            if (end < start) throw new IOException("Groq 字幕结束时间早于开始时间");
            words.add(new SubtitleExtractor.Word(text, start, end, ""));
        }
        List<SubtitleSegment> segments = new ArrayList<>();
        if (!words.isEmpty()) { words = joinWrittenTokens(words, full); segments = SubtitleExtractor.groupWords(words); }
        else {
            JSONArray raw = root.optJSONArray("segments");
            if (raw != null) for (int i = 0; i < raw.length(); i++) {
                JSONObject s = raw.optJSONObject(i);
                if (s == null) throw new IOException("Groq 返回了无效的字幕段落");
                String text = s.optString("text", "").trim();
                if (text.isEmpty()) continue;
                long start = timeMs(s, "start"), end = timeMs(s, "end");
                if (end < start) throw new IOException("Groq 字幕结束时间早于开始时间");
                segments.add(new SubtitleSegment(start / 10, end / 10, text));
            }
        }
        if (segments.isEmpty()) throw new IOException("Groq 未返回可用的带时间轴字幕；未生成估算时间轴");
        if (full.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (SubtitleSegment segment : segments) {
                if (b.length() > 0) b.append('\n');
                b.append(segment.text);
            }
            full = b.toString();
        }
        return new SubtitleExtractor.Parsed(full, root.optString("language", ""), segments, words);
    }

    /** Join split ASCII tokens only when the returned full transcript proves there is no space.
     * E.g. word timestamps may tokenize AE86 as A / E / 86. This changes no recognized letters.
     * If the two response views differ at all, retain the original words instead of guessing.
     */
    static List<SubtitleExtractor.Word> joinWrittenTokens(List<SubtitleExtractor.Word> words, String full) {
        if (full.isEmpty()) return words;
        int[] starts = new int[words.size()], ends = new int[words.size()];
        int cursor = 0;
        for (int i = 0; i < words.size(); i++) {
            while (cursor < full.length() && Character.isWhitespace(full.charAt(cursor))) cursor++;
            String token = words.get(i).text;
            if (!full.startsWith(token, cursor)) return words;
            starts[i] = cursor;
            cursor += token.length();
            ends[i] = cursor;
        }
        if (!full.substring(cursor).trim().isEmpty()) return words;
        List<SubtitleExtractor.Word> result = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            SubtitleExtractor.Word first = words.get(i), last = first;
            String token = first.text;
            long endMs = first.endMs;
            if (token.matches("[A-Za-z0-9]+")) {
                while (i + 1 < words.size() && ends[i] == starts[i + 1]
                        && words.get(i + 1).text.matches("[A-Za-z0-9]+")
                        && words.get(i + 1).startMs >= last.startMs
                        && words.get(i + 1).startMs - last.endMs < 700) {
                    last = words.get(++i);
                    endMs = Math.max(endMs, last.endMs);
                    token += last.text;
                }
            }
            result.add(new SubtitleExtractor.Word(token, first.startMs, endMs, first.speaker));
        }
        return result;
    }

    private static long timeMs(JSONObject obj, String field) throws IOException {
        double value = obj.optDouble(field, Double.NaN);
        if (!Double.isFinite(value) || value < 0 || value > 24 * 60 * 60)
            throw new IOException("Groq 字幕缺少有效的 " + field + " 时间戳");
        return Math.round(value * 1000);
    }
}

