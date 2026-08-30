package com.example.mediaparser.parser;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.model.ParseResult;
import com.example.mediaparser.net.HttpClient;

import org.json.JSONArray;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fast public-page reader. Login/captcha pages are handled by the on-device WebView fallback. */
public final class ZhihuParser implements PlatformParser {
    @Override public String platformName() { return "知乎"; }
    @Override public boolean supports(String url) { return url.contains("zhihu.com") || url.contains("zhihu.cn"); }

    @Override public ParseResult parse(String inputUrl) throws ParseException {
        try {
            Map<String,String> h = new LinkedHashMap<>();
            h.put("User-Agent", HttpClient.DESKTOP_UA);
            h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            h.put("Referer", "https://www.zhihu.com/");
            HttpClient.Response r = HttpClient.get(inputUrl, h);
            if (r.status == 401 || r.status == 403) throw new ParseException("知乎要求验证或登录；将打开本机浏览器兼容模式，不会把 Cookie 上传到第三方。");
            if (!r.ok() || r.body.isBlank()) throw new ParseException("知乎页面读取失败 HTTP " + r.status + "；将尝试浏览器兼容模式。");
            String html = r.body;
            String title = first(meta(html,"property","og:title"), meta(html,"name","title"), tagTitle(html));
            String desc = first(meta(html,"property","og:description"), meta(html,"name","description"));
            if (isGenericSiteText(desc)) desc = "";
            String cover = meta(html,"property","og:image");
            String author = first(meta(html,"name","author"), jsonString(html,"authorName"), jsonString(html,"name"));
            String content = firstJsonContent(html);
            if (isGenericSiteText(content) || cleanHtml(content).equals(cleanHtml(desc))) content = "";

            ParseResult.Builder b = ParseResult.builder(platformName(), r.finalUrl)
                    .title(cleanTitle(title)).author(cleanHtml(author)).description(cleanHtml(desc))
                    .contentText(cleanHtml(content)).coverUrl(htmlDecode(cover));
            Map<String,String> mh = Map.of("Referer","https://www.zhihu.com/","User-Agent",HttpClient.DESKTOP_UA);
            Set<String> seen = new LinkedHashSet<>();
            for (String u : mediaUrls(html)) {
                if (!seen.add(u)) continue;
                MediaItem.Type t = u.toLowerCase().contains(".m3u8") || u.toLowerCase().contains(".mp4") ? MediaItem.Type.VIDEO : MediaItem.Type.IMAGE;
                b.add(new MediaItem(t, t == MediaItem.Type.VIDEO ? "知乎视频" : "知乎图片", u, mh));
            }
            if (!cover.isBlank() && seen.add(cover)) b.add(new MediaItem(MediaItem.Type.IMAGE,"知乎封面",htmlDecode(cover),mh));
            ParseResult out = b.build();
            boolean bodyPage = isBodyPage(inputUrl) || isBodyPage(r.finalUrl);
            if (!out.hasMedia() && (out.title.isBlank() || (bodyPage && out.contentText.isBlank()))) throw new ParseException("知乎公开页面没有返回真实正文；将尝试浏览器兼容模式。");
            return out;
        } catch (ParseException e) { throw e; }
        catch (Exception e) { throw new ParseException("知乎解析失败：" + friendly(e) + "；将尝试浏览器兼容模式。", e); }
    }

    static String meta(String html,String attr,String value) {
        Pattern[] ps = {
                Pattern.compile("(?is)<meta[^>]*"+attr+"\\s*=\\s*['\"]"+Pattern.quote(value)+"['\"][^>]*content\\s*=\\s*['\"](.*?)['\"][^>]*>"),
                Pattern.compile("(?is)<meta[^>]*content\\s*=\\s*['\"](.*?)['\"][^>]*"+attr+"\\s*=\\s*['\"]"+Pattern.quote(value)+"['\"][^>]*>")};
        for(Pattern p:ps){Matcher m=p.matcher(html);if(m.find())return htmlDecode(m.group(1));} return "";
    }
    static String tagTitle(String html){Matcher m=Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);return m.find()?htmlDecode(m.group(1)):"";}
    static String jsonString(String html,String key){Matcher m=Pattern.compile("\\\""+Pattern.quote(key)+"\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").matcher(html);return m.find()?decodeJson(m.group(1)):"";}
    static String firstJsonContent(String html){
        Matcher m=Pattern.compile("\\\"content\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\]){20,})\\\"").matcher(html);
        String best=""; while(m.find()){String v=decodeJson(m.group(1));if(v.length()>best.length())best=v;} return best;
    }
    static Set<String> mediaUrls(String html){
        Set<String> out=new LinkedHashSet<>(); String s=html.replace("\\/","/").replace("\\u002F","/").replace("&amp;","&");
        Matcher m=Pattern.compile("https?://[^\\s'\"<>]+?(?:\\.mp4|\\.m3u8)(?:\\?[^\\s'\"<>]*)?",Pattern.CASE_INSENSITIVE).matcher(s);
        while(m.find()&&out.size()<12)out.add(htmlDecode(m.group())); return out;
    }
    static String cleanHtml(String s){if(s==null)return"";return htmlDecode(s.replaceAll("(?is)<br\\s*/?>","\n").replaceAll("(?is)</p\\s*>","\n").replaceAll("(?is)<[^>]+>","")).replaceAll("[ \\t\\x0B\\f\\r]+"," ").replaceAll("\\n{3,}","\n\n").trim();}
    public static boolean isGenericSiteText(String s){String v=cleanHtml(s);return v.contains("中文互联网高质量的问答社区")||v.contains("让人们更好地分享知识、经验和见解")||v.contains("知识分享社区和创作者聚集的原创内容平台");}
    public static String cleanTitle(String s){return cleanHtml(s).replaceFirst("\\s*[-–—]\\s*知乎\\s*$","").trim();}
    private static boolean isBodyPage(String u){return u!=null&&(u.contains("/answer/")||u.contains("zhuanlan.zhihu.com/p/")||u.matches(".*zhihu\\.com/(?:p|pin)/.*"));}
    static String htmlDecode(String s){if(s==null)return"";return s.replace("&quot;","\"").replace("&#34;","\"").replace("&apos;","'").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">").replace("&nbsp;"," ").replace("&amp;","&").replace("\\u002F","/");}
    static String decodeJson(String raw){try{return new JSONArray("[\""+raw+"\"]").getString(0);}catch(Exception e){return raw.replace("\\n","\\n").replace("\\\"","\"").replace("\\/","/");}}
    private static String first(String...v){for(String s:v)if(s!=null&&!s.isBlank())return s;return"";}
    private static String friendly(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
