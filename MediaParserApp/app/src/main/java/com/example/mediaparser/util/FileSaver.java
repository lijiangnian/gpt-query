package com.example.mediaparser.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.example.mediaparser.model.MediaItem;
import com.example.mediaparser.net.HttpClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;

public final class FileSaver {
    private FileSaver() {}

    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    public static String save(Context context, MediaItem item, String baseName) throws Exception {
        if (item.extractsAudioTrack()) return extractAudioTrack(context, item, baseName);
        return saveDirect(context, item, baseName);
    }

    public static File downloadToCache(Context context, MediaItem item, String suffix) throws Exception {
        String ext = (suffix == null || suffix.isBlank()) ? ".media" : suffix;
        File temp = File.createTempFile("mediaparser_subtitle_src_", ext, context.getCacheDir());
        boolean ok = false;
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { streamDownload(item, out, null); }
            ok = true;
            return temp;
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    public interface VideoReady { void accept(File video) throws Exception; }
    public static File prepareAudioForCloud(Context context, MediaItem item, ProgressListener listener) throws Exception {
        return prepareAudioForCloud(context,item,listener,null);
    }
    public static File prepareAudioForCloud(Context context, MediaItem item, ProgressListener listener,VideoReady videoReady) throws Exception {
        if (item == null || item.url.isBlank()) throw new IllegalArgumentException("没有可用音视频");
        if (item.type == MediaItem.Type.AUDIO && !item.extractsAudioTrack()) {
            File audio = File.createTempFile("mediaparser_cloud_audio_", ".m4a", context.getCacheDir());
            boolean ok = false;
            try {
                try (FileOutputStream out = new FileOutputStream(audio)) { streamDownload(item, out, listener); }
                ok = true;
                return audio;
            } finally {
                if (!ok) {
                    //noinspection ResultOfMethodCallIgnored
                    audio.delete();
                }
            }
        }

        File source = File.createTempFile("mediaparser_cloud_video_", ".mp4", context.getCacheDir());
        File audio = File.createTempFile("mediaparser_cloud_audio_", ".m4a", context.getCacheDir());
        boolean ok = false;
        try {
            try (FileOutputStream out = new FileOutputStream(source)) { streamDownload(item, out, listener); }
            remuxAudio(source, audio);
            if(videoReady!=null)videoReady.accept(source);
            ok = true;
            return audio;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                audio.delete();
            }
        }
    }

    private static String saveDirect(Context context, MediaItem item, String baseName) throws Exception {
        String ext = extension(item);
        String safe = safeName(baseName);
        String fileName = safe + "_" + System.currentTimeMillis() + ext;

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = mediaValues(item, fileName, true);
            Uri uri = resolver.insert(collection(item), values);
            if (uri == null) throw new IllegalStateException("系统无法创建媒体文件");
            try {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("系统无法写入媒体文件");
                    streamDownload(item, out, null);
                }
                finishPending(resolver, uri);
                return uri.toString();
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                throw e;
            }
        }

        File folder = legacyFolder(item);
        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("无法创建保存目录");
        File outFile = new File(folder, fileName);
        try (FileOutputStream out = new FileOutputStream(outFile)) { streamDownload(item, out, null); }
        return outFile.getAbsolutePath();
    }

    private static String extractAudioTrack(Context context, MediaItem item, String baseName) throws Exception {
        if (item.type != MediaItem.Type.AUDIO) throw new IllegalArgumentException("只有音频项目支持抽取音轨");
        String safe = safeName(baseName);
        String fileName = safe + "_音频_" + System.currentTimeMillis() + ".m4a";
        File temp = File.createTempFile("mediaparser_audio_src_", ".mp4", context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { streamDownload(item, out, null); }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("系统无法创建音频文件");
                try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rw")) {
                    if (pfd == null) throw new IllegalStateException("系统无法打开音频文件");
                    remuxAudio(temp, pfd);
                    finishPending(resolver, uri);
                    return uri.toString();
                } catch (Exception e) {
                    resolver.delete(uri, null, null);
                    throw e;
                }
            }

            File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("无法创建音乐目录");
            File out = new File(folder, fileName);
            remuxAudio(temp, out);
            return out.getAbsolutePath();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void remuxAudio(File input, ParcelFileDescriptor output) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            Track track = findAudioTrack(extractor);
            if (track == null) throw new IllegalStateException("视频中没有可提取的音轨");
            muxer = new MediaMuxer(output.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            copyTrack(extractor, muxer, track);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("该视频音轨编码暂不支持无损 M4A 提取", e);
        } finally {
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            extractor.release();
        }
    }

    private static void remuxAudio(File input, File output) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            Track track = findAudioTrack(extractor);
            if (track == null) throw new IllegalStateException("视频中没有可提取的音轨");
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            copyTrack(extractor, muxer, track);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("该视频音轨编码暂不支持无损 M4A 提取", e);
        } finally {
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            extractor.release();
        }
    }

    private static Track findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return new Track(i, f);
        }
        return null;
    }

    private static void copyTrack(MediaExtractor extractor, MediaMuxer muxer, Track track) {
        extractor.selectTrack(track.index);
        int muxTrack = muxer.addTrack(track.format);
        muxer.start();
        int max = 2 * 1024 * 1024;
        if (track.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            try { max = Math.max(max, track.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)); } catch (Exception ignored) {}
        }
        max = Math.min(max, 16 * 1024 * 1024);
        ByteBuffer buffer = ByteBuffer.allocateDirect(max);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = Math.max(0, extractor.getSampleTime());
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(muxTrack, buffer, info);
            if (!extractor.advance()) break;
        }
        muxer.stop();
    }

    private static void streamDownload(MediaItem item, OutputStream out, ProgressListener listener) throws Exception {
        String current = item.url;
        for (int redirect = 0; redirect < 7; redirect++) {
            HttpURLConnection c = (HttpURLConnection) URI.create(current).toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("User-Agent", HttpClient.MOBILE_UA);
            for (Map.Entry<String, String> e : item.headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            int status = c.getResponseCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IllegalStateException("媒体重定向缺少 Location");
                current = URI.create(current).resolve(loc).toString();
                continue;
            }
            if (status < 200 || status >= 300) {
                c.disconnect();
                throw new IllegalStateException("媒体下载失败 HTTP " + status);
            }
            validateMediaResponse(item, current, c.getContentType());
            long total = c.getContentLengthLong();
            long done = 0L;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (listener != null) listener.onProgress(done, total);
                }
                out.flush();
            } finally {
                c.disconnect();
            }
            return;
        }
        throw new IllegalStateException("媒体下载重定向过多");
    }


    private static void validateMediaResponse(MediaItem item, String url, String contentType) {
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int q = lowerUrl.indexOf('?');
        if (q >= 0) lowerUrl = lowerUrl.substring(0, q);
        String[] badExt = {".js", ".css", ".json", ".html", ".htm", ".svg", ".woff", ".woff2", ".ttf", ".map"};
        for (String ext : badExt) {
            if (lowerUrl.endsWith(ext)) throw new IllegalStateException("该地址实际不是媒体文件，已阻止保存");
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("text/") || ct.contains("javascript") || ct.contains("json") || ct.contains("xml")) {
            throw new IllegalStateException("服务器返回的不是媒体文件（" + ct + "）");
        }
    }

    private static String extension(MediaItem item) {
        if (!item.preferredExtension.isBlank()) return item.preferredExtension;
        String lower = item.url.toLowerCase(Locale.ROOT);
        if (item.type == MediaItem.Type.IMAGE) {
            if (lower.contains(".png")) return ".png";
            if (lower.contains(".webp")) return ".webp";
            return ".jpg";
        }
        if (item.type == MediaItem.Type.AUDIO) {
            if (lower.contains(".m4a") || lower.contains(".m4s") || lower.contains(".mp4")) return ".m4a";
            if (lower.contains(".aac")) return ".aac";
            return ".mp3";
        }
        return ".mp4";
    }

    private static String mime(MediaItem item) {
        if (!item.mimeType.isBlank()) return item.mimeType;
        String ext = extension(item);
        if (item.type == MediaItem.Type.IMAGE) return ext.equals(".png") ? "image/png" : ext.equals(".webp") ? "image/webp" : "image/jpeg";
        if (item.type == MediaItem.Type.AUDIO) return ext.equals(".m4a") ? "audio/mp4" : ext.equals(".aac") ? "audio/aac" : "audio/mpeg";
        return "video/mp4";
    }

    private static ContentValues mediaValues(MediaItem item, String fileName, boolean pending) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime(item));
        if (pending) values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        return values;
    }

    private static Uri collection(MediaItem item) {
        return MediaStore.Downloads.EXTERNAL_CONTENT_URI;
    }

    private static void finishPending(ContentResolver resolver, Uri uri) {
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
    }

    private static File legacyFolder(MediaItem item) {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    private static String safeName(String baseName) {
        String safe = sanitize(baseName);
        if (safe.isBlank()) safe = "media";
        if (safe.length() > 48) safe = safe.substring(0, 48);
        return safe;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_").trim();
    }

    private static final class Track {
        final int index;
        final MediaFormat format;
        Track(int index, MediaFormat format) { this.index = index; this.format = format; }
    }
}
