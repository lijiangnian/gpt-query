package com.example.mediaparser.util;

import org.json.JSONArray;
import org.json.JSONObject;

public final class JsonUtil {
    private JsonUtil() {}

    public static Object get(Object root, String... path) {
        Object cur = root;
        for (String p : path) {
            if (cur instanceof JSONObject) {
                cur = ((JSONObject) cur).opt(p);
            } else if (cur instanceof JSONArray) {
                try { cur = ((JSONArray) cur).opt(Integer.parseInt(p)); }
                catch (Exception e) { return null; }
            } else return null;
            if (cur == null || cur == JSONObject.NULL) return null;
        }
        return cur;
    }

    public static String str(Object root, String... path) {
        Object v = get(root, path);
        return v == null ? "" : String.valueOf(v);
    }

    public static int intValue(Object root, String... path) {
        Object v = get(root, path);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    public static JSONObject object(Object root, String... path) {
        Object v = get(root, path);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public static JSONArray array(Object root, String... path) {
        Object v = get(root, path);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    public static String firstUrl(JSONArray array) {
        if (array == null) return "";
        for (int i = 0; i < array.length(); i++) {
            String s = array.optString(i, "");
            if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("//")) return normalizeUrl(s);
        }
        return "";
    }

    public static String normalizeUrl(String u) {
        if (u == null) return "";
        if (u.startsWith("//")) return "https:" + u;
        return u.replace("\\u002F", "/").replace("\\/", "/").replace("&amp;", "&");
    }
}
