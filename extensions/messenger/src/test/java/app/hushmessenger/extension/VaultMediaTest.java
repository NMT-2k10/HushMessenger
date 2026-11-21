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
}
