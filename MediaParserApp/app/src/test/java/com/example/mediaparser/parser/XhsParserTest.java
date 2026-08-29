package com.example.mediaparser.parser;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class XhsParserTest {
    @Test
    public void preservesSignedCdnTransformationSuffix() throws Exception {
        String signed = "http://sns-webpic-qc.xhscdn.com/path/image!nd_dft_wlteh_jpg_3";
        JSONObject image = new JSONObject().put("urlDefault", signed);

        assertEquals(
                "https://sns-webpic-qc.xhscdn.com/path/image!nd_dft_wlteh_jpg_3",
                XhsParser.originalImageUrl(image));
    }

    @Test
    public void fallsBackToInfoListWithoutChangingUrl() throws Exception {
        JSONObject image = new JSONObject("{\"infoList\":[{\"url\":\"https://example.com/a!signed\"}]}");

        assertEquals("https://example.com/a!signed", XhsParser.originalImageUrl(image));
    }
}
