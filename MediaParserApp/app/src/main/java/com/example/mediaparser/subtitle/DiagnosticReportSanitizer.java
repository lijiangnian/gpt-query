package com.example.mediaparser.subtitle;

import java.util.List;
import java.util.ArrayList;

/** Pure-Java final barrier before a diagnostics report can be copied or saved. */
public final class DiagnosticReportSanitizer {
    private DiagnosticReportSanitizer(){}
    public static String redact(String raw,List<String> secrets){String x=raw==null?"":raw;for(String s:secrets)if(s!=null&&!s.isBlank())x=x.replace(s,"[已隐藏]");return x.replaceAll("(?i)(api[_ -]?key|access[_ -]?token|authorization|bearer)([\\s:=]+)[A-Za-z0-9._\\-]{8,}","$1$2[已隐藏]");}
    public static List<String> pages(String report,int maxLines){int limit=Math.max(4,maxLines);String[] lines=(report==null?"":report).split("\\r?\\n",-1);ArrayList<String> out=new ArrayList<>();StringBuilder page=new StringBuilder();int count=0;for(String line:lines){if(count>=limit&&line.startsWith("【")){out.add(page.toString().trim());page.setLength(0);count=0;}if(count>=limit){out.add(page.toString().trim());page.setLength(0);count=0;}if(page.length()>0)page.append('\n');page.append(line);count++;}if(page.length()>0||out.isEmpty())out.add(page.toString().trim());return out;}
}
