package com.example.mediaparser.subtitle;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class SubtitleSaver {
    private SubtitleSaver() {}

    public static String saveText(Context context, String baseName, String suffix, String extension,
                                  String mimeType, String content) throws Exception {
        String safe = sanitize(baseName);
        if (safe.isBlank()) safe = "media";
        if (safe.length() > 48) safe = safe.substring(0, 48);
        String name = safe + suffix + "_" + System.currentTimeMillis() + extension;

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("系统无法创建字幕文件");
        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("系统无法写入字幕文件");
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri.toString();
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_").trim();
    }
}
