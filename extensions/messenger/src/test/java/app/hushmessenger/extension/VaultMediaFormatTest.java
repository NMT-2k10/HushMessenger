package app.hushmessenger.extension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.*;

public class VaultMediaFormatTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=");
    private static final byte[] GIF = Base64.getDecoder().decode(
        "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    @Test public void diagnosesImagesGifsAndStickerSemantics() throws Exception {
        assertMedia(PNG, MessageLogAttachment.PHOTO, VaultMediaFormat.PNG, MessageLogAttachment.PHOTO);
        assertMedia(GIF, MessageLogAttachment.PHOTO, VaultMediaFormat.GIF, MessageLogAttachment.GIF);
        assertMedia(GIF, MessageLogAttachment.STICKER, VaultMediaFormat.GIF, MessageLogAttachment.STICKER);
    }

    @Test public void anUnclassifiedMp4IsAVideoAndADeclaredVideoCannotUseAPoster() throws Exception {
        byte[] media = iso("isom");
        assertMedia(media, MessageLogAttachment.PHOTO, VaultMediaFormat.MP4, MessageLogAttachment.VIDEO);
        assertFalse(VaultMediaFormat.PNG.accepts(MessageLogAttachment.VIDEO));
        assertFalse(VaultMediaFormat.PNG.accepts(MessageLogAttachment.AUDIO));
    }

    @Test public void aVoiceContainerIsAudio() throws Exception {
        assertMedia(iso("M4A "), MessageLogAttachment.PHOTO, VaultMediaFormat.M4A, MessageLogAttachment.AUDIO);
    }

    @Test public void anErrorBodyIsNotAnAttachment() throws Exception {
        assertNull(VaultMediaFormat.probe(new ByteArrayInputStream("<html>denied</html>".getBytes(StandardCharsets.UTF_8)),
            MessageLogAttachment.PHOTO));
    }

    @Test public void rejectsTruncatedContainersWithoutALengthHeader() throws Exception {
        for (byte[] whole : new byte[][] {PNG, GIF, iso("isom"), iso("M4A ")}) {
            byte[] cut = Arrays.copyOf(whole, whole.length - 3);
            VaultMediaFormat.Payload probe = VaultMediaFormat.probe(new ByteArrayInputStream(cut), MessageLogAttachment.PHOTO);
            assertNotNull(probe);
            assertThrows(IOException.class, probe.bytes::readAllBytes);
        }
    }

    private static void assertMedia(byte[] bytes, String declared, VaultMediaFormat expected, String kind) throws Exception {
        VaultMediaFormat.Payload payload = VaultMediaFormat.probe(new ByteArrayInputStream(bytes), declared);
        assertNotNull(payload);
        assertEquals(expected, payload.format);
        assertTrue(payload.format.accepts(declared));
        assertEquals(kind, payload.format.kind(declared));
        assertArrayEquals(bytes, payload.bytes.readAllBytes());
    }

    private static byte[] iso(String brand) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(16);
            out.writeBytes("ftyp");
            out.writeBytes(brand);
            out.writeInt(0);
            out.writeInt(12);
            out.writeBytes("moov");
            out.writeInt(123);
            out.writeInt(12);
            out.writeBytes("mdat");
            out.writeInt(456);
        }
        return bytes.toByteArray();
    }
}
