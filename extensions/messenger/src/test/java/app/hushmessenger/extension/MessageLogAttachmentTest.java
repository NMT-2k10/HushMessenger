package app.hushmessenger.extension;

import android.net.Uri;
import com.facebook.messaging.model.attachment.Attachment;
import com.facebook.messaging.model.attachment.AttachmentImageMap;
import com.facebook.messaging.model.attachment.AudioData;
import com.facebook.messaging.model.attachment.ImageData;
import com.facebook.messaging.model.attachment.ImageUrl;
import com.facebook.messaging.model.attachment.VideoData;
import com.facebook.messaging.model.messages.Message;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Keeping a message's own media: finding its address, fetching it, and pointing the entry at the file. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class MessageLogAttachmentTest {
    private static final String ADDRESS = "https://cdn.example.com/audio.mp4?oe=6A1B2C3D";
    private static final String SMALL = "https://cdn.example.com/photo-s.jpg?oe=6A1B2C3D";
    private static final String BIG = "https://cdn.example.com/photo-l.jpg?oe=6A1B2C3D";
    /** A video's own address and the thumbnail Messenger sends beside it, which look alike on the message. */
    private static final String MOVIE = "https://cdn.example.com/video.mp4?oe=6A1B2C3D";
    private static final String THUMB = "https://cdn.example.com/video-thumb.jpg?oe=6A1B2C3D";
    private static final String THREAD = "thread-7";
    private static final String ID = "mid.100";
    /** Stands in for the fetch, so no test reaches a network. */
    private static final byte[] CLIP = "ftypM4A clip bytes".getBytes(StandardCharsets.UTF_8);

    private java.util.function.Function<String, byte[]> download;
    private java.util.function.ToLongFunction<String> measure;
    private java.util.function.LongConsumer retryLater;
    private final List<String> asked = new java.util.ArrayList<>();
    private final List<String> measured = new java.util.ArrayList<>();

    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
        CrashGuard.onProcessStart(RuntimeEnvironment.getApplication());
        retryLater = MessageLog.retryLater;
        MessageLog.retryLater = delay -> { };
        MessageLog.retry();
        MessageLog.forgetCaptures();
        Settings.hookErrors.clear();
        MessageLog.vault = new MessageLogTest.SoftwareVault();
        logFile().delete();
        MessageLogMedia.deleteAll(Settings.appContext);
        download = MessageLogAttachment.download;
        measure = MessageLogAttachment.measure;
        asked.clear();
        measured.clear();
        MessageLogAttachment.download = address -> {
            asked.add(address);
            return CLIP;
        };
        // What the server says about a video and its thumbnail, which is how the two are told apart.
        MessageLogAttachment.measure = address -> {
            measured.add(address);
            return MOVIE.equals(address) ? 2_000_000 : THUMB.equals(address) ? 40_000 : -1;
        };
        Settings.preferences.edit().putBoolean(MessageLog.KEY, true).putBoolean(MessageLog.KEEP_VOICE, true)
            .putBoolean(MessageLog.KEEP_PHOTOS, true).putBoolean(MessageLog.KEEP_VIDEOS, true).commit();
    }

    @After public void tidy() {
        MessageLogAttachment.download = download;
        MessageLogAttachment.measure = measure;
        MessageLog.retryLater = retryLater;
        logFile().delete();
        MessageLogMedia.deleteAll(Settings.appContext);
        MessageLog.vault = null;
    }

    private File logFile() {
        return new File(Settings.appContext.getFilesDir(), MessageLog.FILE);
    }

    private static Message voiceMessage() {
        return Message.with(Attachment.audio(new AudioData(Uri.parse(ADDRESS))));
    }

    private static Message photoMessage() {
        return Message.with(Attachment.image(ImageData.of(
            AttachmentImageMap.of(new ImageUrl(240, 180, SMALL)),
            AttachmentImageMap.of(new ImageUrl(1440, 1920, BIG), new ImageUrl(480, 640, SMALL)))));
    }

    private static Message videoMessage() {
        return Message.with(Attachment.video(new VideoData(Uri.parse(MOVIE), Uri.parse(THUMB))));
    }

    private static MessageLogAttachment.Found audio() {
        return new MessageLogAttachment.Found(ADDRESS, MessageLogAttachment.AUDIO);
    }

    private static MessageLogAttachment.Found photo() {
        return new MessageLogAttachment.Found(BIG, MessageLogAttachment.PHOTO);
    }

    private static MessageLogAttachment.Found video() {
        return new MessageLogAttachment.Found(new String[] {MOVIE, THUMB}, MessageLogAttachment.VIDEO);
    }

    private static String nameOf(String kind) {
        return MessageLogAttachment.name(THREAD, ID, kind);
    }

    @Test public void theAddressIsFoundThroughTheAttachmentsOwnTypes() {
        MessageLogAttachment.Found found = MessageLogAttachment.found(voiceMessage());
        assertEquals(ADDRESS, found.address());
        assertEquals(MessageLogAttachment.AUDIO, found.kind);
    }

    /** Messenger offers the same photo at several sizes, and only the biggest is worth keeping. */
    @Test public void aPhotosBiggestAddressIsTheOneFound() {
        MessageLogAttachment.Found found = MessageLogAttachment.found(photoMessage());
        assertEquals(BIG, found.address());
        assertEquals(MessageLogAttachment.PHOTO, found.kind);
    }

    /** A photo with no size against any address still gets kept, from the first address that's the message's own. */
    @Test public void aPhotoWithNoSizesStillHasAnAddress() {
        Message message = Message.with(Attachment.image(ImageData.of(null,
            AttachmentImageMap.of(new ImageUrl(0, 0, BIG)))));
        assertEquals(BIG, MessageLogAttachment.found(message).address());
    }

    /**
     * A video arrives with its own address and its thumbnail's, both the same type and neither marked, so both are
     * carried off Messenger's thread and nothing is asked over the network while the message is being logged.
     */
    @Test public void bothOfAVideosAddressesAreCarried() {
        MessageLogAttachment.Found found = MessageLogAttachment.found(videoMessage());
        assertEquals(MessageLogAttachment.VIDEO, found.kind);
        assertArrayEquals(new String[] {MOVIE, THUMB}, found.addresses);
        assertEquals(List.of(), measured);
        assertEquals(List.of(), asked);
    }

    @Test public void anotherKindOfAttachmentHasNoAddressToFind() {
        assertNull(MessageLogAttachment.found(Message.with(Attachment.video(new VideoData(null)))));
        assertNull(MessageLogAttachment.found(Message.with()));
        assertNull(MessageLogAttachment.found(Message.with(Attachment.audio(new AudioData(null)))));
        assertNull(MessageLogAttachment.found(Message.with(Attachment.image(ImageData.of(null, null)))));
        assertNull(MessageLogAttachment.found(new Object()));
        assertNull(MessageLogAttachment.found(null));
    }

    /** Only the signed address the message came with. A file already on the phone is Messenger's to hand out, not ours. */
    @Test public void anAddressThatIsntTheMessagesOwnIsLeftAlone() {
        for (String address : new String[] {"file:///data/data/com.facebook.orca/clip.mp4", "content://media/external/audio/1",
            "http://cdn.example.com/audio.mp4"}) {
            assertNull(address, MessageLogAttachment.found(Message.with(Attachment.audio(new AudioData(Uri.parse(address))))));
            assertNull(address, MessageLogAttachment.found(Message.with(Attachment.image(ImageData.of(null,
                AttachmentImageMap.of(new ImageUrl(1440, 1920, address)))))));
        }
    }

    @Test public void nothingIsReadWithTheSwitchesOff() {
        Settings.preferences.edit().putBoolean(MessageLog.KEEP_VOICE, false).commit();
        assertNull(MessageLogAttachment.found(voiceMessage()));
        assertEquals(BIG, MessageLogAttachment.found(photoMessage()).address());
        Settings.preferences.edit().putBoolean(MessageLog.KEEP_VIDEOS, false).commit();
        assertNull(MessageLogAttachment.found(videoMessage()));
        Settings.preferences.edit().putBoolean(MessageLog.KEEP_PHOTOS, false).commit();
        assertNull(MessageLogAttachment.found(photoMessage()));
        Settings.preferences.edit().putBoolean(MessageLog.KEEP_VOICE, true).putBoolean(MessageLog.KEY, false).commit();
        assertNull(MessageLogAttachment.found(voiceMessage()));
        Settings.preferences.edit().putBoolean(MessageLog.KEY, true).putBoolean("paused", true).commit();
        assertNull(MessageLogAttachment.found(voiceMessage()));
    }

    /** The hook hands the message over before its text, and what it found waits there for the log's own call. */
    @Test public void theHookHandsTheAddressOverWithTheMessage() {
        Settings.incomingMessageMedia(voiceMessage());
        assertEquals(ADDRESS, Settings.takeIncomingMessageMedia().address());
        assertNull(Settings.takeIncomingMessageMedia());
        Settings.incomingMessageMedia(voiceMessage());
        Settings.incomingMessageMedia(Message.with());
        assertNull("a message with no media clears what the one before it left", Settings.takeIncomingMessageMedia());
    }

    /** The message stored, then its clip fetched and sealed where the log's screen plays it from. */
    @Test public void aClipIsKeptWithItsMessage() throws Exception {
        MessageLog.storeNow(null, THREAD, ID);
        assertEquals(nameOf(MessageLogAttachment.AUDIO), MessageLogAttachment.keepNow(THREAD, ID, audio()));
        assertEquals(List.of(ADDRESS), asked);
        List<MessageLog.Entry> entries = MessageLog.entries();
        assertEquals(1, entries.size());
        MessageLog.Entry kept = entries.get(0);
        assertTrue(kept.attachment);
        assertEquals(MessageLogAttachment.AUDIO, kept.mediaType);
        assertEquals(nameOf(MessageLogAttachment.AUDIO), kept.media);
        assertArrayEquals(CLIP, MessageLogMedia.open(Settings.appContext, kept.media));
        assertTrue(Settings.hookErrors.isEmpty());
    }

    /** A photo takes the name the notification's own preview would have, so the entry only ever has one picture. */
    @Test public void aPhotoIsKeptUnderThePicturesOwnName() throws Exception {
        MessageLog.storeNow(null, THREAD, ID);
        assertEquals(MessageLog.digest(THREAD + '\u0000' + ID), nameOf(MessageLogAttachment.PHOTO));
        assertEquals(nameOf(MessageLogAttachment.PHOTO), MessageLogAttachment.keepNow(THREAD, ID, photo()));
        assertEquals(List.of(BIG), asked);
        MessageLog.Entry kept = MessageLog.entries().get(0);
        assertEquals("", kept.mediaType);
        assertEquals(nameOf(MessageLogAttachment.PHOTO), kept.media);
        assertArrayEquals(CLIP, MessageLogMedia.open(Settings.appContext, kept.media));
    }

    /** The video, not the thumbnail beside it: the server says how big each is, and a thumbnail is a fraction of its video. */
    @Test public void theBiggerOfAVideosAddressesIsTheOneKept() throws Exception {
        MessageLog.storeNow(null, THREAD, ID);
        assertEquals(nameOf(MessageLogAttachment.VIDEO), MessageLogAttachment.keepNow(THREAD, ID, video()));
        assertEquals(List.of(MOVIE, THUMB), measured);
        assertEquals(List.of(MOVIE), asked);
        MessageLog.Entry kept = MessageLog.entries().get(0);
        assertEquals(MessageLogAttachment.VIDEO, kept.mediaType);
        assertEquals(nameOf(MessageLogAttachment.VIDEO), kept.media);
        assertArrayEquals(CLIP, MessageLogMedia.open(Settings.appContext, kept.media));
        assertNotEquals(nameOf(MessageLogAttachment.PHOTO), kept.media);
        assertTrue(Settings.hookErrors.isEmpty());
    }

    /** Some servers won't answer how big a file is, and then the only way left to tell the two apart is to fetch both. */
    @Test public void bothAreFetchedWhenNothingSaysHowBigTheyAre() throws Exception {
        byte[] movie = new byte[4096];
        MessageLogAttachment.measure = address -> {
            measured.add(address);
            return -1;
        };
        MessageLogAttachment.download = address -> {
            asked.add(address);
            return MOVIE.equals(address) ? movie : CLIP;
        };
        MessageLog.storeNow(null, THREAD, ID);
        assertEquals(nameOf(MessageLogAttachment.VIDEO), MessageLogAttachment.keepNow(THREAD, ID, video()));
        assertEquals(List.of(MOVIE, THUMB), asked);
        assertArrayEquals(movie, MessageLogMedia.open(Settings.appContext, MessageLog.entries().get(0).media));
    }

    /** A video is the largest thing the log keeps, and one past that size leaves its message logged without it. */
    @Test public void aVideoPastItsSizeIsntKept() {
        MessageLogAttachment.download = address -> new byte[MessageLogAttachment.MAX_VIDEO_BYTES + 1];
        MessageLog.storeNow(null, THREAD, ID);
        assertEquals("", MessageLogAttachment.keepNow(THREAD, ID, video()));
        assertTrue(MessageLog.entries().get(0).media.isEmpty());
        assertEquals(MessageLogAttachment.MAX_VIDEO_BYTES, MessageLogMedia.MAX_ANY_BYTES);
        assertTrue(MessageLogAttachment.MAX_VIDEO_BYTES > MessageLogAttachment.MAX_PHOTO_BYTES);
    }

    /** The clip can land while the message is still waiting to be written, and then the one write carries both. */
    @Test public void aClipThatArrivesBeforeTheWriteGoesInWithIt() throws Exception {
        MessageLog.queue(null, THREAD, ID);
        assertEquals(nameOf(MessageLogAttachment.AUDIO), MessageLogAttachment.keepNow(THREAD, ID, audio()));
        MessageLog.drain();
        List<MessageLog.Entry> entries = MessageLog.entries();
        assertEquals(1, entries.size());
        assertEquals(MessageLogAttachment.AUDIO, entries.get(0).mediaType);
        assertArrayEquals(CLIP, MessageLogMedia.open(Settings.appContext, entries.get(0).media));
    }

    /** Messenger builds one message's notification twice, and the second fetch is the one that isn't needed. */
    @Test public void theSameClipIsFetchedOnce() {
        assertEquals(nameOf(MessageLogAttachment.AUDIO), MessageLogAttachment.keepNow(THREAD, ID, audio()));
        assertEquals(nameOf(MessageLogAttachment.AUDIO), MessageLogAttachment.keepNow(THREAD, ID, audio()));
        assertEquals(List.of(ADDRESS), asked);
    }

    @Test public void aMessageStaysLoggedWhenItsMediaCantBeFetched() {
        MessageLogAttachment.download = address -> {
            asked.add(address);
            return null;
        };
        MessageLog.storeNow(null, THREAD, ID);
        assertEquals("", MessageLogAttachment.keepNow(THREAD, ID, audio()));
        List<MessageLog.Entry> entries = MessageLog.entries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).media.isEmpty());
        assertEquals("", entries.get(0).mediaType);
        assertFalse(new File(Settings.appContext.getFilesDir(), MessageLogMedia.DIR).isDirectory());
    }

    /** A file past the kind's own size isn't kept, so one huge photo can't push the rest of the log out. */
    @Test public void mediaPastItsSizeIsntKept() {
        MessageLogAttachment.download = address -> new byte[MessageLogAttachment.MAX_AUDIO_BYTES + 1];
        MessageLog.storeNow(null, THREAD, ID);
        assertEquals("", MessageLogAttachment.keepNow(THREAD, ID, audio()));
        assertTrue(MessageLog.entries().get(0).media.isEmpty());
        assertTrue(MessageLogAttachment.MAX_PHOTO_BYTES > MessageLogAttachment.MAX_AUDIO_BYTES);
    }

    /** A picture off the notification is what the log already had for this message, so it keeps it. */
    @Test public void aPictureAlreadyKeptIsntReplaced() throws Exception {
        MessageLog.storeNow(null, THREAD, ID);
        String picture = MessageLog.digest(THREAD + '\u0000' + ID);
        File dir = new File(Settings.appContext.getFilesDir(), MessageLogMedia.DIR);
        dir.mkdirs();
        MessageLog.sealInto(dir, picture, "a picture".getBytes(StandardCharsets.UTF_8));
        assertTrue(MessageLog.attachNow(THREAD, ID, picture, ""));
        assertFalse(MessageLog.attachNow(THREAD, ID, nameOf(MessageLogAttachment.AUDIO), MessageLogAttachment.AUDIO));
        List<MessageLog.Entry> entries = MessageLog.entries();
        assertEquals(picture, entries.get(0).media);
        assertEquals("", entries.get(0).mediaType);
    }

    /** A clip whose message isn't in the log has nothing to belong to, and the next write drops the file. */
    @Test public void aClipWithNoMessageIsntKept() {
        assertFalse(MessageLog.attachNow(THREAD, ID, nameOf(MessageLogAttachment.AUDIO), MessageLogAttachment.AUDIO));
    }

    /** The kind is a column of its own, and a log written before it still reads the way it did. */
    @Test public void theKindSurvivesAWriteAndAnOlderLogStillReads() throws Exception {
        MessageLog.write(Settings.appContext, List.of(
            new MessageLog.Entry(1_700_000_000_000L, THREAD, "", "Ana", "", ID, false, true, "a".repeat(64), MessageLogAttachment.AUDIO),
            new MessageLog.Entry(1_700_000_001_000L, THREAD, "", "Ana", "", "mid.101", false, true, "b".repeat(64)),
            new MessageLog.Entry(1_700_000_002_000L, THREAD, "", "Ana", "", "mid.102", false, true, "c".repeat(64), MessageLogAttachment.VIDEO)));
        List<MessageLog.Entry> back = MessageLog.read(logFile());
        assertEquals(3, back.size());
        assertEquals(MessageLogAttachment.AUDIO, back.get(0).mediaType);
        assertEquals("a".repeat(64), back.get(0).media);
        assertEquals("", back.get(1).mediaType);
        assertEquals("b".repeat(64), back.get(1).media);
        assertEquals(MessageLogAttachment.VIDEO, back.get(2).mediaType);
        assertEquals("c".repeat(64), back.get(2).media);
    }

    /** A kind the log doesn't know reads as a picture, which is what every entry before this was. */
    @Test public void anUnknownKindReadsAsAPicture() throws Exception {
        MessageLog.sealInto(Settings.appContext.getFilesDir(), MessageLog.FILE,
            ("1700000000000\t" + THREAD + "\t\tAna\t\t" + ID + "\t\t" + "a".repeat(64) + "\tsomething\n").getBytes(StandardCharsets.UTF_8));
        List<MessageLog.Entry> back = MessageLog.read(logFile());
        assertEquals(1, back.size());
        assertEquals("", back.get(0).mediaType);
    }

    /** An unsend marks the clip's message like any other, and the clip stays with it. */
    @Test public void anUnsentVoiceMessageKeepsItsClip() {
        MessageLog.storeNow(null, THREAD, ID);
        assertEquals(nameOf(MessageLogAttachment.AUDIO), MessageLogAttachment.keepNow(THREAD, ID, audio()));
        assertTrue(MessageLog.markNow(ID, THREAD, ""));
        MessageLog.Entry marked = MessageLog.entries().get(0);
        assertTrue(marked.unsent);
        assertEquals(MessageLogAttachment.AUDIO, marked.mediaType);
        assertEquals(nameOf(MessageLogAttachment.AUDIO), marked.media);
    }

    /** The vault reads the same message its own way: the biggest photo address across both of its maps. */
    @Test public void theVaultFindsTheLargestPhotoAddress() {
        MessageLogAttachment.Found found = MessageLogAttachment.foundForVault(photoMessage());
        assertNotNull(found);
        assertEquals(MessageLogAttachment.PHOTO, found.kind);
        assertEquals(BIG, found.address());
    }

    /** The vault's read doesn't ride on the log's switches: with every one of them off it still finds the media. */
    @Test public void theVaultReadIgnoresTheLogsSwitches() {
        Settings.preferences.edit().putBoolean(MessageLog.KEEP_PHOTOS, false)
            .putBoolean(MessageLog.KEEP_VIDEOS, false).putBoolean(MessageLog.KEEP_VOICE, false).commit();
        assertNull(MessageLogAttachment.found(photoMessage()));
        assertNotNull(MessageLogAttachment.foundForVault(photoMessage()));
        assertNotNull(MessageLogAttachment.foundForVault(voiceMessage()));
        MessageLogAttachment.Found video = MessageLogAttachment.foundForVault(videoMessage());
        assertNotNull(video);
        assertEquals(MessageLogAttachment.VIDEO, video.kind);
        assertEquals(2, video.addresses.length);
    }

    /**
     * A photo whose maps are there but hold no address — the shape a message can arrive in before Messenger
     * fills its sizes in — gives the vault nothing, and says nothing back: the caller records that as its own
     * visible state rather than as a photo that silently has no file.
     */
    @Test public void theVaultFindsNothingWhenThePhotoMapsAreEmpty() {
        Message message = Message.with(Attachment.image(ImageData.of(
            AttachmentImageMap.of(), AttachmentImageMap.of())));
        assertNull(MessageLogAttachment.foundForVault(message));
        Message noMaps = Message.with(Attachment.image(ImageData.of(null, null)));
        assertNull(MessageLogAttachment.foundForVault(noMaps));
    }

    /** A view-once attachment is never the vault's, even though it carries a thumbnail's sizes like a photo. */
    @Test public void theVaultNeverTakesAViewOnceAttachment() {
        Message message = Message.with(Attachment.ephemeral(ImageData.of(
            AttachmentImageMap.of(new ImageUrl(240, 180, SMALL)),
            AttachmentImageMap.of(new ImageUrl(1440, 1920, BIG)))));
        MessageLogAttachment.Found found = MessageLogAttachment.foundForVault(message);
        assertNotNull(found);
        assertEquals(MessageLogAttachment.EPHEMERAL, found.kind);
        assertEquals(0, found.addresses.length);
    }

    /**
     * The encrypted-chat shape from the device: the attachment's image maps are there but empty, and the
     * message's media resource carries the photo as a file Messenger already fetched and decrypted. The
     * vault's scan finds it there, under its encrypted kind.
     */
    @Test public void theVaultScanFindsAnEncryptedPhotoOnTheMediaResource() {
        Message message = Message.withMedia(
            Attachment.image(ImageData.of(AttachmentImageMap.of(), AttachmentImageMap.of())),
            com.facebook.ui.media.attachments.model.MediaResource.of(
                com.facebook.ui.media.attachments.model.MediaResource.Kind.ENCRYPTED_PHOTO,
                Uri.parse("file:///data/user/0/com.facebook.orca/cache/media/photo-1.jpg")));
        MessageLogAttachment.VaultRead read = MessageLogAttachment.inspectForVault(message);
        assertFalse(read.ephemeral);
        assertNotNull(read.found);
        assertEquals(MessageLogAttachment.PHOTO, read.found.kind);
        assertEquals("file:///data/user/0/com.facebook.orca/cache/media/photo-1.jpg", read.found.address());
        assertEquals(MessageLogAttachment.PHOTO, read.shapeKind);
        assertTrue(read.diag, read.diag.contains("maps=0,0"));
        assertTrue(read.diag, read.diag.contains("ENCRYPTED_PHOTO"));
        assertTrue(read.diag, read.diag.contains("file"));
        assertFalse(read.diag, read.diag.contains("photo-1.jpg"));
    }

    /** A video the thread view would resolve from a media resource: its web address is the vault's to fetch. */
    @Test public void theVaultScanFindsAVideoOnTheMediaResource() {
        Message message = Message.withMedia(
            Attachment.video(new VideoData(null, null)),
            com.facebook.ui.media.attachments.model.MediaResource.of(
                com.facebook.ui.media.attachments.model.MediaResource.Kind.VIDEO, Uri.parse(MOVIE)));
        MessageLogAttachment.VaultRead read = MessageLogAttachment.inspectForVault(message);
        assertNotNull(read.found);
        assertEquals(MessageLogAttachment.VIDEO, read.found.kind);
        assertEquals(MOVIE, read.found.address());
        assertEquals(MessageLogAttachment.VIDEO, read.shapeKind);
    }

    /**
     * When nothing on the message can be fetched, the scan still names the kind and fingerprints what the
     * message carried, so the entry is filed as a photo with the reason on it instead of as a text message.
     */
    @Test public void theVaultScanNamesTheShapeWhenNothingCanBeFetched() {
        Message message = Message.with(Attachment.image(ImageData.of(
            AttachmentImageMap.of(), AttachmentImageMap.of())));
        MessageLogAttachment.VaultRead read = MessageLogAttachment.inspectForVault(message);
        assertNull(read.found);
        assertFalse(read.ephemeral);
        assertEquals(MessageLogAttachment.PHOTO, read.shapeKind);
        assertTrue(read.diag, read.diag.contains("img=1"));
        assertTrue(read.diag, read.diag.contains("maps=0,0"));
    }

    /** A view-once message's media resources are never read, even when one carries a usable address. */
    @Test public void theVaultScanNeverReadsAViewOnceMessagesResources() {
        Message message = Message.withMedia(
            Attachment.ephemeral(ImageData.of(AttachmentImageMap.of(), AttachmentImageMap.of())),
            com.facebook.ui.media.attachments.model.MediaResource.of(
                com.facebook.ui.media.attachments.model.MediaResource.Kind.PHOTO,
                Uri.parse("file:///data/user/0/com.facebook.orca/cache/media/photo-2.jpg")));
        MessageLogAttachment.VaultRead read = MessageLogAttachment.inspectForVault(message);
        assertTrue(read.ephemeral);
        assertNull(read.found);
    }

    /** An https address in the attachment's extra string map is a copy the message itself carried. */
    @Test public void theVaultScanFindsAnAddressInTheAttachmentsExtraMap() {
        Attachment attachment = Attachment.image(ImageData.of(
            AttachmentImageMap.of(), AttachmentImageMap.of()));
        attachment.A09 = new java.util.HashMap<>();
        attachment.A09.put("is_preview", "0");
        attachment.A09.put("media", BIG);
        MessageLogAttachment.VaultRead read = MessageLogAttachment.inspectForVault(Message.with(attachment));
        assertNotNull(read.found);
        assertEquals(MessageLogAttachment.PHOTO, read.found.kind);
        assertEquals(BIG, read.found.address());
    }

    /**
     * The shape the device actually reported (diag "maps=-1,4"): the first size map is absent and the second
     * holds four entries whose strings are bare paths to files Messenger already fetched and decrypted into
     * its own cache — the only form an encrypted chat hands over. The vault takes the biggest of them; the
     * message log, which only ever kept web addresses, still takes none.
     */
    @Test public void theVaultTakesAPhotoCarriedAsLocalFilePaths() {
        String dir = "/data/user/0/com.facebook.orca/cache/media/";
        Message message = Message.with(Attachment.image(ImageData.of(null,
            AttachmentImageMap.of(
                new ImageUrl(240, 180, dir + "photo-s.jpg"),
                new ImageUrl(480, 640, dir + "photo-m.jpg"),
                new ImageUrl(720, 960, dir + "photo-l.jpg"),
                new ImageUrl(1440, 1920, dir + "photo-xl.jpg")))));
        MessageLogAttachment.VaultRead read = MessageLogAttachment.inspectForVault(message);
        assertFalse(read.ephemeral);
        assertNotNull(read.found);
        assertEquals(MessageLogAttachment.PHOTO, read.found.kind);
        assertEquals(dir + "photo-xl.jpg", read.found.address());
        assertEquals(4, read.found.addresses.length);
        assertTrue(read.diag, read.diag.contains("maps=n,4"));
        assertTrue(read.diag, read.diag.contains("asch=path"));
        assertFalse(read.diag, read.diag.contains("photo-xl.jpg"));
        assertNotNull(MessageLogAttachment.foundForVault(message));
        assertNull(MessageLogAttachment.found(message));
    }

    /**
     * A photo whose biggest entry is a reference only Messenger's secure parser can resolve still gives the
     * vault its usable copies: local files first, then web addresses, biggest first within each form. The
     * unusable form is named in the fingerprint, never fetched.
     */
    @Test public void theVaultOrdersLocalBeforeWebAndSkipsUnusableForms() {
        Message message = Message.with(Attachment.image(ImageData.of(
            AttachmentImageMap.of(new ImageUrl(240, 180, SMALL)),
            AttachmentImageMap.of(
                new ImageUrl(1440, 1920, "secureref:9f86d081884c7d659a2feaa0c55ad015"),
                new ImageUrl(720, 960, BIG),
                new ImageUrl(480, 640, "/data/user/0/com.facebook.orca/cache/media/photo-m.jpg")))));
        MessageLogAttachment.VaultRead read = MessageLogAttachment.inspectForVault(message);
        assertNotNull(read.found);
        assertEquals(3, read.found.addresses.length);
        assertEquals("/data/user/0/com.facebook.orca/cache/media/photo-m.jpg", read.found.addresses[0]);
        assertEquals(BIG, read.found.addresses[1]);
        assertEquals(SMALL, read.found.addresses[2]);
        assertTrue(read.diag, read.diag.contains("asch=https+other+path"));
        assertFalse(read.diag, read.diag.contains("secureref"));
    }

    /** Entries whose address string never arrived are counted in the fingerprint, and give the vault nothing. */
    @Test public void theVaultCountsPhotoEntriesWithoutAnAddress() {
        Message message = Message.with(Attachment.image(ImageData.of(null,
            AttachmentImageMap.of(new ImageUrl(240, 180, null), new ImageUrl(480, 640, "")))));
        MessageLogAttachment.VaultRead read = MessageLogAttachment.inspectForVault(message);
        assertNull(read.found);
        assertEquals(MessageLogAttachment.PHOTO, read.shapeKind);
        assertTrue(read.diag, read.diag.contains("maps=n,2"));
        assertTrue(read.diag, read.diag.contains("anull=2"));
        assertTrue(read.diag, read.diag.contains("asch=-"));
    }

    /** A video whose playable address is a file on the phone is the vault's, ahead of its web thumbnail. */
    @Test public void theVaultTakesAVideoCarriedAsAFileOnThePhone() {
        Message message = Message.with(Attachment.video(new VideoData(
            Uri.parse("file:///data/user/0/com.facebook.orca/cache/media/clip.mp4"),
            Uri.parse(THUMB))));
        MessageLogAttachment.Found found = MessageLogAttachment.foundForVault(message);
        assertNotNull(found);
        assertEquals(MessageLogAttachment.VIDEO, found.kind);
        assertEquals("file:///data/user/0/com.facebook.orca/cache/media/clip.mp4", found.addresses[0]);
    }

    /** A voice clip carried as a bare path — the decrypted file in Messenger's cache — is the vault's too. */
    @Test public void theVaultTakesAVoiceClipCarriedAsABarePath() {
        Message message = Message.with(Attachment.audio(
            new AudioData(Uri.parse("/data/user/0/com.facebook.orca/cache/media/voice.mp4"))));
        MessageLogAttachment.Found found = MessageLogAttachment.foundForVault(message);
        assertNotNull(found);
        assertEquals(MessageLogAttachment.AUDIO, found.kind);
        assertEquals("/data/user/0/com.facebook.orca/cache/media/voice.mp4", found.address());
    }

    /**
     * A sticker is a photo-shaped attachment whose declared type says sticker. The vault files it as one —
     * the device acceptance showed stickers filed as photos because only the data objects were read.
     */
    @Test public void aStickerDeclaresItselfByType() {
        Message message = Message.with(com.facebook.messaging.model.attachment.Attachment.typed(
            com.facebook.messaging.model.attachment.AttachmentType.STICKER,
            ImageData.of(null, AttachmentImageMap.of(new ImageUrl(1440, 1920, BIG)))));
        MessageLogAttachment.VaultRead read = MessageLogAttachment.inspectForVault(message);
        assertEquals(MessageLogAttachment.STICKER, read.shapeKind);
        assertNotNull(read.found);
        assertEquals(BIG, read.found.address());
        assertTrue(read.diag, read.diag.contains("typ=STICKER"));
    }

    /** An animated image declares itself by type, and a plain image type is overruled by a GIF format map. */
    @Test public void anAnimatedImageIsFiledAsAGif() {
        Message typed = Message.with(com.facebook.messaging.model.attachment.Attachment.typed(
            com.facebook.messaging.model.attachment.AttachmentType.ANIMATED_IMAGE,
            ImageData.of(null, AttachmentImageMap.of(new ImageUrl(480, 480, BIG)))));
        assertEquals(MessageLogAttachment.GIF, MessageLogAttachment.inspectForVault(typed).shapeKind);
        Message mapped = Message.with(com.facebook.messaging.model.attachment.Attachment.typed(
            com.facebook.messaging.model.attachment.AttachmentType.IMAGE,
            ImageData.of(null, AttachmentImageMap.gif(new ImageUrl(480, 480, BIG)))));
        assertEquals(MessageLogAttachment.GIF, MessageLogAttachment.inspectForVault(mapped).shapeKind);
    }

    /**
     * A voice clip in an encrypted chat arrives with its type declared and no audio data at all — the
     * device acceptance showed it filed as a text message. The type alone files it as audio.
     */
    @Test public void aVoiceClipWithNoAudioDataIsFiledByItsType() {
        Message message = Message.with(com.facebook.messaging.model.attachment.Attachment.typedOnly(
            com.facebook.messaging.model.attachment.AttachmentType.AUDIO));
        MessageLogAttachment.VaultRead read = MessageLogAttachment.inspectForVault(message);
        assertEquals(MessageLogAttachment.AUDIO, read.shapeKind);
        assertNull(read.found);
        assertFalse(read.ephemeral);
        assertTrue(read.diag, read.diag.contains("typ=AUDIO"));
    }

    /** A declared view-once type is refused on the type alone, whatever photo data rides under it. */
    @Test public void aViewOnceTypeIsRefusedEvenWithPhotoData() {
        Message message = Message.with(com.facebook.messaging.model.attachment.Attachment.typed(
            com.facebook.messaging.model.attachment.AttachmentType.EPHEMERAL_IMAGE,
            ImageData.of(null, AttachmentImageMap.of(new ImageUrl(1440, 1920, BIG)))));
        assertTrue(MessageLogAttachment.inspectForVault(message).ephemeral);
    }

    /**
     * What Messenger's resolver produced for a photo: the vault takes the addresses it can open — the file
     * already on the phone first, then https, then http — and names every scheme it saw, including the
     * internal reference it cannot open, without ever treating that reference as an address.
     */
    @Test public void resolvedPhotoDataYieldsTheResolversAddresses() {
        com.facebook.messaging.attachments.ImageAttachmentData data =
            com.facebook.messaging.attachments.ImageAttachmentData.of(
                com.facebook.messaging.attachments.ImageAttachmentUris.of(
                    Uri.parse("fbid:98765"), Uri.parse(BIG),
                    Uri.parse("http://cdn.example.com/photo-m.jpg"),
                    Uri.parse("/data/user/0/com.facebook.orca/cache/media/photo.jpg")), null);
        MessageLogAttachment.Resolved resolved = MessageLogAttachment.resolved("photo", data);
        assertNotNull(resolved.found);
        assertEquals(MessageLogAttachment.PHOTO, resolved.found.kind);
        assertEquals(List.of("/data/user/0/com.facebook.orca/cache/media/photo.jpg", BIG,
            "http://cdn.example.com/photo-m.jpg"), List.of(resolved.found.addresses));
        assertEquals("rsch=fbid+http+https+path", resolved.diag);
    }

    /** A photo resolution hands over a list; every photo in it contributes its addresses. */
    @Test public void resolvedPhotoListCombinesEveryPhoto() {
        com.facebook.messaging.attachments.ImageAttachmentData one =
            com.facebook.messaging.attachments.ImageAttachmentData.of(
                com.facebook.messaging.attachments.ImageAttachmentUris.of(Uri.parse(SMALL)), null);
        com.facebook.messaging.attachments.ImageAttachmentData two =
            com.facebook.messaging.attachments.ImageAttachmentData.of(
                com.facebook.messaging.attachments.ImageAttachmentUris.of(Uri.parse(BIG)), null);
        MessageLogAttachment.Resolved resolved =
            MessageLogAttachment.resolved("photo", List.of(one, two));
        assertNotNull(resolved.found);
        assertEquals(List.of(SMALL, BIG), List.of(resolved.found.addresses));
    }

    /** The resolver's video and audio data yield their playable addresses, the resource's included. */
    @Test public void resolvedVideoAndAudioDataYieldTheirAddresses() {
        com.facebook.messaging.attachments.VideoAttachmentData video =
            com.facebook.messaging.attachments.VideoAttachmentData.of(Uri.parse(MOVIE),
                com.facebook.ui.media.attachments.model.MediaResource.of(
                    com.facebook.ui.media.attachments.model.MediaResource.Kind.ENCRYPTED_VIDEO,
                    Uri.parse("https://cdn.example.com/enc.mp4")));
        MessageLogAttachment.Resolved resolvedVideo = MessageLogAttachment.resolved("video", video);
        assertNotNull(resolvedVideo.found);
        assertEquals(MessageLogAttachment.VIDEO, resolvedVideo.found.kind);
        assertEquals(List.of(MOVIE, "https://cdn.example.com/enc.mp4"),
            List.of(resolvedVideo.found.addresses));
        com.facebook.messaging.attachments.AudioAttachmentData audio =
            com.facebook.messaging.attachments.AudioAttachmentData.of(Uri.parse(ADDRESS));
        MessageLogAttachment.Resolved resolvedAudio = MessageLogAttachment.resolved("audio", audio);
        assertNotNull(resolvedAudio.found);
        assertEquals(MessageLogAttachment.AUDIO, resolvedAudio.found.kind);
        assertEquals(ADDRESS, resolvedAudio.found.address());
    }

    /** A resource that declares itself view-once is never read out of resolved data. */
    @Test public void resolvedSkipsAnEphemeralResource() {
        com.facebook.messaging.attachments.ImageAttachmentData data =
            com.facebook.messaging.attachments.ImageAttachmentData.of(null,
                com.facebook.ui.media.attachments.model.MediaResource.of(
                    com.facebook.ui.media.attachments.model.MediaResource.Kind.EPHEMERAL_PHOTO,
                    Uri.parse(BIG)));
        MessageLogAttachment.Resolved resolved = MessageLogAttachment.resolved("photo", data);
        assertNull(resolved.found);
        assertEquals("rsch=-", resolved.diag);
    }

    /** An internal reference alone is named by its scheme and never taken as an address. */
    @Test public void resolvedNamesAnUnopenableSchemeWithoutTakingIt() {
        com.facebook.messaging.attachments.ImageAttachmentData data =
            com.facebook.messaging.attachments.ImageAttachmentData.of(
                com.facebook.messaging.attachments.ImageAttachmentUris.of(Uri.parse("fbid:98765")), null);
        MessageLogAttachment.Resolved resolved = MessageLogAttachment.resolved("photo", data);
        assertNull(resolved.found);
        assertEquals("rsch=fbid", resolved.diag);
        assertNull(MessageLogAttachment.resolved("photo", null).found);
    }

    /** Clear takes the files with the messages, and Undo puts both back. */
    @Test public void clearAndUndoTakeTheClipAlong() throws Exception {
        MessageLog.storeNow(null, THREAD, ID);
        assertEquals(nameOf(MessageLogAttachment.AUDIO), MessageLogAttachment.keepNow(THREAD, ID, audio()));
        MessageLog.Cleared cleared = MessageLog.clearForUndo();
        assertTrue(cleared.cleared);
        assertTrue(MessageLog.entries().isEmpty());
        assertTrue(MessageLog.restore(cleared));
        MessageLog.Entry back = MessageLog.entries().get(0);
        assertEquals(MessageLogAttachment.AUDIO, back.mediaType);
        assertArrayEquals(CLIP, MessageLogMedia.open(Settings.appContext, back.media));
    }
}
