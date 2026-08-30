package com.example.mediaparser.cloud;

import com.example.mediaparser.net.HttpClient;

import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Minimal OpenList/AList v3/v4 client using documented-compatible auth and fs/get APIs. */
public final class OpenListClient {
    private final OpenListSettings.Config config;
    private String sessionToken;
    public OpenListClient(OpenListSettings.Config config){this.config=config;this.sessionToken=config==null?"":config.token;}
    public String test()throws Exception{requireConfig();HttpClient.Response r=HttpClient.get(config.server+"/api/public/settings",Map.of("User-Agent",HttpClient.DESKTOP_UA));if(!r.ok())throw new IllegalStateException("OpenList 连接失败 HTTP "+r.status);JSONObject root=json(r.body);int code=root.optInt("code",200);if(code!=200)throw apiError(root);ensureToken();return"连接正常"+(sessionToken.isBlank()?" · 匿名访问":" · 鉴权通过");}
    public FileResult get(String path,String password)throws Exception{requireConfig();String clean=normalizePath(path);ensureToken();JSONObject req=new JSONObject();req.put("path",clean);req.put("password",password==null?"":password.trim());HttpClient.Response r=HttpClient.post(config.server+"/api/fs/get",headers(),req.toString());if(!r.ok())throw new IllegalStateException("OpenList 文件查询失败 HTTP "+r.status);return parseFileResult(config.server,clean,r.body);}
    static FileResult parseFileResult(String server,String clean,String body)throws Exception{JSONObject root=json(body);if(root.optInt("code",0)!=200)throw apiError(root);JSONObject data=root.optJSONObject("data");if(data==null)throw new IllegalStateException("OpenList 没有返回文件数据");String raw=data.optString("raw_url","");if(raw.isBlank())throw new IllegalStateException(data.optBoolean("is_dir",false)?"这是文件夹，请填写具体媒体文件路径":"OpenList 未返回 raw_url；存储驱动可能不支持直链");raw=URI.create(server).resolve(raw).toString();if(!raw.startsWith("https://"))throw new IllegalStateException("源站只返回了非 HTTPS 地址，已阻止携带网盘凭证访问");Map<String,String> h=new LinkedHashMap<>();JSONObject ho=data.optJSONObject("header");if(ho!=null){Iterator<String> keys=ho.keys();while(keys.hasNext()){String k=keys.next(),v=ho.optString(k,"");if(!v.isBlank()&&!k.equalsIgnoreCase("Authorization"))h.put(k,v);}}String name=data.optString("name",lastName(clean));long size=data.optLong("size",-1);return new FileResult(name,clean,raw,stableUrl(server,clean),size,h);}
    private void ensureToken()throws Exception{if(!sessionToken.isBlank()||config.username.isBlank())return;JSONObject body=new JSONObject();body.put("username",config.username);body.put("password",config.password);HttpClient.Response r=HttpClient.post(config.server+"/api/auth/login",Map.of("Content-Type","application/json","User-Agent",HttpClient.DESKTOP_UA),body.toString());if(!r.ok())throw new IllegalStateException("OpenList 登录失败 HTTP "+r.status);JSONObject root=json(r.body);if(root.optInt("code",0)!=200)throw apiError(root);JSONObject data=root.optJSONObject("data");sessionToken=data==null?"":data.optString("token","");if(sessionToken.isBlank())throw new IllegalStateException("OpenList 登录成功但没有返回 Token");}
    private Map<String,String> headers(){Map<String,String> h=new LinkedHashMap<>();h.put("Content-Type","application/json");h.put("User-Agent",HttpClient.DESKTOP_UA);if(!sessionToken.isBlank())h.put("Authorization",sessionToken);return h;}
    private void requireConfig(){if(config==null||!config.configured())throw new IllegalStateException("请先配置 OpenList 服务器");if(!config.server.startsWith("https://"))throw new IllegalStateException("OpenList 必须使用 HTTPS");}
    static String normalizePath(String p){String v=p==null?"":p.trim();if(v.isBlank())throw new IllegalArgumentException("请填写 OpenList 文件路径");if(!v.startsWith("/"))v="/"+v;while(v.contains("//"))v=v.replace("//","/");if(v.contains("/../")||v.endsWith("/.."))throw new IllegalArgumentException("文件路径不能包含 ..");return v;}
    public static String stableUrl(String server,String path){StringBuilder b=new StringBuilder();String[] parts=normalizePath(path).split("/",-1);for(String p:parts){if(p.isEmpty())continue;b.append('/').append(enc(p));}return server.replaceAll("/+$","")+"/d"+b;}
    private static String enc(String s){try{return URLEncoder.encode(s,StandardCharsets.UTF_8.name()).replace("+","%20");}catch(Exception e){return s;}}
    private static JSONObject json(String body)throws Exception{if(body==null||body.isBlank()||!body.trim().startsWith("{"))throw new IllegalStateException("服务器返回的不是 OpenList JSON");return new JSONObject(body);}
    private static IllegalStateException apiError(JSONObject root){int c=root.optInt("code",0);String m=root.optString("message","未知错误");if(c==401||c==403)return new IllegalStateException("OpenList 鉴权失败（"+c+"）："+m);if(c==404)return new IllegalStateException("OpenList 路径不存在："+m);return new IllegalStateException("OpenList 错误 "+c+"："+m);}
    private static String lastName(String p){int i=p.lastIndexOf('/');return i>=0&&i+1<p.length()?p.substring(i+1):"media";}
    public static final class FileResult{public final String name,path,rawUrl,stableUrl;public final long size;public final Map<String,String> headers;public FileResult(String name,String path,String rawUrl,String stableUrl,long size,Map<String,String> headers){this.name=name;this.path=path;this.rawUrl=rawUrl;this.stableUrl=stableUrl;this.size=size;this.headers=Map.copyOf(headers);}}
}
