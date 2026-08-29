package com.example.mediaparser.subtitle;

import java.util.List;

/** Pure-Java final barrier before a diagnostics report can be copied or saved. */
public final class DiagnosticReportSanitizer {
    private DiagnosticReportSanitizer(){}
    public static String redact(String raw,List<String> secrets){String x=raw==null?"":raw;for(String s:secrets)if(s!=null&&!s.isBlank())x=x.replace(s,"[已隐藏]");return x.replaceAll("(?i)(api[_ -]?key|access[_ -]?token|authorization|bearer)([\\s:=]+)[A-Za-z0-9._\\-]{8,}","$1$2[已隐藏]");}
}
