package app.hushmessenger.extension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** How the vault reaches a captured message's media: which address, and whole files only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class VaultMediaTest {

    /** An address on the phone is the file itself: it is picked without asking any server how big it is. */
    @Test public void aLocalAddressIsPickedOverWebOnes() {
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(new String[] {
            "https://cdn.example.com/video.mp4?oe=6A1B2C3D",
            "file:///data/user/0/com.facebook.orca/cache/media/video-1.mp4"},
            MessageLogAttachment.VIDEO);
        assertEquals("file:///data/user/0/com.facebook.orca/cache/media/video-1.mp4", VaultMedia.pick(found));
    }

    /** A stream that ends before the length it promised fails at its end, so a cut-short file is never kept. */
    @Test public void aShortDownloadFailsAtItsEnd() throws Exception {
        byte[] promised = "0123456789".getBytes(StandardCharsets.UTF_8);
        InputStream counted = VaultMedia.counted(new ByteArrayInputStream(promised), promised.length + 5);
        byte[] buffer = new byte[64];
        try {
            while (counted.read(buffer) != -1) { }
            fail("a download that ended early must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ended"));
        }
    }

    /** A stream that delivers exactly what it promised reads clean to its end. */
    @Test public void aWholeDownloadReadsClean() throws Exception {
        byte[] promised = "0123456789".getBytes(StandardCharsets.UTF_8);
        InputStream counted = VaultMedia.counted(new ByteArrayInputStream(promised), promised.length);
        int total = 0;
        byte[] buffer = new byte[4];
        int read;
        while ((read = counted.read(buffer)) != -1) total += read;
        assertEquals(promised.length, total);
    }

    /** A body that arrives longer than its headers announced — decompressed on the way — is whole at its end. */
    @Test public void aLongerThanAnnouncedDownloadReadsClean() throws Exception {
        byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);
        InputStream counted = VaultMedia.counted(new ByteArrayInputStream(body), 4);
        int total = 0;
        byte[] buffer = new byte[4];
        int read;
        while ((read = counted.read(buffer)) != -1) total += read;
        assertEquals(body.length, total);
    }

    /** A file on the phone opens as itself, byte for byte. */
    @Test public void aLocalFileOpensAsItself() throws Exception {
        java.io.File file = java.io.File.createTempFile("vault-media", ".jpg");
        byte[] bytes = "the photo itself".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(file.toPath(), bytes);
        try (InputStream in = VaultMedia.open(file.toURI().toString())) {
            assertNotNull(in);
            assertArrayEquals(bytes, in.readAllBytes());
        } finally {
            file.delete();
        }
    }

    /** A bare path — the form an encrypted chat's photo arrives in — opens as the file it names. */
    @Test public void aBarePathOpensAsTheFileItNames() throws Exception {
        java.io.File file = java.io.File.createTempFile("vault-media", ".jpg");
        byte[] bytes = "the decrypted photo".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(file.toPath(), bytes);
        try (InputStream in = VaultMedia.open(file.getAbsolutePath())) {
            assertNotNull(in);
            assertArrayEquals(bytes, in.readAllBytes());
        } finally {
            file.delete();
        }
    }

    /** The fetch order: web addresses before the media store, and the store before anything else. */
    @Test public void theFetchOrderPutsTheMediaStoreAfterWebAddresses() {
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(new String[] {
            "msys://ae-media/222?sha256=bbb",
            "http://cdn.example.com/photo-m.jpg",
            "https://cdn.example.com/photo-l.jpg"},
            MessageLogAttachment.PHOTO);
        assertEquals(java.util.List.of(
            "https://cdn.example.com/photo-l.jpg",
            "msys://ae-media/222?sha256=bbb",
            "http://cdn.example.com/photo-m.jpg"), VaultMedia.ordered(found));
    }

    /** A media-store reference opens through the app's content resolver, byte for byte. */
    @Test public void aMediaStoreReferenceOpensThroughTheProvider() throws Exception {
        byte[] bytes = "the decrypted photo".getBytes(StandardCharsets.UTF_8);
        java.util.function.Function<String, InputStream> kept = VaultMedia.providerOpener;
        VaultMedia.providerOpener = address -> new ByteArrayInputStream(bytes);
        try (InputStream in = VaultMedia.open("msys://ae-media/222?sha256=bbb")) {
            assertNotNull(in);
            assertArrayEquals(bytes, in.readAllBytes());
        } finally {
            VaultMedia.providerOpener = kept;
        }
    }

    /** The fetch order: every address on the phone before any web address, each group in the reader's order. */
    @Test public void theFetchOrderPutsLocalAddressesFirst() {
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(new String[] {
            "https://cdn.example.com/photo-l.jpg?oe=6A1B2C3D",
            "/data/user/0/com.facebook.orca/cache/media/photo-m.jpg",
            "file:///data/user/0/com.facebook.orca/cache/media/photo-s.jpg",
            "https://cdn.example.com/photo-s.jpg?oe=6A1B2C3D"},
            MessageLogAttachment.PHOTO);
        assertEquals(java.util.List.of(
            "/data/user/0/com.facebook.orca/cache/media/photo-m.jpg",
            "file:///data/user/0/com.facebook.orca/cache/media/photo-s.jpg",
            "https://cdn.example.com/photo-l.jpg?oe=6A1B2C3D",
            "https://cdn.example.com/photo-s.jpg?oe=6A1B2C3D"), VaultMedia.ordered(found));
    }

    @Test public void tamHandleKeepsTheStockAuthorityAndAllEncodedParameters() {
        String authority = "com.facebook.orca.vault72h.tam-attachment";
        String encoded = authority + "/123/token%2Fxyz?content_type=3&is_decouple_tam=true&rl=8";
        assertEquals("content://" + encoded, VaultMedia.providerUri("msys://" + encoded, authority).toString());
        assertNull(VaultMedia.providerUri("msys://ae-media/222?sha256=bbb", authority));
        assertNull(VaultMedia.providerUri("msys://foreign.tam-attachment/123/token", authority));
        assertNull(VaultMedia.providerUri("msys://" + authority + "/123", authority));
    }

    @Test public void contentResolverReturnsAStreamNullAndAnError() throws Exception {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        String authority = "vault-test-input";
        java.io.File backing = java.io.File.createTempFile("vault-provider", ".test");
        byte[] bytes = {9, 7, 0, 1};
        java.nio.file.Files.write(backing.toPath(), bytes);
        android.content.ContentProvider provider = new android.content.ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public String getType(android.net.Uri uri) { return "application/octet-stream"; }
            @Override public android.database.Cursor query(android.net.Uri uri, String[] fields, String filter,
                String[] args, String order) { return null; }
            @Override public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) {
                throw new UnsupportedOperationException();
            }
            @Override public int delete(android.net.Uri uri, String filter, String[] args) {
                throw new UnsupportedOperationException();
            }
            @Override public int update(android.net.Uri uri, android.content.ContentValues values,
                String filter, String[] args) { throw new UnsupportedOperationException(); }
            @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri uri, String mode)
                throws java.io.FileNotFoundException {
                if ("/null".equals(uri.getPath())) return null;
                if ("/error".equals(uri.getPath())) throw new java.io.FileNotFoundException();
                return android.os.ParcelFileDescriptor.open(backing, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            }
        };
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = authority;
        provider.attachInfo(context, info);
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider);
        try {
            try (InputStream in = VaultMedia.openProvider(context, "content://" + authority + "/valid")) {
                assertNotNull(in);
                assertArrayEquals(bytes, in.readAllBytes());
            }
            assertNull(VaultMedia.openProvider(context, "content://" + authority + "/null"));
            assertNull(VaultMedia.openProvider(context, "content://" + authority + "/error"));
        } finally {
            backing.delete();
        }
    }

}
