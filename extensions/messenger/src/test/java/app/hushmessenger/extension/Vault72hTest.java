package app.hushmessenger.extension;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * The Temporary Vault's store, with software keys in place of the keystore (Robolectric has none), as the
 * message log's tests do. Captures are written through the same synchronous entry points the hooks queue to,
 * so what is checked here is the store's own behaviour: merging, edits, recalls, the 72-hour expiry, and the
 * media fetch's honesty about what it managed to keep.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class Vault72hTest {
    private static final long T0 = 1_700_000_000_000L;
    private Context context;
    private SecretKey mediaKey;
    private java.util.function.Function<String, InputStream> opener;

    @Before public void reset() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Settings.initialize(context);
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        Settings.hookErrors.clear();
        Vault72h.resetForTests();
        context.deleteDatabase(Vault72h.DB_NAME);
        deleteMedia();
        Vault72h.vault = new MessageLogTest.SoftwareVault();
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        mediaKey = generator.generateKey();
        Vault72h.mediaKeys = new Vault72h.MediaKeys() {
            @Override public SecretKey key() { return mediaKey; }
            @Override public void forget() { }
        };
        opener = VaultMedia.opener;
        Settings.preferences.edit().putBoolean(Vault72h.KEY, true).commit();
    }

    @After public void tidy() {
        VaultMedia.opener = opener;
        Vault72h.vault = null;
        Vault72h.mediaKeys = null;
        Vault72h.resetForTests();
        context.deleteDatabase(Vault72h.DB_NAME);
        deleteMedia();
    }

    private void deleteMedia() {
        File dir = new File(context.getFilesDir(), Vault72h.MEDIA_DIR);
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) file.delete();
        dir.delete();
    }

    private long capture(String text, String thread, String id, long now) {
        return Vault72h.recordNow(text, thread, id, Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "", "", now);
    }

    private List<Vault72h.Entry> all(long now) {
        return Vault72h.entries(Vault72h.Filter.ALL, null, null, now);
    }

    @Test public void aTextCaptureIsStoredSealedAndReadable() {
        long rowId = Vault72h.recordNow("xin chào, mai gặp nhé", "thread-1", "m1",
            Vault72h.SOURCE_NOTIFICATION, null, T0 - 1000, 42, "An", "Nhóm lớp", T0);
        assertTrue(rowId > 0);
        List<Vault72h.Entry> entries = all(T0);
        assertEquals(1, entries.size());
        Vault72h.Entry entry = entries.get(0);
        assertEquals("xin chào, mai gặp nhé", entry.text);
        assertEquals("An", entry.sender);
        assertEquals("Nhóm lớp", entry.chat);
        assertEquals(42, entry.senderId);
        assertEquals(Vault72h.KIND_TEXT, entry.kind);
        assertEquals(Vault72h.STATUS_ACTIVE, entry.status);
        assertEquals(T0, entry.receivedAt);
        assertEquals(T0 + Vault72h.TTL_MS, entry.expiresAt);
        assertEquals(Vault72h.MEDIA_NONE, entry.mediaStatus);
        // The words are not sitting in the database in the clear.
        File db = context.getDatabasePath(Vault72h.DB_NAME);
        assertTrue(db.isFile());
    }

    @Test public void theSameMessageFromTwoSurfacesMergesIntoOneEntry() {
        capture("nội dung", "thread-1", "m2", T0);
        long rowId = Vault72h.recordRowNow("nội dung", "m2", "thread-1", T0 - 500, 7, 0, 0, T0 + 60_000);
        assertTrue(rowId > 0);
        List<Vault72h.Entry> entries = all(T0 + 60_000);
        assertEquals(1, entries.size());
        assertEquals(T0, entries.get(0).receivedAt);
        assertEquals(T0 + Vault72h.TTL_MS, entries.get(0).expiresAt);
    }

    /**
     * The two surfaces don't always hold the same key for one message: the notification capture has the
     * message's own id, a chat row may have only its threading id. The words still have to land on one entry.
     */
    @Test public void aRowKeyedOnlyByThreadingIdMergesWithTheNotificationCapture() {
        Vault72h.recordNow("mmb", "thread-1", "m-real", Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "An", "", T0);
        long rowId = Vault72h.recordRowNow("mmb", "", "tid-9", T0 - 500, 7, 0, 0, T0 + 30_000);
        assertTrue(rowId > 0);
        List<Vault72h.Entry> entries = all(T0 + 30_000);
        assertEquals(1, entries.size());
        assertEquals("An", entries.get(0).sender);
        assertEquals("m-real", entries.get(0).msgId);
    }

    /**
     * The same agreement the other way round: the row writes first, with no name and a threading key, and the
     * notification capture that follows has to find that entry, give it the sender's name and the message's
     * own id — the id a recall will name.
     */
    @Test public void aNotificationCaptureUpgradesARowEntrysKeyAndName() {
        assertTrue(Vault72h.recordRowNow("mmb", "", "tid-9", T0, 7, 0, 0, T0) > 0);
        Vault72h.recordNow("mmb", "thread-1", "m-real", Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "An", "", T0 + 20_000);
        List<Vault72h.Entry> entries = all(T0 + 20_000);
        assertEquals(1, entries.size());
        assertEquals("An", entries.get(0).sender);
        assertEquals("m-real", entries.get(0).msgId);
        assertTrue(Vault72h.markRecalledNow("m-real"));
        assertTrue(all(T0 + 20_000).get(0).recalled());
    }

    /**
     * The encrypted-chat shape the device acceptance showed twice over: one message, two entries — the
     * notification's, with the sender's name, and the chat row's, with a different id for the same message
     * and no name at all. The two spellings of the id differ, so identity has to come from the words, the
     * send time and the sender agreeing; the entry keeps the first id it was filed under.
     */
    @Test public void theSameMessageUnderTwoDifferentIdsMergesOnce() {
        Vault72h.recordNow("👍", "thread-1", "msys-1", Vault72h.SOURCE_NOTIFICATION, null, 0, 0,
            "Nguyễn Mạnh Tuấn", "", T0);
        long rowId = Vault72h.recordRowNow("👍", "row-9", "tid-9", T0 + 4_000, 7, 0, 0, T0 + 30_000);
        assertTrue(rowId > 0);
        List<Vault72h.Entry> entries = all(T0 + 30_000);
        assertEquals(1, entries.size());
        assertEquals("msys-1", entries.get(0).msgId);
        assertEquals("Nguyễn Mạnh Tuấn", entries.get(0).sender);
    }

    /** The same words sent twice are two messages: five minutes apart, the send times refuse the merge. */
    @Test public void theSameWordsSentTwiceStayTwoEntries() {
        capture("👍", "thread-1", "first", T0);
        capture("👍", "thread-1", "second", T0 + 5 * 60_000);
        assertEquals(2, all(T0 + 5 * 60_000).size());
    }

    /**
     * An entry written before its sender's name could be read holds an empty name, and a later capture of
     * the same message has to fill it — the fill once waited for a missing value that never occurs, so a
     * name arriving late never landed.
     */
    @Test public void aNameArrivingLaterFillsAnEntryThatHadNone() {
        Vault72h.recordNow("chào", "thread-1", "x1", Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "", "", T0);
        Vault72h.recordNow("chào", "thread-1", "x1", Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "Bình", "",
            T0 + 1_000);
        List<Vault72h.Entry> entries = all(T0 + 1_000);
        assertEquals(1, entries.size());
        assertEquals("Bình", entries.get(0).sender);
    }

    /** A message that is only an emoji is filed as one; words with an emoji in them stay text. */
    @Test public void emojiOnlyMessagesAreFiledAsEmoji() {
        capture("👍", "thread-1", "e1", T0);
        capture("👍 đẹp quá", "thread-1", "e2", T0);
        List<Vault72h.Entry> entries = all(T0);
        assertEquals(2, entries.size());
        for (Vault72h.Entry entry : entries) {
            if ("e1".equals(entry.msgId)) assertEquals(Vault72h.KIND_EMOJI, entry.kind);
            if ("e2".equals(entry.msgId)) assertEquals(Vault72h.KIND_TEXT, entry.kind);
        }
        assertTrue(Vault72h.isEmojiOnly("👍🏽"));
        assertTrue(Vault72h.isEmojiOnly("😀 😀"));
        assertFalse(Vault72h.isEmojiOnly("a👍"));
        assertFalse(Vault72h.isEmojiOnly(""));
    }

    /** A sticker's scan shape refines the photo kind its data alone would have filed it under. */
    @Test public void aStickerShapeRefinesAPhotoCapture() {
        Vault72h.recordNow("Đã gửi một nhãn dán.", "thread-1", "stk-1", Vault72h.SOURCE_NOTIFICATION,
            new MessageLogAttachment.Found("https://cdn.example.com/s.png", MessageLogAttachment.PHOTO),
            0, 0, "An", "", T0, MessageLogAttachment.STICKER, "att=1 typ=STICKER");
        List<Vault72h.Entry> entries = all(T0);
        assertEquals(1, entries.size());
        assertEquals(Vault72h.KIND_STICKER, entries.get(0).kind);
    }

    /** A voice clip whose scan found no address is still filed as audio, with the reason on it. */
    @Test public void aVoiceShapeFilesACaptureAsAudio() {
        Vault72h.recordNow("Đã gửi tin nhắn âm thanh cho bạn", "thread-1", "vc-1",
            Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "An", "", T0,
            MessageLogAttachment.AUDIO, "att=1 typ=AUDIO");
        List<Vault72h.Entry> entries = all(T0);
        assertEquals(1, entries.size());
        assertEquals(Vault72h.KIND_AUDIO, entries.get(0).kind);
        assertEquals(Vault72h.MEDIA_NO_ADDRESS, entries.get(0).mediaStatus);
    }

    /** Two different messages that happen to share their words are two entries: each carries its own id. */
    @Test public void twoDifferentMessagesWithTheSameWordsStayTwoEntries() {
        capture("ok", "thread-1", "mA", T0);
        capture("ok", "thread-1", "mB", T0 + 5_000);
        assertEquals(2, all(T0 + 5_000).size());
    }

    @Test public void anIdlessCaptureMergesWithTheSameWordsInTheSameChat() {
        capture("trùng lặp", "thread-9", "", T0);
        capture("trùng lặp", "thread-9", "", T0 + 30_000);
        assertEquals(1, all(T0 + 30_000).size());
        capture("khác hẳn", "thread-9", "", T0 + 40_000);
        assertEquals(2, all(T0 + 40_000).size());
    }

    @Test public void differentWordsUnderOneIdAreAnEdit() {
        capture("bản gốc", "thread-1", "m3", T0);
        capture("bản đã sửa", "thread-1", "m3", T0 + 5_000);
        List<Vault72h.Entry> entries = all(T0 + 5_000);
        assertEquals(1, entries.size());
        assertEquals("bản đã sửa", entries.get(0).text);
        assertEquals("bản gốc", entries.get(0).editedFrom);
        assertEquals(Vault72h.STATUS_EDITED, entries.get(0).status);
    }

    @Test public void aRecallMarksTheEntryAndTheCopyStaysReadable() {
        capture("sẽ bị thu hồi", "thread-1", "m4", T0);
        assertTrue(Vault72h.markRecalledNow("m4"));
        List<Vault72h.Entry> entries = all(T0);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).recalled());
        assertEquals("sẽ bị thu hồi", entries.get(0).text);
        assertEquals(1, Vault72h.entries(Vault72h.Filter.RECALLED, null, null, T0).size());
    }

    @Test public void aRecallOfAMessageNeverCapturedCreatesNothing() {
        assertFalse(Vault72h.markRecalledNow("không-hề-có"));
        assertEquals(0, all(T0).size());
    }

    @Test public void anExpiredEntryIsInvisibleEvenBeforeAnyPurgeRuns() {
        capture("hết hạn", "thread-1", "m5", T0);
        assertEquals(1, all(T0 + Vault72h.TTL_MS - 1).size());
        assertEquals(0, all(T0 + Vault72h.TTL_MS).size());
        assertEquals(0, Vault72h.stats(T0 + Vault72h.TTL_MS).entries);
    }

    @Test public void aFreshRowInAnOpenChatIsAnArrival() {
        long rowId = Vault72h.recordRowNow("đang xem trực tiếp", "m6", "thread-2", T0, 9, 0, 0, T0 + 2_000);
        assertTrue(rowId > 0);
        List<Vault72h.Entry> entries = all(T0 + 2_000);
        assertEquals(1, entries.size());
        assertEquals(Vault72h.SOURCE_ROW, entries.get(0).source);
        assertEquals(T0 + 2_000, entries.get(0).receivedAt);
    }

    @Test public void anOldRowNeverCreatesAnEntryButFillsAnExistingOne() {
        long old = T0 - 11 * 60_000;
        assertEquals(-1, Vault72h.recordRowNow("tin cũ", "m7", "thread-2", old, 9, 0, 0, T0));
        assertEquals(0, all(T0).size());
        capture("tin cũ", "thread-2", "m7", T0 - 60_000);
        assertTrue(Vault72h.recordRowNow("tin cũ", "m7", "thread-2", old, 9, 0, 0, T0) > 0);
        assertEquals(1, all(T0).size());
        assertEquals(T0 - 60_000, all(T0).get(0).receivedAt);
    }

    @Test public void theUnsentPlaceholderRowIsNotACapture() {
        assertEquals(-1, Vault72h.recordRowNow("", "m8", "thread-2", T0, 9,
            Settings.UNSENT_PLACEHOLDER_TYPE, Settings.UNSENT_PLACEHOLDER_SUBTYPE, T0));
        assertEquals(0, all(T0).size());
    }

    @Test public void aViewOnceMessageIsNeverArchived() {
        MessageLogAttachment.Found ephemeral = new MessageLogAttachment.Found(new String[0],
            MessageLogAttachment.EPHEMERAL);
        assertEquals(-1, Vault72h.recordNow(null, "thread-1", "m9", Vault72h.SOURCE_NOTIFICATION,
            ephemeral, 0, 0, "", "", T0));
        assertEquals(0, all(T0).size());
    }

    @Test public void aPhotoIsFetchedSealedAndOpensBackToTheSameBytes() throws Exception {
        Settings.preferences.edit().putBoolean(Vault72h.KEEP_PHOTOS, true).commit();
        byte[] picture = new byte[8192];
        new SecureRandom().nextBytes(picture);
        VaultMedia.opener = address -> new ByteArrayInputStream(picture);
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(
            new String[] {"https://example.test/photo.jpg"}, MessageLogAttachment.PHOTO);
        long rowId = Vault72h.recordNow(null, "thread-1", "m10", Vault72h.SOURCE_NOTIFICATION,
            found, 0, 0, "", "", T0);
        assertTrue(rowId > 0);
        Vault72h.Entry entry = awaitMedia(T0);
        assertNotNull(entry);
        assertEquals(Vault72h.MEDIA_STORED, entry.mediaStatus);
        assertEquals(Vault72h.KIND_PHOTO, entry.kind);
        assertTrue(entry.hasMedia());
        assertArrayEquals(picture, Vault72h.openMedia(context, entry));
        // The sealed file on disk is not the picture itself.
        File sealed = new File(new File(context.getFilesDir(), Vault72h.MEDIA_DIR), entry.mediaName);
        assertTrue(sealed.isFile());
        assertFalse(java.util.Arrays.equals(picture, java.nio.file.Files.readAllBytes(sealed.toPath())));
    }

    @Test public void withTheKeepSwitchOffNoMediaIsFetched() {
        VaultMedia.opener = address -> { throw new AssertionError("must not fetch"); };
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(
            new String[] {"https://example.test/photo.jpg"}, MessageLogAttachment.PHOTO);
        assertTrue(Vault72h.recordNow(null, "thread-1", "m11", Vault72h.SOURCE_NOTIFICATION,
            found, 0, 0, "", "", T0) > 0);
        List<Vault72h.Entry> entries = all(T0);
        assertEquals(1, entries.size());
        assertEquals(Vault72h.MEDIA_NOT_KEPT, entries.get(0).mediaStatus);
    }

    @Test public void aFailedFetchKeepsTheEntryAndSaysSo() throws Exception {
        Settings.preferences.edit().putBoolean(Vault72h.KEEP_VIDEOS, true).commit();
        VaultMedia.opener = address -> { throw new RuntimeException("offline"); };
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(
            new String[] {"https://example.test/video.mp4"}, MessageLogAttachment.VIDEO);
        assertTrue(Vault72h.recordNow("kèm video", "thread-1", "m12", Vault72h.SOURCE_NOTIFICATION,
            found, 0, 0, "", "", T0) > 0);
        Vault72h.Entry entry = awaitMedia(T0);
        assertNotNull(entry);
        assertEquals(Vault72h.MEDIA_FAILED, entry.mediaStatus);
        assertEquals("kèm video", entry.text);
        assertFalse(entry.hasMedia());
    }

    @Test public void mediaWithNoFetchableAddressIsUnavailableNotStored() throws Exception {
        Settings.preferences.edit().putBoolean(Vault72h.KEEP_VOICE, true).commit();
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(new String[0],
            MessageLogAttachment.AUDIO);
        assertTrue(Vault72h.recordNow(null, "thread-1", "m13", Vault72h.SOURCE_NOTIFICATION,
            found, 0, 0, "", "", T0) > 0);
        Vault72h.Entry entry = awaitMedia(T0);
        assertNotNull(entry);
        assertEquals(Vault72h.MEDIA_UNAVAILABLE, entry.mediaStatus);
        assertEquals(Vault72h.KIND_AUDIO, entry.kind);
    }

    @Test public void purgeDeletesExpiredMediaFilesWithTheirEntries() throws Exception {
        Settings.preferences.edit().putBoolean(Vault72h.KEEP_PHOTOS, true).commit();
        VaultMedia.opener = address -> new ByteArrayInputStream(new byte[2048]);
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(
            new String[] {"https://example.test/p.jpg"}, MessageLogAttachment.PHOTO);
        Vault72h.recordNow(null, "thread-1", "m14", Vault72h.SOURCE_NOTIFICATION, found, 0, 0, "", "", T0);
        Vault72h.Entry entry = awaitMedia(T0);
        assertNotNull(entry);
        File sealed = new File(new File(context.getFilesDir(), Vault72h.MEDIA_DIR), entry.mediaName);
        assertTrue(sealed.isFile());
        // Every capture purges first, in its own time: a capture past this entry's expiry removes row and file.
        long later = T0 + Vault72h.TTL_MS + 1;
        capture("mới", "thread-1", "m15", later);
        assertFalse(sealed.isFile());
        List<Vault72h.Entry> entries = all(later);
        assertEquals(1, entries.size());
        assertEquals("m15", entries.get(0).msgId);
    }

    @Test public void clearAllEmptiesEntriesAndMediaAtOnce() throws Exception {
        Settings.preferences.edit().putBoolean(Vault72h.KEEP_PHOTOS, true).commit();
        VaultMedia.opener = address -> new ByteArrayInputStream(new byte[1024]);
        capture("một", "thread-1", "m16", T0);
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(
            new String[] {"https://example.test/p.jpg"}, MessageLogAttachment.PHOTO);
        Vault72h.recordNow(null, "thread-1", "m17", Vault72h.SOURCE_NOTIFICATION, found, 0, 0, "", "", T0);
        // Await m17 itself, not whichever entry sorts first: m16 shares its timestamp, and awaiting the
        // text entry would let clearAll race the still-running fetch.
        Vault72h.Entry media = null;
        for (int i = 0; i < 200 && media == null; i++) {
            for (Vault72h.Entry entry : all(T0)) {
                if ("m17".equals(entry.msgId) && !Vault72h.MEDIA_PENDING.equals(entry.mediaStatus)) media = entry;
            }
            if (media == null) Thread.sleep(25);
        }
        assertNotNull(media);
        assertTrue(Vault72h.clearAll());
        assertEquals(0, all(T0).size());
        assertEquals(0, Vault72h.stats(T0).entries);
        File dir = new File(context.getFilesDir(), Vault72h.MEDIA_DIR);
        File[] left = dir.listFiles();
        assertTrue(left == null || left.length == 0);
    }

    @Test public void statsAndConversationsReflectWhatIsHeld() {
        capture("a", "thread-1", "m18", T0);
        capture("b", "thread-1", "m19", T0);
        capture("c", "thread-2", "m20", T0);
        Vault72h.markRecalledNow("m19");
        Vault72h.Stats stats = Vault72h.stats(T0);
        assertEquals(3, stats.entries);
        assertEquals(1, stats.recalled);
        assertEquals(T0 + Vault72h.TTL_MS, stats.nextExpiryAt);
        List<String[]> chats = Vault72h.conversations(T0);
        assertEquals(2, chats.size());
    }

    @Test public void searchFindsWordsSendersAndChats() {
        Vault72h.recordNow("hẹn ở quán cà phê", "thread-1", "m21", Vault72h.SOURCE_NOTIFICATION,
            null, 0, 0, "Bình", "", T0);
        assertEquals(1, Vault72h.entries(Vault72h.Filter.ALL, null, "cà phê", T0).size());
        assertEquals(1, Vault72h.entries(Vault72h.Filter.ALL, null, "Bình", T0).size());
        assertEquals(0, Vault72h.entries(Vault72h.Filter.ALL, null, "không có", T0).size());
        assertEquals(1, Vault72h.entries(Vault72h.Filter.ALL, "thread-1", null, T0).size());
        assertEquals(0, Vault72h.entries(Vault72h.Filter.ALL, "thread-2", null, T0).size());
    }

    /**
     * Regression: the Vault's Open/Clear actions and its three keep switches are added under the Vault's
     * settings row only when the row gets the sub-row wrapper in buildControls; the wrapper's key list once
     * omitted the Vault, so on device the switch showed with nothing under it. The built page must contain
     * both action buttons and all three switches, inside the Vault row's own wrapper.
     */
    @Test public void vaultActionsAndKeepSwitchesRenderUnderTheVaultRow() {
        OpenSections.all();
        try (var screen = org.robolectric.Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            Switch vaultSwitch = root.findViewWithTag(Vault72h.KEY);
            assertNotNull("the Vault's own switch", vaultSwitch);
            View wrapper = (View) ((View) vaultSwitch.getParent()).getParent();
            Button view = root.findViewWithTag("vault_view");
            Button clear = root.findViewWithTag("vault_clear");
            assertNotNull("the Open Vault action renders", view);
            assertNotNull("the Clear Vault action renders", clear);
            assertSame("Open Vault sits in the Vault row's wrapper", view, wrapper.findViewWithTag("vault_view"));
            assertSame("Clear Vault sits in the Vault row's wrapper", clear, wrapper.findViewWithTag("vault_clear"));
            for (String keep : new String[] {Vault72h.KEEP_PHOTOS, Vault72h.KEEP_VIDEOS, Vault72h.KEEP_VOICE}) {
                Switch keepSwitch = root.findViewWithTag(keep);
                assertNotNull("the keep switch renders: " + keep, keepSwitch);
                assertSame("the keep switch sits in the Vault row's wrapper: " + keep,
                    keepSwitch, wrapper.findViewWithTag(keep));
            }
        }
    }

    /**
     * A media-shaped message whose address never arrived — the shape a photo takes when the message the hook
     * read carried no filled-in sizes — is an attachment entry that says it has no downloadable copy, not a
     * plain entry with nothing where the file should be.
     */
    @Test public void anAttachmentThatArrivesWithNoAddressSaysSo() {
        assertTrue(Vault72h.recordNow(null, "thread-1", "m20", Vault72h.SOURCE_NOTIFICATION,
            null, 0, 0, "", "", T0) > 0);
        List<Vault72h.Entry> entries = all(T0);
        assertEquals(1, entries.size());
        assertEquals(Vault72h.KIND_ATTACHMENT, entries.get(0).kind);
        assertEquals(Vault72h.MEDIA_NO_ADDRESS, entries.get(0).mediaStatus);
        assertFalse(entries.get(0).hasMedia());
    }

    /** The vault's own screen shows that reason on the entry, in words. */
    @Test public void theVaultSheetShowsWhyAnAttachmentHasNoFile() throws Exception {
        long now = System.currentTimeMillis();
        Vault72h.recordNow(null, "thread-1", "m21", Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "", "", now);
        try (var screen = org.robolectric.Robolectric.buildActivity(SettingsActivity.class).setup()) {
            new VaultSheet(screen.get()).showVault();
            Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            View root = dialog.getWindow().getDecorView();
            // The sheet reads the vault on its own thread and renders back on the main one.
            boolean shown = false;
            for (int i = 0; i < 100 && !shown; i++) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper();
                shown = hasText(root, "No downloadable copy came with the message");
                if (!shown) Thread.sleep(50);
            }
            assertTrue("the entry says no downloadable copy came with the message", shown);
            dialog.dismiss();
        }
    }

    private static boolean hasText(View view, String wanted) {
        if (view instanceof TextView && wanted.equals(String.valueOf(((TextView) view).getText()))) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), wanted)) return true;
            }
        }
        return false;
    }

    /**
     * A photo whose message carried no address — the encrypted-chat shape — is still filed as a photo, with
     * the no-address state and the scan's fingerprint on it, and it shows under the media filter instead of
     * passing for a text message.
     */
    @Test public void aPhotoShapedCaptureIsFiledAsAPhotoWithItsReason() {
        long rowId = Vault72h.recordNow("Đã gửi một ảnh cho bạn", "thread-1", "m29",
            Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "", "", T0,
            MessageLogAttachment.PHOTO, "att=1 img=1 maps=0,0");
        assertTrue(rowId > 0);
        List<Vault72h.Entry> entries = all(T0);
        assertEquals(1, entries.size());
        Vault72h.Entry entry = entries.get(0);
        assertEquals(Vault72h.KIND_PHOTO, entry.kind);
        assertEquals(Vault72h.MEDIA_NO_ADDRESS, entry.mediaStatus);
        assertEquals("att=1 img=1 maps=0,0", entry.diag);
        assertEquals(1, Vault72h.entries(Vault72h.Filter.MEDIA, null, "", T0).size());
    }

    /**
     * Media resolved later, when Messenger's thread view resolves the message, attaches to the entry the
     * arrival archived and is fetched and sealed like any captured media.
     */
    @Test public void resolvedMediaAttachesToTheEntryAndIsFetched() throws Exception {
        Settings.preferences.edit().putBoolean(Vault72h.KEEP_PHOTOS, true).commit();
        assertTrue(Vault72h.recordNow("Đã gửi một ảnh cho bạn", "thread-1", "m30",
            Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "", "", T0,
            MessageLogAttachment.PHOTO, "att=1 img=1 maps=0,0") > 0);
        byte[] picture = new byte[4096];
        new SecureRandom().nextBytes(picture);
        File local = new File(context.getCacheDir(), "resolved-photo.jpg");
        java.nio.file.Files.write(local.toPath(), picture);
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(
            new String[] {local.toURI().toString()}, MessageLogAttachment.PHOTO);
        assertTrue(Vault72h.attachFoundNow("m30", found, "att=1 mr=1 uris=file", T0));
        Vault72h.Entry entry = awaitMedia(T0);
        assertNotNull(entry);
        assertEquals(Vault72h.MEDIA_STORED, entry.mediaStatus);
        assertEquals(Vault72h.KIND_PHOTO, entry.kind);
        assertArrayEquals(picture, Vault72h.openMedia(context, entry));
    }

    /**
     * A message can carry several addresses for one file and any of them can be dead. The fetch walks them
     * all: the entry is stored from the address that answers, not given up on at the first failure.
     */
    @Test public void aDeadAddressDoesNotDecideTheEntry() throws Exception {
        Settings.preferences.edit().putBoolean(Vault72h.KEEP_PHOTOS, true).commit();
        assertTrue(Vault72h.recordNow("Đã gửi một ảnh cho bạn", "thread-1", "m33",
            Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "", "", T0,
            MessageLogAttachment.PHOTO, "att=1 img=1 maps=n,4 asch=https+path") > 0);
        byte[] picture = new byte[2048];
        new SecureRandom().nextBytes(picture);
        VaultMedia.opener = address -> address.contains("dead")
            ? null : new ByteArrayInputStream(picture);
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(new String[] {
            "https://cdn.example.com/dead.jpg?oe=6A1B2C3D",
            "https://cdn.example.com/alive.jpg?oe=6A1B2C3D"}, MessageLogAttachment.PHOTO);
        assertTrue(Vault72h.attachFoundNow("m33", found, "", T0));
        Vault72h.Entry entry = awaitMedia(T0);
        assertNotNull(entry);
        assertEquals(Vault72h.MEDIA_STORED, entry.mediaStatus);
        assertArrayEquals(picture, Vault72h.openMedia(context, entry));
    }

    /**
     * A resolution delivery can name its message differently from the entry the arrival archived — the
     * surfaces spell one message's id in their own ways. With exactly one recent entry of the delivery's
     * kind still waiting for its media, the delivery is that entry's, under the entry's own id.
     */
    @Test public void resolvedMediaFindsItsEntryWhenTheDeliveryNamesItDifferently() throws Exception {
        Settings.preferences.edit().putBoolean(Vault72h.KEEP_PHOTOS, true).commit();
        assertTrue(Vault72h.recordNow("Đã gửi một ảnh cho bạn", "thread-1", "notif-spelling",
            Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "", "", T0,
            MessageLogAttachment.PHOTO, "att=1 typ=IMAGE img=1 maps=n,4 asch=msys") > 0);
        byte[] picture = new byte[2048];
        new SecureRandom().nextBytes(picture);
        File local = new File(context.getCacheDir(), "resolved-photo-2.jpg");
        java.nio.file.Files.write(local.toPath(), picture);
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(
            new String[] {local.toURI().toString()}, MessageLogAttachment.PHOTO);
        assertTrue(Vault72h.attachFoundNow("thread-spelling", found, "rsch=msys", T0));
        Vault72h.Entry entry = awaitMedia(T0);
        assertNotNull(entry);
        assertEquals("notif-spelling", entry.msgId);
        assertEquals(Vault72h.MEDIA_STORED, entry.mediaStatus);
        assertArrayEquals(picture, Vault72h.openMedia(context, entry));
    }

    /** Two recent entries could be a differently-named delivery's either one: it is filed under neither. */
    @Test public void resolvedMediaWithTwoCandidateEntriesIsDropped() {
        assertTrue(Vault72h.recordNow("Đã gửi một ảnh cho bạn", "thread-1", "photo-a",
            Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "", "", T0,
            MessageLogAttachment.PHOTO, "att=1 typ=IMAGE") > 0);
        assertTrue(Vault72h.recordNow("Đã gửi một ảnh cho bạn", "thread-1", "photo-b",
            Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "", "", T0,
            MessageLogAttachment.PHOTO, "att=1 typ=IMAGE") > 0);
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(
            new String[] {"https://example.test/photo.jpg"}, MessageLogAttachment.PHOTO);
        assertFalse(Vault72h.attachFoundNow("third-spelling", found, "", T0));
        for (Vault72h.Entry entry : all(T0)) {
            assertEquals(Vault72h.MEDIA_NO_ADDRESS, entry.mediaStatus);
        }
    }

    /** A delivery long after the fact no longer matches by kind alone: its window has closed. */
    @Test public void resolvedMediaForAStaleEntryIsDropped() {
        assertTrue(Vault72h.recordNow("Đã gửi một ảnh cho bạn", "thread-1", "photo-old",
            Vault72h.SOURCE_NOTIFICATION, null, 0, 0, "", "", T0,
            MessageLogAttachment.PHOTO, "att=1 typ=IMAGE") > 0);
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(
            new String[] {"https://example.test/photo.jpg"}, MessageLogAttachment.PHOTO);
        assertFalse(Vault72h.attachFoundNow("other-spelling", found, "", T0 + 20 * 60_000L));
    }

    /** Resolution for a message the vault never archived creates nothing. */
    @Test public void resolvedMediaForAnUnknownMessageCreatesNothing() {
        MessageLogAttachment.Found found = new MessageLogAttachment.Found(
            new String[] {"https://example.test/photo.jpg"}, MessageLogAttachment.PHOTO);
        assertFalse(Vault72h.attachFoundNow("never-archived", found, "", T0));
        assertEquals(0, all(T0).size());
    }

    /** Resolved view-once media is refused, and a fingerprint alone still lands on the entry it describes. */
    @Test public void resolvedViewOnceIsRefusedButAFingerprintStillLands() {
        assertTrue(Vault72h.recordNow("mmb", "thread-1", "m31", Vault72h.SOURCE_NOTIFICATION,
            null, 0, 0, "", "", T0) > 0);
        MessageLogAttachment.Found ephemeral = new MessageLogAttachment.Found(new String[0],
            MessageLogAttachment.EPHEMERAL);
        assertFalse(Vault72h.attachFoundNow("m31", ephemeral, "", T0));
        assertTrue(Vault72h.attachFoundNow("m31", null, "att=1 mr=1 uris=file", T0));
        Vault72h.Entry entry = all(T0).get(0);
        assertEquals("att=1 mr=1 uris=file", entry.diag);
        assertEquals(Vault72h.MEDIA_NONE, entry.mediaStatus);
    }

    /** An entry with nothing stored can't be exported to the gallery, and says so at once. */
    @Test public void anEntryWithoutMediaIsNotExportedToTheGallery() {
        assertTrue(Vault72h.recordNow("mmb", "thread-1", "m32", Vault72h.SOURCE_NOTIFICATION,
            null, 0, 0, "", "", T0) > 0);
        boolean[] saved = {true};
        Vault72h.exportToGallery(context, all(T0).get(0), done -> saved[0] = done);
        assertFalse(saved[0]);
    }

    /** Waits for the media thread to settle an entry's media status, and returns that entry. */
    private Vault72h.Entry awaitMedia(long now) throws Exception {
        for (int i = 0; i < 200; i++) {
            List<Vault72h.Entry> entries = all(now);
            if (!entries.isEmpty()) {
                Vault72h.Entry entry = entries.get(0);
                if (!Vault72h.MEDIA_PENDING.equals(entry.mediaStatus)) return entry;
            }
            Thread.sleep(25);
        }
        return null;
    }
}
