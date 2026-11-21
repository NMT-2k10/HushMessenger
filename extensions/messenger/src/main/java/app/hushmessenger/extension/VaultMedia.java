package app.hushmessenger.extension;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * How the vault reaches a captured message's media file. The address is the one the message itself carried, the
 * same address {@link MessageLogAttachment} reads for the message log; which of a video's two addresses is the
 * video is settled the same way too, by asking the server how big each is. The download itself is a stream, so
 * a file of any size can be kept without holding it in memory, and the vault sets no size limit of its own.
 */
final class VaultMedia {
    private VaultMedia() {}

    private static final int TIMEOUT_MS = 60_000;

    /** Opens the stream at an address. Tests swap this in to answer without a network. */
    static java.util.function.Function<String, InputStream> opener = VaultMedia::openRemote;

    /**
     * Which of [found]'s addresses holds the file itself. An address on the phone — a file or content address
     * Messenger already fetched and decrypted — is the file itself and needs no asking; otherwise the message
     * log's rule decides, asking the server how big each web address is. Null when nothing answers.
     */
    static String pick(MessageLogAttachment.Found found) {
        for (String address : found.addresses) {
            if (address.startsWith("file:") || address.startsWith("content://")) return address;
        }
        return MessageLogAttachment.pick(found);
    }

    static InputStream open(String address) {
        if (address.startsWith("file:") || address.startsWith("content://")) return openLocal(address);
        return opener.apply(address);
    }

    /** A stream over a file or content address on the phone, in the vault's own app. Null when it can't be read. */
    private static InputStream openLocal(String address) {
        try {
            if (address.startsWith("content://")) {
                android.content.Context context = Settings.appContext;
                if (context == null) return null;
                return context.getContentResolver().openInputStream(android.net.Uri.parse(address));
            }
            java.io.File file = new java.io.File(new java.net.URI(address));
            return new java.io.FileInputStream(file);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static InputStream openRemote(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                return null;
            }
            final HttpURLConnection held = connection;
            InputStream in = counted(connection.getInputStream(), connection.getContentLengthLong());
            return new InputStream() {
                @Override public int read() throws IOException { return in.read(); }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    return in.read(buffer, offset, length);
                }
                @Override public void close() throws IOException {
                    try { in.close(); } finally { held.disconnect(); }
                }
            };
        } catch (Exception unreachable) {
            if (connection != null) connection.disconnect();
            return null;
        }
    }

    /**
     * A stream that fails at its end when fewer bytes arrived than [expected] said would — a download cut short
     * mid-file must never be sealed and reported as the whole video. An unknown length (-1) is taken as it comes.
     */
    static InputStream counted(InputStream in, long expected) {
        return new InputStream() {
            private long read = 0;
            private boolean ended = false;

            @Override public int read() throws IOException {
                int next = in.read();
                if (next < 0) end();
                else read++;
                return next;
            }

            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                int got = in.read(buffer, offset, length);
                if (got < 0) end();
                else read += got;
                return got;
            }

            private void end() throws IOException {
                if (ended) return;
                ended = true;
                if (expected >= 0 && read != expected) {
                    throw new IOException("the download ended at " + read + " of " + expected + " bytes");
                }
            }

            @Override public void close() throws IOException { in.close(); }
        };
    }
}
