package com.example.mediaparser.subtitle;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/** Bounded retries and redacted diagnostics for the Gemini JSON endpoints. */
final class GeminiHttp {
    interface Progress { void onStage(String text); }
    interface Attempt { JSONObject run() throws Exception; }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private GeminiHttp() {}

    static JSONObject json(String url, String method, String key, JSONObject body,
                           String stage, Progress progress) throws Exception {
        return retry(() -> once(url, method, key, body, stage), stage, progress, Thread::sleep);
    }

    static JSONObject retry(Attempt attempt, String stage, Progress progress, Sleeper sleeper) throws Exception {
        for (int n = 0; ; n++) {
            try {
                return attempt.run();
            } catch (ApiException e) {
                if (!retryable(e.code) || n >= 2) throw e;
                // Three attempts at most; spacing avoids a burst against low free-tier RPM.
                long delay = Math.max(21_000L * (n + 1), e.retryAfterMs);
                delay += ThreadLocalRandom.current().nextLong(1000L);
                if (progress != null) progress.onStage(stage + "：HTTP " + e.code
                        + "，等待 " + ((delay + 999L) / 1000L) + " 秒后重试（" + (n + 1) + "/2）…");
                sleeper.sleep(delay);
            }
        }
    }

    static boolean retryable(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    private static JSONObject once(String url, String method, String key, JSONObject body,
                                   String stage) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(180_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("x-goog-api-key", key);
            c.setRequestProperty("User-Agent", "MediaParser/0.1.11 Android");
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            int code = c.getResponseCode();
            String response = readBody(c, code);
            if (code < 200 || code >= 300) throw error(stage, code, response, key, c.getHeaderField("Retry-After"));
            try {
                return new JSONObject(response);
            } catch (Exception e) {
                throw new IOException(stage + "：服务未返回有效 JSON；" + redact(response, key));
            }
        } catch (IOException e) {
            // No automatic POST retry on transport timeout: server may already have processed it.
            throw new IOException(stage + "：网络请求失败；" + redact(e.getMessage(), key));
        } finally {
            c.disconnect();
        }
    }

    static String readBody(HttpURLConnection c, int code) throws IOException {
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (stream == null) return "";
        int maxBytes = code >= 400 ? 64 * 1024 : 8 * 1024 * 1024;
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                int remaining = maxBytes - out.size();
                if (count > remaining) {
                    if (code < 400) throw new IOException("云端响应超过安全读取上限");
                    out.write(buffer, 0, remaining);
                    break;
                }
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static ApiException error(String stage, int code, String body, String key, String retryAfter) {
        String detail = body == null ? "" : body;
        try {
            JSONObject err = new JSONObject(detail).optJSONObject("error");
            if (err != null) detail = err.optString("message", detail);
        } catch (Exception ignored) {}
        String hint;
        if (code == 400) hint = "请求参数、音频格式或项目条件不满足（不一定是 Key 错误）";
        else if (code == 401) hint = "Key 缺失、无效或已失效";
        else if (code == 403) hint = "项目权限或地区访问受限";
        else if (code == 404) hint = "模型、接口或文件不存在";
        else if (code == 429) hint = "项目速率或配额受限，请查看所选服务的额度页面";
        else if (code == 413) hint = "音频文件过大";
        else if (code >= 500) hint = "服务端内部错误，不能据此判断 Key 无效";
        else hint = "请求未成功";
        detail = redact(detail, key);
        if (detail.isBlank()) detail = "服务未提供错误正文";
        long retryMs = 0L;
        try { retryMs = Math.max(0L, Math.min(120L, Long.parseLong(retryAfter))) * 1000L; }
        catch (Exception ignored) {}
        return new ApiException(code, retryMs, stage + "：HTTP " + code + " · " + hint + "\n" + detail);
    }

    static String redact(String value, String key) {
        String text = value == null ? "" : value;
        if (key != null && !key.isEmpty()) text = text.replace(key, "[KEY REDACTED]");
        text = text.replaceAll("(?:AIza|AQ\\.)[A-Za-z0-9_.-]{20,}", "[KEY REDACTED]")
                .replaceAll("(?i)(?:https?://)[^\\s<>\"']+", "[URL REDACTED]")
                .replaceAll("gsk_[A-Za-z0-9_-]+", "[KEY REDACTED]")
                .replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return text.length() > 700 ? text.substring(0, 700) + "…" : text;
    }

    static final class ApiException extends Exception {
        final int code;
        final long retryAfterMs;
        ApiException(int code, long retryAfterMs, String message) {
            super(message);
            this.code = code;
            this.retryAfterMs = retryAfterMs;
        }
    }
}
