package app.hushmessenger.extension;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
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

    /** Whether the app must resolve a Messenger media handle before Android can open it. */
    static boolean isProvider(String address) {
        return address.startsWith("msys://");
    }

    static InputStream open(String address) {
        if (isProvider(address) || address.startsWith("content://")) return providerOpener.apply(address);
        if (isLocal(address)) return openLocal(address);
        return opener.apply(address);
    }

    private static final String TAM_PROVIDER =
        "com.facebook.xapp.messaging.composer.broadcast.transportagnostic.attachment.source.TamAttachmentProvider";

    /**
     * Messenger 587's 5TF.A03 constructs both schemes from the same authority and URI builder.
     * Keep the encoded paths and query intact, and use the registered provider in this package.
     */
    static Uri providerUri(String address, String ownAuthority) {
        Uri uri = Uri.parse(address);
        if ("content".equals(uri.getScheme())) return uri;
        if (!"msys".equals(uri.getScheme()) || ownAuthority == null || !ownAuthority.equals(uri.getEncodedAuthority())
            || uri.getPathSegments().size() < 2) return null;
        return uri.buildUpon().scheme("content").build();
    }

    private static InputStream openProvider(String address) {
        return openProvider(Settings.appContext, address);
    }

    static InputStream openProvider(Context context, String address) {
        if (context == null) return null;
        AssetFileDescriptor asset = null;
        try {
            String authority = context.getPackageName() + ".tam-attachment";
            Uri uri = providerUri(address, authority);
            if (uri == null) {
                VaultDiag.media("resolve", "unsupported_handle");
                return null;
            }
            if (authority.equals(uri.getAuthority())) {
                android.content.pm.ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
                if (provider == null || !context.getPackageName().equals(provider.packageName)
                    || !TAM_PROVIDER.equals(provider.name)) {
                    VaultDiag.media("resolve", "provider_missing");
                    return null;
                }
                // The stock delegate implements doOpenTypedAssetFile. openInputStream requests openAssetFile.
                asset = context.getContentResolver().openTypedAssetFileDescriptor(uri, "*/*", null);
                if (asset == null) {
                    VaultDiag.media("resolve", "empty_descriptor");
                    return null;
                }
                long length = asset.getLength();
                InputStream stream = counted(asset.createInputStream(), length);
                asset = null; // The descriptor's AutoCloseInputStream owns the ParcelFileDescriptor.
                VaultDiag.media("resolve", "tam_stream");
                return stream;
            }
            InputStream stream = context.getContentResolver().openInputStream(uri);
            VaultDiag.media("resolve", stream == null ? "empty_stream" : "content_stream");
            return stream;
        } catch (Exception unreadable) {
            VaultDiag.media("resolve", "provider_error");
            return null;
        } finally {
            if (asset != null) {
                try { asset.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** A stream over a file or content address on the phone, in the vault's own app. Null when it can't be read. */
    private static InputStream openLocal(String address) {
        try {
            if (address.startsWith("/")) {
                return openFile(new java.io.File(address));
            }
            try {
                return openFile(new java.io.File(new java.net.URI(address)));
            } catch (Exception notAUri) {
                String path = address.substring("file:".length());
                while (path.startsWith("//")) path = path.substring(1);
                if (!path.startsWith("/")) path = "/" + path;
                return openFile(new java.io.File(path));
            }
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static InputStream openFile(java.io.File file) throws IOException {
        return counted(new java.io.FileInputStream(file), file.length());
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
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                VaultDiag.media("fetch", status == 401 || status == 403 || status == 410 ? "url_denied" : "http_error");
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
