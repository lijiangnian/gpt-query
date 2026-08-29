package com.example.mediaparser.subtitle;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Imports a small plain-text or SRT reference without copying it into app storage. */
public final class ReferenceTextFile {
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private ReferenceTextFile() {}

    public static Intent picker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "application/x-subrip", "application/octet-stream"
        });
        return intent;
    }

    public static String read(Context context, Uri uri) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalArgumentException("无法读取标准稿文件");
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int count; (count = input.read(buffer)) >= 0; ) {
                total += count;
                if (total > MAX_BYTES) throw new IllegalArgumentException("标准稿不能超过 2 MB");
                output.write(buffer, 0, count);
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') text = text.substring(1);
            text = text.trim();
            if (text.isEmpty()) throw new IllegalArgumentException("标准稿文件为空");
            return text;
        }
    }
}
