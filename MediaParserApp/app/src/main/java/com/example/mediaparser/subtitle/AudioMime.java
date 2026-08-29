package com.example.mediaparser.subtitle;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Read the container signature, not the temporary .m4a filename. */
final class AudioMime {
    private AudioMime() {}

    static String detect(File file) throws IOException {
        byte[] head;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64];
            int used = 0, count;
            while (used < buffer.length && (count = in.read(buffer, used, buffer.length - used)) > 0) used += count;
            head = java.util.Arrays.copyOf(buffer, used);
        }
        return detect(head);
    }

    static String detect(byte[] h) throws IOException {
        if (at(h, 4, "ftyp")) return "audio/mp4";
        if (at(h, 0, "RIFF") && at(h, 8, "WAVE")) return "audio/wav";
        if (at(h, 0, "fLaC")) return "audio/flac";
        if (at(h, 0, "OggS")) return "audio/ogg";
        if (at(h, 0, "ID3")) return "audio/mpeg";
        if (h.length >= 2 && (h[0] & 255) == 255) {
            if ((h[1] & 0xf6) == 0xf0) return "audio/aac";
            if ((h[1] & 0xe0) == 0xe0 && (h[1] & 0x06) != 0) return "audio/mpeg";
        }
        if (h.length >= 4 && (h[0] & 255) == 0x1a && (h[1] & 255) == 0x45
                && (h[2] & 255) == 0xdf && (h[3] & 255) == 0xa3) return "audio/webm";
        throw new IOException("准备音频：无法识别下载文件的音频容器，请确认源链接返回有效音频");
    }

    private static boolean at(byte[] bytes, int offset, String signature) {
        byte[] expected = signature.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (bytes[offset + i] != expected[i]) return false;
        return true;
    }
}
