import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.model.ParseResult;
import com.example.mediaparser.parser.ParserRegistry;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public class LiveParserMain {
    private static final class Case {
        final String name, url;
        Case(String name, String url) { this.name = name; this.url = url; }
    }

    public static void main(String[] args) throws Exception {
        Case[] cases = new Case[] {
            new Case("douyin", "https://www.douyin.com/video/7660061608801996068"),
            new Case("xiaohongshu", "http://xhslink.com/o/6Ayuq9p9ubU"),
            new Case("kuaishou", "https://www.kuaishou.com/short-video/3x2cvn2u7fq8k8s"),
            new Case("bilibili", "https://www.bilibili.com/video/BV1w2M66kEhR/"),
            new Case("weibo", "https://weibo.com/tv/show/1034:5330147385999375")
        };
        ParserRegistry registry = new ParserRegistry();
        int parsePass = 0;
        int mediaProbePass = 0;
        for (Case c : cases) {
            long start = System.currentTimeMillis();
            try {
                ParseResult r = registry.parseText(c.url);
                boolean parsed = r != null && r.hasMedia();
                if (parsed) parsePass++;
                int good = 0;
                System.out.printf("LIVE %s PARSE=%s platform=%s title=%s media=%d ms=%d%n",
                        c.name, parsed ? "PASS" : "FAIL", r == null ? "" : r.platform,
                        r == null ? "" : oneLine(r.title), r == null ? 0 : r.media.size(),
                        System.currentTimeMillis() - start);
                if (r != null) {
                    int idx = 0;
                    for (MediaItem item : r.media) {
                        idx++;
                        int status = probe(item);
                        boolean ok = status >= 200 && status < 400;
                        if (ok) good++;
                        System.out.printf("  MEDIA %d %s %s HTTP=%d URL=%s%n", idx, item.type,
                                ok ? "PASS" : "FAIL", status, shorten(item.url));
                    }
                    if (!r.media.isEmpty() && good == r.media.size()) mediaProbePass++;
                }
            } catch (Throwable t) {
                System.out.printf("LIVE %s PARSE=FAIL ms=%d error=%s%n", c.name,
                        System.currentTimeMillis() - start, oneLine(t.toString()));
            }
        }
        System.out.printf("SUMMARY parse=%d/%d mediaProbeAll=%d/%d%n", parsePass, cases.length, mediaProbePass, cases.length);
        if (parsePass < 2) System.exit(2);
    }

    private static int probe(MediaItem item) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(item.url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Range", "bytes=0-0");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 Chrome/132 Mobile Safari/537.36");
            for (Map.Entry<String, String> e : item.headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            int status = c.getResponseCode();
            try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
                if (in != null) in.readNBytes(1);
            }
            return status;
        } catch (Throwable t) {
            System.out.println("    probe error=" + oneLine(t.toString()));
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() <= 140 ? s : s.substring(0, 140) + "...";
    }
}
