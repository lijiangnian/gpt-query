package com.example.mediaparser.subtitle;

import java.net.HttpURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Checks authentication and model visibility only; never runs inference. */
public final class GroqKeyValidator {
    private GroqKeyValidator() {}

    public static GeminiKeyValidator.Result validate(String apiKey) {
        return validate(apiKey, TranscriptionOptions.defaults().groqModel());
    }

    public static GeminiKeyValidator.Result validate(String apiKey, String model) {
        if (!TranscriptionOptions.ACCURATE_MODEL.equals(model) && !TranscriptionOptions.FAST_MODEL.equals(model))
            return GeminiKeyValidator.Result.invalid("不支持的 Groq 模型");
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) return GeminiKeyValidator.Result.invalid("Groq Key 为空");
        HttpURLConnection c = null;
        try {
            c = GroqTranscriber.open("/models", "GET", key);
            c.setReadTimeout(20_000);
            int code = c.getResponseCode();
            String body = GeminiHttp.readBody(c, code);
            if (code < 200 || code >= 300)
                return GeminiKeyValidator.Result.error(GeminiHttp.error("Groq Key 验证", code, body, key, null).getMessage());
            JSONArray models = new JSONObject(body).optJSONArray("data");
            if (models != null) for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m != null && model.equals(m.optString("id")))
                    return GeminiKeyValidator.Result.ok("Groq 鉴权通过 · " + model + " 可见（尚未验证实际转写）");
            }
            return GeminiKeyValidator.Result.error("Groq 鉴权通过，但未找到 " + model + "，请检查项目模型权限");
        } catch (Exception e) {
            return GeminiKeyValidator.Result.network("Groq 验证失败：" + GeminiHttp.redact(e.getMessage(), key));
        } finally { if (c != null) c.disconnect(); }
    }
}
