package com.example.mediaparser;

import com.example.mediaparser.subtitle.DiagnosticReportSanitizer;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApiDiagnosticsActivityTest {
    @Test public void reportRedactsEveryKnownSecret(){
        String key="provider-example-secret-123456789";
        String token="access-token-example-987654321";
        String out=DiagnosticReportSanitizer.redact("Bearer "+key+"\nAccess Token: "+token, Arrays.asList(key,token));
        assertFalse(out.contains(key));
        assertFalse(out.contains(token));
        assertTrue(out.contains("[已隐藏]"));
    }

    @Test public void genericAuthorizationValuesAreAlsoRedacted(){
        String out=DiagnosticReportSanitizer.redact("Authorization: abcdefghijklmnop", java.util.Collections.emptyList());
        assertFalse(out.contains("abcdefghijklmnop"));
    }
}
