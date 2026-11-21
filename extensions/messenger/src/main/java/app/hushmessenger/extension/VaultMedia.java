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

    /** Opens a media-store reference through the app's own content resolver. Tests swap this in too. */
    static java.util.function.Function<String, InputStream> providerOpener = VaultMedia::openProvider;

    /**
     * Which of [found]'s addresses holds the file itself. An address on the phone — a file or content address
     * Messenger already fetched and decrypted — is the file itself and needs no asking; otherwise the message
     * log's rule decides, asking the server how big each web address is. Null when nothing answers.
     */
    static String pick(MessageLogAttachment.Found found) {
        for (String address : found.addresses) {
            if (isLocal(address)) return address;
        }
        return MessageLogAttachment.pick(found);
    }

    /**
     * [found]'s addresses in the order the vault tries them: addresses on the phone first (already fetched
     * and decrypted, and they cannot expire), then web addresses, then references into Messenger's own media
     * store, then anything else in the order the reader ranked them. The fetch walks this list until one
     * address yields the whole file, so one dead address never decides an entry.
     */
    static java.util.List<String> ordered(MessageLogAttachment.Found found) {
        java.util.List<String> local = new java.util.ArrayList<>();
        java.util.List<String> web = new java.util.ArrayList<>();
        java.util.List<String> store = new java.util.ArrayList<>();
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (String address : found.addresses) {
            if (isLocal(address)) local.add(address);
            else if (address.startsWith("https://")) web.add(address);
            else if (isProvider(address)) store.add(address);
            else rest.add(address);
        }
        local.addAll(web);
        local.addAll(store);
        local.addAll(rest);
        return local;
    }

    /** Whether an address names a file on the phone rather than a web address. */
    static boolean isLocal(String address) {
        return address.startsWith("file:") || address.startsWith("content://") || address.startsWith("/");
    }

    /**
     * Whether an address is a reference into Messenger's own media store. That is how an encrypted chat
     * names a message's media: the reference itself is not a file or a web address, and the app resolves it
     * through its content resolver when it needs the file behind the message — the same way the vault opens
     * it here.
     */
    static boolean isProvider(String address) {
        return address.startsWith("msys://");
    }

    static InputStream open(String address) {
        if (isLocal(address)) return openLocal(address);
        if (isProvider(address)) return providerOpener.apply(address);
        return opener.apply(address);
    }

    /** A stream over a media-store reference, read through the vault's own app's content resolver. */
    private static InputStream openProvider(String address) {
        try {
            android.content.Context context = Settings.appContext;
            if (context == null) return null;
            return context.getContentResolver().openInputStream(android.net.Uri.parse(address));
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** A stream over a file or content address on the phone, in the vault's own app. Null when it can't be read. */
    private static InputStream openLocal(String address) {
        try {
            if (address.startsWith("content://")) {
                android.content.Context context = Settings.appContext;
                if (context == null) return null;
                return context.getContentResolver().openInputStream(android.net.Uri.parse(address));
            }
            if (address.startsWith("/")) {
                return new java.io.FileInputStream(new java.io.File(address));
            }
            try {
                return new java.io.FileInputStream(new java.io.File(new java.net.URI(address)));
            } catch (Exception notAUri) {
                String path = address.substring("file:".length());
                while (path.startsWith("//")) path = path.substring(1);
                if (!path.startsWith("/")) path = "/" + path;
                return new java.io.FileInputStream(new java.io.File(path));
            }
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
            // The file itself, unencoded: a transparently decompressed body would arrive longer than the
            // length its headers announced, and the counted stream below would call a whole file cut short.
            connection.setRequestProperty("Accept-Encoding", "identity");
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
     * mid-file must never be sealed and reported as the whole video. More bytes than announced is not a
     * failure: a body the network decompressed on the way arrives longer than its headers said, and it is
     * whole at its end all the same. An unknown length (-1) is taken as it comes.
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
                if (expected >= 0 && read < expected) {
                    throw new IOException("the download ended at " + read + " of " + expected + " bytes");
                }
            }

            @Override public void close() throws IOException { in.close(); }
        };
    }
}
