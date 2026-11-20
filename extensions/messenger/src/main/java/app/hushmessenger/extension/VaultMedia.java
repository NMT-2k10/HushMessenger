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

    /** Which of [found]'s addresses holds the file itself. Null when the server won't say. */
    static String pick(MessageLogAttachment.Found found) {
        return MessageLogAttachment.pick(found);
    }

    static InputStream open(String address) {
        return opener.apply(address);
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
            InputStream in = connection.getInputStream();
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
}
