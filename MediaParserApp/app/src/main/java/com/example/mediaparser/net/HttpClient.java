package com.example.mediaparser.net;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpClient {
    public static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36";
    public static final String IPHONE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1";
    public static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36";

    private HttpClient() {}

    public static Response get(String url, Map<String, String> headers) throws Exception {
        return request("GET", url, headers, null, 5, 8 * 1024 * 1024);
    }

    public static Response post(String url, Map<String, String> headers, String body) throws Exception {
        return request("POST", url, headers, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), 5, 8 * 1024 * 1024);
    }

    public static Response request(String method, String inputUrl, Map<String, String> headers, byte[] body, int maxRedirects, int maxBytes) throws Exception {
        String current = inputUrl;
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        if (headers != null) requestHeaders.putAll(headers);
        if (!requestHeaders.containsKey("User-Agent")) requestHeaders.put("User-Agent", MOBILE_UA);
        Map<String, String> cookieJar = new LinkedHashMap<>();

        for (int redirect = 0; redirect <= maxRedirects; redirect++) {
            URL u = URI.create(current).toURL();
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            c.setRequestProperty("Accept", "*/*");
            for (Map.Entry<String, String> e : requestHeaders.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (!cookieJar.isEmpty()) c.setRequestProperty("Cookie", joinCookies(cookieJar));
            if (body != null && body.length > 0) {
                c.setDoOutput(true);
                c.getOutputStream().write(body);
            }

            int status = c.getResponseCode();
            absorbCookies(c, cookieJar);
            Map<String, List<String>> responseHeaders = c.getHeaderFields() == null ? Collections.emptyMap() : c.getHeaderFields();

            if (isRedirect(status)) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null || redirect == maxRedirects) {
                    return new Response(status, current, "", responseHeaders, cookieJar);
                }
                current = URI.create(current).resolve(location).toString();
                if (status == 303) {
                    method = "GET";
                    body = null;
                }
                continue;
            }

            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readText(in, maxBytes);
            String finalUrl = c.getURL().toString();
            c.disconnect();
            return new Response(status, finalUrl, text, responseHeaders, cookieJar);
        }
        throw new IllegalStateException("Too many redirects");
    }

    public static byte[] download(String url, Map<String, String> headers, int maxBytes) throws Exception {
        String current = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) URI.create(current).toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", MOBILE_UA);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            int status = c.getResponseCode();
            if (isRedirect(status)) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IllegalStateException("下载重定向缺少地址");
                current = URI.create(current).resolve(location).toString();
                continue;
            }
            if (status < 200 || status >= 300) {
                String msg = c.getErrorStream() == null ? "" : readText(c.getErrorStream(), 2048);
                c.disconnect();
                throw new IllegalStateException("下载失败 HTTP " + status + " " + msg);
            }
            byte[] bytes = readBytes(c.getInputStream(), maxBytes);
            c.disconnect();
            return bytes;
        }
        throw new IllegalStateException("下载重定向过多");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String readText(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) throw new IllegalStateException("页面过大，超过 " + maxBytes + " 字节");
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) throw new IllegalStateException("文件超过允许大小");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void absorbCookies(HttpURLConnection c, Map<String, String> jar) {
        Map<String, List<String>> headers = c.getHeaderFields();
        if (headers == null) return;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            for (String sc : e.getValue()) {
                int semi = sc.indexOf(';');
                String pair = semi >= 0 ? sc.substring(0, semi) : sc;
                int eq = pair.indexOf('=');
                if (eq > 0) jar.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
    }

    private static String joinCookies(Map<String, String> jar) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> e : jar.entrySet()) pairs.add(e.getKey() + "=" + e.getValue());
        return String.join("; ", pairs);
    }

    public static final class Response {
        public final int status;
        public final String finalUrl;
        public final String body;
        public final Map<String, List<String>> headers;
        public final Map<String, String> cookies;

        public Response(int status, String finalUrl, String body, Map<String, List<String>> headers, Map<String, String> cookies) {
            this.status = status;
            this.finalUrl = finalUrl;
            this.body = body == null ? "" : body;
            this.headers = headers == null ? Collections.emptyMap() : headers;
            this.cookies = Collections.unmodifiableMap(new LinkedHashMap<>(cookies));
        }

        public boolean ok() { return status >= 200 && status < 300; }
    }
}
