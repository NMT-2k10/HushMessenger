package app.hushmessenger.extension;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Classifies the payload and rejects incomplete files before the sealed copy can be published. */
enum VaultMediaFormat {
    JPEG("image/jpeg", ".jpg"), PNG("image/png", ".png"), WEBP("image/webp", ".webp"),
    GIF("image/gif", ".gif"), MP4("video/mp4", ".mp4"), M4A("audio/mp4", ".m4a"),
    WAV("audio/wav", ".wav"), OGG("audio/ogg", ".ogg"), AAC("audio/aac", ".aac");

    final String mime;
    final String suffix;

    VaultMediaFormat(String mime, String suffix) {
        this.mime = mime;
        this.suffix = suffix;
    }

    static VaultMediaFormat forMime(String mime) {
        for (VaultMediaFormat format : values()) if (format.mime.equals(mime)) return format;
        return null;
    }

    String kind(String declared) {
        if (mime.startsWith("video/")) return MessageLogAttachment.VIDEO;
        if (mime.startsWith("audio/")) return MessageLogAttachment.AUDIO;
        if (MessageLogAttachment.STICKER.equals(declared)) return declared;
        if (this == GIF || MessageLogAttachment.GIF.equals(declared)) return MessageLogAttachment.GIF;
        return MessageLogAttachment.PHOTO;
    }

    boolean accepts(String declared) {
        if (MessageLogAttachment.VIDEO.equals(declared)) return this == MP4;
        if (MessageLogAttachment.AUDIO.equals(declared)) return mime.startsWith("audio/");
        if (MessageLogAttachment.STICKER.equals(declared) || MessageLogAttachment.GIF.equals(declared)) {
            return this == GIF || this == JPEG || this == PNG || this == WEBP;
        }
        return !MessageLogAttachment.EPHEMERAL.equals(declared);
    }

    static final class Payload {
        final VaultMediaFormat format;
        final InputStream bytes;

        Payload(VaultMediaFormat format, InputStream bytes) {
            this.format = format;
            this.bytes = bytes;
        }
    }

    static Payload probe(InputStream raw, String declared) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(32);
        byte[] prefix = new byte[32];
        int used = 0;
        while (used < prefix.length) {
            int count = in.read(prefix, used, prefix.length - used);
            if (count < 0) break;
            used += count;
        }
        in.reset();
        VaultMediaFormat format = identify(Arrays.copyOf(prefix, used), declared);
        return format == null ? null : new Payload(format, format.checked(in, prefix));
    }

    private static VaultMediaFormat identify(byte[] bytes, String declared) {
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
            && (bytes[2] & 255) == 255) return JPEG;
        if (starts(bytes, PNG_HEADER)) return PNG;
        if (fourCc(bytes, 0, "RIFF") && fourCc(bytes, 8, "WEBP")) return WEBP;
        if (fourCc(bytes, 0, "RIFF") && fourCc(bytes, 8, "WAVE")) return WAV;
        if (starts(bytes, ascii("GIF87a")) || starts(bytes, ascii("GIF89a"))) return GIF;
        if (fourCc(bytes, 4, "ftyp") && bytes.length >= 16) {
            return fourCc(bytes, 8, "M4A ") || fourCc(bytes, 8, "M4B ")
                || MessageLogAttachment.AUDIO.equals(declared) ? M4A : MP4;
        }
        if (fourCc(bytes, 0, "OggS")) return OGG;
        if (bytes.length >= 7 && (bytes[0] & 255) == 255 && (bytes[1] & 246) == 240) return AAC;
        return null;
    }

    private static final byte[] PNG_HEADER = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] PNG_END = {0, 0, 0, 0, 73, 69, 78, 68, (byte) 174, 66, 96, (byte) 130};

    private static boolean starts(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    private static boolean fourCc(byte[] bytes, int offset, String code) {
        if (bytes.length < offset + 4) return false;
        for (int i = 0; i < 4; i++) if (bytes[offset + i] != code.charAt(i)) return false;
        return true;
    }

    private InputStream checked(InputStream in, byte[] prefix) {
        switch (this) {
            case JPEG: return withEnd(in, new byte[] {(byte) 255, (byte) 217}, 4);
            case PNG: return withEnd(in, PNG_END, 45);
            case GIF: return withEnd(in, new byte[] {59}, 14);
            case WEBP: case WAV: return VaultMedia.counted(in, unsignedInt(prefix, 4, true) + 8);
            case MP4: case M4A: return new IsoStream(in, this);
            default: return in;
        }
    }

    private static long unsignedInt(byte[] bytes, int at, boolean littleEndian) {
        long value = 0;
        for (int i = 0; i < 4; i++) value = (value << 8) | (bytes[at + (littleEndian ? 3 - i : i)] & 255);
        return value;
    }

    private static InputStream withEnd(InputStream in, byte[] expected, int minimum) {
        return new CheckedStream(in) {
            private final byte[] tail = new byte[expected.length];
            private long total;

            @Override void accept(byte[] bytes, int offset, int length) {
                int keep = Math.min(length, tail.length);
                System.arraycopy(tail, keep, tail, 0, tail.length - keep);
                System.arraycopy(bytes, offset + length - keep, tail, tail.length - keep, keep);
                total += length;
            }

            @Override void finish() throws IOException {
                if (total < minimum || !Arrays.equals(expected, tail)) throw new IOException("incomplete image");
            }
        };
    }

    private abstract static class CheckedStream extends FilterInputStream {
        private final byte[] single = new byte[1];

        CheckedStream(InputStream in) { super(in); }

        abstract void accept(byte[] bytes, int offset, int length) throws IOException;
        abstract void finish() throws IOException;

        @Override public int read() throws IOException {
            return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count < 0) finish();
            else if (count > 0) accept(bytes, offset, count);
            return count;
        }
    }

    /** MP4/M4A require a complete sequence of top-level boxes and real media and track metadata. */
    private static final class IsoStream extends CheckedStream {
        private final byte[] header = new byte[16];
        private final VaultMediaFormat format;
        private int headerBytes;
        private long remaining;
        private boolean hasMovie, hasMedia, toEnd;

        IsoStream(InputStream in, VaultMediaFormat format) {
            super(in);
            this.format = format;
        }

        @Override void accept(byte[] bytes, int offset, int length) throws IOException {
            int end = offset + length;
            while (offset < end && !toEnd) {
                if (remaining > 0) {
                    int count = (int) Math.min(remaining, end - offset);
                    remaining -= count;
                    offset += count;
                    continue;
                }
                header[headerBytes++] = bytes[offset++];
                if (headerBytes < 8) continue;
                long size = unsignedInt(header, 0, false);
                if (size == 1) {
                    if (headerBytes < 16) continue;
                    long high = unsignedInt(header, 8, false);
                    if (high > Integer.MAX_VALUE) throw new IOException("invalid container size");
                    size = (high << 32) | unsignedInt(header, 12, false);
                }
                boolean media = fourCc(header, 4, "mdat");
                boolean movie = fourCc(header, 4, "moov");
                if (size == 0 && media) {
                    toEnd = true;
                    hasMedia = offset < end;
                } else {
                    if (size < headerBytes || ((media || movie) && size == headerBytes)) {
                        throw new IOException("invalid container box");
                    }
                    remaining = size - headerBytes;
                    hasMovie |= movie;
                    hasMedia |= media;
                }
                headerBytes = 0;
            }
            if (toEnd && offset < end) hasMedia = true;
        }

        @Override void finish() throws IOException {
            if (headerBytes != 0 || remaining != 0 || !hasMovie || !hasMedia) {
                throw new IOException("incomplete " + format.mime);
            }
        }
    }
}
