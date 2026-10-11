package com.smartlib.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Detects real file types from magic bytes so a renamed file cannot masquerade as another type. */
public final class FileSniffer {
    private FileSniffer() {
    }

    public record Sniffed(String mime, String extension) {
    }

    public static byte[] head(InputStream in, int n) throws IOException {
        byte[] buf = in.readNBytes(n);
        return buf;
    }

    public static Optional<Sniffed> detect(byte[] h) {
        if (h.length >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-') {
            return Optional.of(new Sniffed("application/pdf", "pdf"));
        }
        if (h.length >= 58 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4) {
            String marker = new String(Arrays.copyOfRange(h, 30, 58), StandardCharsets.US_ASCII);
            if (marker.equals("mimetypeapplication/epub+zip")) {
                return Optional.of(new Sniffed("application/epub+zip", "epub"));
            }
        }
        if (h.length >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            return Optional.of(new Sniffed("video/mp4", "mp4"));
        }
        if (h.length >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) {
            return Optional.of(new Sniffed("video/webm", "webm"));
        }
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return Optional.of(new Sniffed("image/jpeg", "jpg"));
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return Optional.of(new Sniffed("image/png", "png"));
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return Optional.of(new Sniffed("image/webp", "webp"));
        }
        return Optional.empty();
    }

    public static boolean isImage(Sniffed s) {
        return s.mime().startsWith("image/");
    }
}
