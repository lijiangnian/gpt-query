package com.example.mediaparser.parser;

import com.example.mediaparser.model.ParseResult;

public interface PlatformParser {
    String platformName();
    boolean supports(String url);
    ParseResult parse(String url) throws ParseException;
}
