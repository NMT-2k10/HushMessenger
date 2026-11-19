package app.hushmessenger.extension;

import android.content.Context;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * The export half of a full-video save (HANDOFF §15): bytes move whole — capped and hashed as they pass — into a
 * pending MediaStore item that is published under its hashed name only when the copy completes. A dead source
 * leaves nothing behind, an oversized or broken stream abandons its item, and the name says when and what.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 36})
public class ViewOnceVideoExportTest {
    private Context context;
    private FakeGallery gallery;
    private static final Date WHEN = new Date(1_795_071_933_000L); // 2026-11-19 14.05.33 at GMT+07:00

    /** A MediaStore stand-in: one item, its bytes in memory, and a record of how the export treated it. */
    private static final class FakeGallery implements ViewOnceVideoExport.Gallery {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String begunName;
        String finishedName;
        boolean abandoned;
        boolean failFinish;

        @Override public Uri begin(Context context, String name) {
            begunName = name;
            return Uri.parse("content://media/external/video/media/1");
        }

        @Override public OutputStream stream(Context context, Uri item) {
            return bytes;
        }

        @Override public InputStream readBack(Context context, Uri item) {
            return new ByteArrayInputStream(bytes.toByteArray());
        }

        @Override public void finish(Context context, Uri item, String name) throws IOException {
            if (failFinish) throw new IOException("MediaStore didn't publish the video");
            finishedName = name;
        }

        @Override public void abandon(Context context, Uri item) {
            abandoned = true;
        }
    }

    @Before public void open() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.hookErrors.clear();
        CrashGuard.resetForTests();
        context = RuntimeEnvironment.getApplication();
        gallery = new FakeGallery();
        ViewOnceVideoExport.gallery = gallery;
        ViewOnceVideoExport.writes = Runnable::run;
    }

    /**
     * A structurally complete little MP4: an ftyp box (24 bytes), an mdat box filling the middle, and an empty
     * moov box (8 bytes) at the end — real box sizes throughout, so the structure scan has something true to find.
     */
    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        byte[] ftyp = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'i', 's', 'o', '2'};
        System.arraycopy(ftyp, 0, bytes, 0, ftyp.length);
        int mdatSize = size - ftyp.length - 8;
        bytes[ftyp.length + 3] = (byte) mdatSize;
        bytes[ftyp.length] = (byte) (mdatSize >> 24);
        bytes[ftyp.length + 1] = (byte) (mdatSize >> 16);
        bytes[ftyp.length + 2] = (byte) (mdatSize >> 8);
        System.arraycopy(new byte[] {'m', 'd', 'a', 't'}, 0, bytes, ftyp.length + 4, 4);
        for (int i = ftyp.length + 8; i < size - 8; i++) bytes[i] = (byte) (i * 31 + 7);
        System.arraycopy(new byte[] {0, 0, 0, 8, 'm', 'o', 'o', 'v'}, 0, bytes, size - 8, 8);
        return bytes;
    }

    private static ViewOnceVideoExport.Source sourceOf(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }

    @Test public void exportCopiesEveryByteAndPublishesUnderTheHashedName() throws Exception {
        byte[] video = payload(150_000);
        ViewOnceVideoExport.Result result = ViewOnceVideoExport.export(context, sourceOf(video), WHEN);
        assertTrue(result.saved);
        assertEquals(video.length, result.bytes);
        assertArrayEquals(video, gallery.bytes.toByteArray());
        assertEquals(ViewOnceVideoExport.name(result.hash, WHEN), result.name);
        assertEquals(result.name, gallery.finishedName);
        assertFalse(gallery.abandoned);
        // The item began under the pending name, so a half-copied video never wears the final one.
        assertEquals(ViewOnceVideoExport.name(ViewOnceVideoExport.PENDING_HASH, WHEN), gallery.begunName);
    }

    @Test public void copyHashesWhatItCopies() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ViewOnceVideoExport.Copied copied = ViewOnceVideoExport.copy(
            new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), out, 1024);
        assertEquals(3, copied.bytes);
        assertEquals("ba7816bf8f01", copied.hash); // SHA-256("abc"), first 12 hex chars.
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    @Test public void copyNeverWritesPastTheCap() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ViewOnceVideoExport.copy(new ByteArrayInputStream(payload(4096)), out, 1024);
            fail("a stream over the cap must be refused");
        } catch (ViewOnceVideoExport.TooBig expected) {
            assertTrue(out.size() <= 1024);
        }
    }

    @Test public void aDeadSourceLeavesNoMediaStoreItemBehind() {
        ViewOnceVideoExport.Result result = ViewOnceVideoExport.export(context, () -> {
            throw new IOException("the media is gone");
        }, WHEN);
        assertFalse(result.saved);
        assertNull(gallery.begunName);
        assertNull(gallery.finishedName);
    }

    @Test public void aStreamThatBreaksMidCopyAbandonsItsItem() {
        InputStream broken = new InputStream() {
            private final byte[] head = payload(100);
            private int position;

            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (position >= head.length) throw new IOException("connection reset");
                int n = Math.min(head.length - position, length);
                System.arraycopy(head, position, buffer, offset, n);
                position += n;
                return n;
            }

            @Override public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };
        ViewOnceVideoExport.Result result = ViewOnceVideoExport.export(context, () -> broken, WHEN);
        assertFalse(result.saved);
        assertTrue(gallery.abandoned);
        assertNull(gallery.finishedName);
    }

    @Test public void bytesThatAreNotAnMp4NeverBecomeASavedVideo() {
        byte[] notVideo = "<html><body>403 Forbidden</body></html>".getBytes(StandardCharsets.UTF_8);
        ViewOnceVideoExport.Result result = ViewOnceVideoExport.export(context, sourceOf(notVideo), WHEN);
        assertFalse(result.saved);
        // Rejected at the header, before any MediaStore item exists to clean up.
        assertNull(gallery.begunName);
        assertNull(gallery.finishedName);
    }

    @Test public void whenMediaStoreRefusesToPublishTheItemIsAbandoned() {
        gallery.failFinish = true;
        ViewOnceVideoExport.Result result = ViewOnceVideoExport.export(context, sourceOf(payload(2048)), WHEN);
        assertFalse(result.saved);
        assertTrue(gallery.abandoned);
        assertNull(gallery.finishedName);
    }

    @Test public void anFtypFileWithoutMovieBoxesIsAbandonedNotPublished() {
        // A valid ftyp followed by one big "free" box: the header is real, the movie is not there.
        byte[] hollow = new byte[512];
        byte[] ftyp = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'i', 's', 'o', '2'};
        System.arraycopy(ftyp, 0, hollow, 0, ftyp.length);
        int freeSize = hollow.length - ftyp.length;
        hollow[ftyp.length] = (byte) (freeSize >> 24);
        hollow[ftyp.length + 1] = (byte) (freeSize >> 16);
        hollow[ftyp.length + 2] = (byte) (freeSize >> 8);
        hollow[ftyp.length + 3] = (byte) freeSize;
        System.arraycopy(new byte[] {'f', 'r', 'e', 'e'}, 0, hollow, ftyp.length + 4, 4);
        ViewOnceVideoExport.Result result = ViewOnceVideoExport.export(context, sourceOf(hollow), WHEN);
        assertFalse(result.saved);
        assertTrue(gallery.abandoned);
        assertNull(gallery.finishedName);
    }

    @Test public void structureScanRejectsABoxThatRunsPastTheEnd() throws Exception {
        // ftyp, then an mdat box claiming 10,000 bytes in a stream that ends almost at once.
        byte[] truncated = new byte[64];
        System.arraycopy(payload(64), 0, truncated, 0, 24);
        truncated[24] = 0; truncated[25] = 0; truncated[26] = 0x27; truncated[27] = 0x10; // 10,000
        System.arraycopy(new byte[] {'m', 'd', 'a', 't'}, 0, truncated, 28, 4);
        assertFalse(ViewOnceVideoExport.hasMovieStructure(new ByteArrayInputStream(truncated)));
    }

    @Test public void structureScanAcceptsMoovBeforeMdat() throws Exception {
        byte[] faststart = new byte[64];
        System.arraycopy(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0,
            'i', 's', 'o', 'm', 'i', 's', 'o', '2'}, 0, faststart, 0, 24);
        System.arraycopy(new byte[] {0, 0, 0, 8, 'm', 'o', 'o', 'v'}, 0, faststart, 24, 8);
        System.arraycopy(new byte[] {0, 0, 0, 32, 'm', 'd', 'a', 't'}, 0, faststart, 32, 8);
        assertTrue(ViewOnceVideoExport.hasMovieStructure(new ByteArrayInputStream(faststart)));
    }

    @Test public void structureScanReadsToTheEndAndRejectsACorruptTail() throws Exception {
        // ftyp + mdat + moov, then a trailing box whose size (4) is smaller than a box header (8).
        // A scan that stopped at moov would call this file complete; the whole-file scan must not.
        byte[] corruptTail = new byte[56];
        System.arraycopy(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0,
            'i', 's', 'o', 'm', 'i', 's', 'o', '2'}, 0, corruptTail, 0, 24);
        System.arraycopy(new byte[] {0, 0, 0, 8, 'm', 'd', 'a', 't'}, 0, corruptTail, 24, 8);
        System.arraycopy(new byte[] {0, 0, 0, 8, 'm', 'o', 'o', 'v'}, 0, corruptTail, 32, 8);
        System.arraycopy(new byte[] {0, 0, 0, 4, 'j', 'u', 'n', 'k'}, 0, corruptTail, 40, 8);
        assertFalse(ViewOnceVideoExport.hasMovieStructure(new ByteArrayInputStream(corruptTail)));
    }

    @Test public void structureScanRejectsAHeaderCutOffAtEof() throws Exception {
        byte[] cut = new byte[44];
        System.arraycopy(payload(40), 0, cut, 0, 40); // One complete, valid little file...
        // ...then 4 stray bytes: half of another box header, then the stream just ends.
        System.arraycopy(new byte[] {0, 0, 0, 16}, 0, cut, 40, 4);
        assertFalse(ViewOnceVideoExport.hasMovieStructure(new ByteArrayInputStream(cut)));
    }

    @Test public void structureScanRejectsAnUndersizedBoxAndAnEmptyStream() throws Exception {
        byte[] undersized = new byte[32];
        System.arraycopy(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0,
            'i', 's', 'o', 'm', 'i', 's', 'o', '2'}, 0, undersized, 0, 24);
        System.arraycopy(new byte[] {0, 0, 0, 5, 'm', 'd', 'a', 't'}, 0, undersized, 24, 8);
        assertFalse(ViewOnceVideoExport.hasMovieStructure(new ByteArrayInputStream(undersized)));
        assertFalse(ViewOnceVideoExport.hasMovieStructure(new ByteArrayInputStream(new byte[0])));
    }

    @Test public void structureScanRejectsATruncated64BitSize() throws Exception {
        // A box announcing a 64-bit size, with only 3 of its 8 size bytes present before EOF.
        byte[] bytes = new byte[24 + 8 + 3];
        System.arraycopy(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0,
            'i', 's', 'o', 'm', 'i', 's', 'o', '2'}, 0, bytes, 0, 24);
        System.arraycopy(new byte[] {0, 0, 0, 1, 'm', 'd', 'a', 't', 0, 0, 0}, 0, bytes, 24, 11);
        assertFalse(ViewOnceVideoExport.hasMovieStructure(new ByteArrayInputStream(bytes)));
    }

    @Test public void aFullExportQueueReportsFailureInsteadOfThrowing() {
        ViewOnceVideoExport.writes = command -> {
            throw new java.util.concurrent.RejectedExecutionException("queue full");
        };
        ViewOnceVideoExport.Result[] got = new ViewOnceVideoExport.Result[1];
        ViewOnceVideoExport.save(context, sourceOf(payload(64)), result -> got[0] = result);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotNull(got[0]);
        assertFalse(got[0].saved);
    }

    @Test public void nameCarriesTheDateAndHash() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT+07:00"));
        try {
            assertEquals("View once video 2026-11-19 14.05.33 3f9a0c1d2b4e.mp4",
                ViewOnceVideoExport.name("3f9a0c1d2b4e", WHEN));
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }
}
