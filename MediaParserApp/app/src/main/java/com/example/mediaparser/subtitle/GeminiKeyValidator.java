package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Lightweight Gemini API-key validation. It does not upload user media or run inference. */
public final class GeminiKeyValidator {
    private static final String MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000";
    private static final String TARGET_MODEL = "gemini-3.5-transcribe";

    private GeminiKeyValidator() {}

    public static Result validate(String apiKey) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isBlank()) return Result.invalid("Key 为空");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(MODELS_URL).toURL().openConnection();
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("x-goog-api-key", key);
            c.setRequestProperty("User-Agent", "MediaParser/0.1.11 Android");
            int code = c.getResponseCode();
            String body = readBody(c, code);
            if (code >= 200 && code < 300) {
                boolean found = hasTargetModel(body);
                return found
                        ? Result.ok("Key 验证通过 · 转写模型可见（尚未验证实际转写）")
                        : Result.okWithWarning("Key 可用，但当前项目的模型列表未返回 Gemini 3.5 Transcribe");
            }
            String detail = GeminiHttp.redact(apiMessage(body), key);
            if (code == 400) return Result.error("请求或项目条件不满足" + suffix(detail));
            if (code == 401) return Result.invalid("Key 无效或已失效" + suffix(detail));
            if (code == 403) return Result.invalid("Key 无权限、项目/API 未启用或被限制" + suffix(detail));
            if (code == 429) return Result.quota("当前项目触发额度或速率限制" + suffix(detail));
            return Result.error("验证失败 HTTP " + code + suffix(detail));
        } catch (Exception e) {
            return Result.network("网络验证失败：" + GeminiHttp.redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), key));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean hasTargetModel(String body) {
        try {
            JSONObject root = new JSONObject(body == null ? "" : body);
            JSONArray models = root.optJSONArray("models");
            if (models == null) return body != null && body.contains(TARGET_MODEL);
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m == null) continue;
                String name = m.optString("name", "");
                if (name.endsWith("/" + TARGET_MODEL) || TARGET_MODEL.equals(name)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String readBody(HttpURLConnection c, int code) {
        try {
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) out.append(line);
                return out.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String apiMessage(String body) {
        try {
            JSONObject root = new JSONObject(body == null ? "" : body);
            JSONObject error = root.optJSONObject("error");
            return error == null ? "" : error.optString("message", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String suffix(String s) { return s == null || s.isBlank() ? "" : "：" + s; }

    public static final class Result {
        public enum State { OK, WARNING, INVALID, QUOTA, NETWORK, ERROR }
        public final State state;
        public final String message;
        private Result(State state, String message) { this.state = state; this.message = message; }
        public boolean usable() { return state == State.OK || state == State.WARNING; }
        public static Result ok(String m) { return new Result(State.OK, m); }
        public static Result okWithWarning(String m) { return new Result(State.WARNING, m); }
        public static Result invalid(String m) { return new Result(State.INVALID, m); }
        public static Result quota(String m) { return new Result(State.QUOTA, m); }
        public static Result network(String m) { return new Result(State.NETWORK, m); }
        public static Result error(String m) { return new Result(State.ERROR, m); }
    }
}
