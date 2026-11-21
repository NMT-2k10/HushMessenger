package app.hushmessenger.extension;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
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
        assertEquals(Vault72h.MEDIA_NONE, entries.get(0).mediaStatus);
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
        assertNotNull(awaitMedia(T0));
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
