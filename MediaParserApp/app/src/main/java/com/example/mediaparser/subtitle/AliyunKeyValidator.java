package com.example.mediaparser.subtitle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Read-only Alibaba Model Studio credential validation.
 *
 * <p>This calls the workspace quota-list endpoint and never uploads media or
 * starts an inference task. A successful response proves that the API key and
 * workspace match and that Paraformer-v2 is visible to the workspace. The
 * endpoint exposes rate limits, not the remaining free audio duration.</p>
 */
public final class AliyunKeyValidator {
    private static final String TARGET_MODEL = "paraformer-v2";

    private AliyunKeyValidator() {}

    public static GeminiKeyValidator.Result validate(String apiKey, String workspace) {
        GeminiKeyValidator.Result para=validateModel(apiKey,workspace,TARGET_MODEL);
        GeminiKeyValidator.Result qwen=validateModel(apiKey,workspace,Qwen3AsrTranscriber.MODEL);
        if(para.usable()&&qwen.usable())return GeminiKeyValidator.Result.ok("阿里云鉴权通过 · Paraformer-v2 与 Qwen3-ASR 均可用");
        if(para.usable())return GeminiKeyValidator.Result.ok("阿里云鉴权通过 · Paraformer-v2 可用；Qwen3-ASR："+qwen.message);
        if(qwen.usable())return GeminiKeyValidator.Result.ok("阿里云鉴权通过 · Qwen3-ASR 可用；Paraformer-v2："+para.message);
        return para;
    }

    public static GeminiKeyValidator.Result validateQwen3(String apiKey,String workspace){
        return validateModel(apiKey,workspace,Qwen3AsrTranscriber.MODEL);
    }

    static GeminiKeyValidator.Result validateModel(String apiKey, String workspace,String targetModel) {
        String key = apiKey == null ? "" : apiKey.trim();
        String ws = workspace == null ? "" : workspace.trim();
        if (key.isBlank()) return GeminiKeyValidator.Result.invalid("阿里云 Key 为空");
        if (!ws.matches("[A-Za-z0-9-]{3,80}"))
            return GeminiKeyValidator.Result.invalid("Workspace ID 格式不正确");

        HttpURLConnection connection = null;
        try {
            String model = URLEncoder.encode(targetModel, StandardCharsets.UTF_8);
            String url = "https://" + ws + ".cn-beijing.maas.aliyuncs.com/api/v1/quotas"
                    + "?model=" + model + "&page_no=1&page_size=20";
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "MediaParser/0.2.1 Android");

            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status >= 200 && status < 300) {
                if (hasTargetModel(body,targetModel)) {
                    return GeminiKeyValidator.Result.ok(
                            "阿里云鉴权通过 · Workspace 匹配 · "+targetModel+" 可用");
                }
                return GeminiKeyValidator.Result.error(
                        "鉴权通过，但当前 Workspace 未返回 "+targetModel+"；请在业务空间开通对应 ASR 权限");
            }

            String detail = GeminiHttp.redact(apiMessage(body), key);
            if (status == 401)
                return GeminiKeyValidator.Result.invalid("Key 无效或已失效" + suffix(detail));
            if (status == 403)
                return GeminiKeyValidator.Result.invalid(
                        "Key 与 Workspace 不匹配、没有权限或免费额度用完即停" + suffix(detail));
            if (status == 404)
                return GeminiKeyValidator.Result.invalid("Workspace ID 不存在或地域不正确" + suffix(detail));
            if (status == 429)
                return GeminiKeyValidator.Result.quota("当前工作空间触发速率限制" + suffix(detail));
            return GeminiKeyValidator.Result.error("验证失败 HTTP " + status + suffix(detail));
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return GeminiKeyValidator.Result.network(
                    "阿里云验证失败：" + GeminiHttp.redact(message, key));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static boolean hasTargetModel(String body) {
        return hasTargetModel(body,TARGET_MODEL);
    }
    static boolean hasTargetModel(String body,String targetModel) {
        try {
            JSONObject root = new JSONObject(body == null ? "" : body);
            JSONObject output = root.optJSONObject("output");
            if (output == null) return false;
            JSONArray quotas = output.optJSONArray("quotas");
            if (quotas == null) return false;
            for (int i = 0; i < quotas.length(); i++) {
                JSONObject quota = quotas.optJSONObject(i);
                if (quota != null && targetModel.equals(quota.optString("model"))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String readBody(HttpURLConnection connection, int status) {
        try {
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) return "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) out.append(line);
                return out.toString();
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String apiMessage(String body) {
        try {
            JSONObject root = new JSONObject(body == null ? "" : body);
            String message = root.optString("message", "");
            if (!message.isBlank()) return message;
            JSONObject output = root.optJSONObject("output");
            return output == null ? "" : output.optString("message", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String suffix(String detail) {
        return detail == null || detail.isBlank() ? "" : "：" + detail;
    }
}
