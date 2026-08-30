package com.example.mediaparser.parser;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.model.ParseResult;
import com.example.mediaparser.net.HttpClient;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** User-supplied HTTPS media URLs, including temporary raw URLs returned by OpenList. */
public final class DirectMediaParser implements PlatformParser {
    @Override public String platformName(){return"媒体直链";}
    @Override public boolean supports(String url){return type(url)!=null;}
    @Override public ParseResult parse(String url)throws ParseException{if(!url.startsWith("https://"))throw new ParseException("为避免明文传输，只接受 HTTPS 媒体直链");MediaItem.Type t=type(url);if(t==null)throw new ParseException("该地址没有可识别的媒体扩展名");String name=name(url);Map<String,String> h=Map.of("User-Agent",HttpClient.MOBILE_UA);ParseResult.Builder b=ParseResult.builder(platformName(),url).title(name).add(new MediaItem(t,"原始媒体直链",url,h));if(t==MediaItem.Type.VIDEO)b.add(MediaItem.audioTrack("视频完整音轨 · M4A",url,h));return b.build();}
    private static MediaItem.Type type(String u){try{String p=URI.create(u).getPath().toLowerCase(Locale.ROOT);if(p.matches(".*\\.(mp4|m4v|mov|mkv|webm)$"))return MediaItem.Type.VIDEO;if(p.matches(".*\\.(mp3|m4a|aac|wav|flac)$"))return MediaItem.Type.AUDIO;if(p.matches(".*\\.(jpg|jpeg|png|webp)$"))return MediaItem.Type.IMAGE;}catch(Exception ignored){}return null;}
    private static String name(String u){try{String p=URI.create(u).getPath();int i=p.lastIndexOf('/');String n=i>=0?p.substring(i+1):p;return URLDecoder.decode(n,StandardCharsets.UTF_8.name());}catch(Exception e){return"media";}}
}
