package app.hushmessenger.extension;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteFullException;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The Temporary Vault: a local, encrypted archive of the messages this phone receives, each kept for exactly
 * 72 hours from the moment it was first captured, so a message its sender unsends can still be read here until
 * its time runs out.
 *
 * <p>Captures arrive from the two surfaces HushMessenger already hooks for other controls: the new-message
 * notification constructor (a message arriving, in the background or not) and the chat rows' text getters (a
 * message shown in the chat the user is looking at, encrypted chats included). Neither surface is the Android
 * notification listener, and neither is the only one relied on. A recall reaches the vault through the same
 * unsent-recall hook the message log uses, and only ever marks an entry that is already here: a message that
 * was never captured gets no placeholder.
 *
 * <p>Storage is a SQLite database in Messenger's private files folder. Words, sender and chat names are sealed
 * with an AES-GCM key the Android keystore holds; media files are sealed as a GCM stream under their own key,
 * so a file streams to disk without being held in memory. Nothing is uploaded anywhere. Expiry is enforced on every read ({@code expires_at > now}) as well as by
 * a purge that runs at Messenger start, every hour, and whenever the vault is opened, so an expired copy can't
 * be opened even if the purge hasn't run yet.
 *
 * <p>View-once (ephemeral) media is never archived: the media scan in {@link MessageLogAttachment#foundForVault}
 * skips those attachments, and a message whose only attachment is ephemeral is not recorded at all.
 */
public final class Vault72h {
    private Vault72h() {}

    static final String KEY = "temporary_vault";
    /** Keep a photo at the size it was sent, fetched as the message is captured. */
    static final String KEEP_PHOTOS = "temporary_vault_keep_photos";
    /** Keep a video, fetched as the message is captured. */
    static final String KEEP_VIDEOS = "temporary_vault_keep_videos";
    /** Keep a voice message's clip, fetched as the message is captured. */
    static final String KEEP_VOICE = "temporary_vault_keep_voice";

    static final String DB_NAME = "hush_vault72h.db";
    static final String MEDIA_DIR = "hush_vault_media";
    static final String KEY_ALIAS = "hushmessenger_vault72h";
    static final String MEDIA_KEY_ALIAS = "hushmessenger_vault72h_media";

    /** How long every copy lives, from its first capture: 72 hours, no choices, no extensions. */
    static final long TTL_MS = 72L * 60 * 60 * 1000;
    /**
     * A chat row whose send time is older than this is history being scrolled, not a message arriving, so it is
     * only ever merged into an entry that already exists, never archived as if it had just arrived.
     */
    static final long ROW_ARRIVAL_WINDOW_MS = 10L * 60 * 1000;
    /** Two captures of one message with no id to join them pair up within this long, like the message log's. */
    static final long LOOSE_MERGE_WINDOW_MS = 2L * 60 * 1000;
    /** How far apart two captures' send times may be and still be the same message under two different ids. */
    static final long SAME_MESSAGE_WINDOW_MS = 90L * 1000;
    static final long PURGE_PERIOD_MS = 60L * 60 * 1000;
    static final long PURGE_DELAY_MS = 30_000;
    static final int MAX_TEXT_CHARS = MessageLog.MAX_TEXT_CHARS;
    static final int MAX_THREAD_CHARS = MessageLog.MAX_THREAD_CHARS;
    static final int MAX_ID_CHARS = MessageLog.MAX_ID_CHARS;
    static final int MAX_NAME_CHARS = MessageLog.MAX_NAME_CHARS;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final long MAX_MEDIA_BYTES = 128L * 1024 * 1024;
    static java.util.function.LongSupplier clock = System::currentTimeMillis;

    // Kinds an entry can be. TEXT is a message with words of its own.
    static final String KIND_TEXT = "TEXT";
    static final String KIND_EMOJI = "EMOJI";
    static final String KIND_PHOTO = "PHOTO";
    static final String KIND_VIDEO = "VIDEO";
    static final String KIND_AUDIO = "AUDIO";
    static final String KIND_STICKER = "STICKER";
    static final String KIND_GIF = "GIF";
    static final String KIND_FILE = "FILE";
    static final String KIND_ATTACHMENT = "ATTACHMENT";

    // What happened to an entry's media. STORED is only written once the sealed file is complete on disk.
    static final String MEDIA_NONE = "NONE";
    /** The message was media-shaped but carried no address the vault could fetch: said out loud, never silent. */
    static final String MEDIA_NO_ADDRESS = "NO_ADDRESS";
    /** An address arrived but the vault's keep switch for that kind was off, so nothing was fetched. */
    static final String MEDIA_NOT_KEPT = "NOT_KEPT";
    static final String MEDIA_PENDING = "PENDING";
    static final String MEDIA_STORED = "STORED";
    static final String MEDIA_UNAVAILABLE = "UNAVAILABLE";
    static final String MEDIA_FAILED = "FAILED";
    static final String MEDIA_STORAGE_FULL = "STORAGE_FULL";

    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_RECALLED = "RECALLED";
    static final String STATUS_EDITED = "EDITED";

    static final String SOURCE_NOTIFICATION = "NOTIFICATION";
    static final String SOURCE_ROW = "ROW";

    /** What the vault's screen can narrow the list to. */
    enum Filter { ALL, RECALLED, MEDIA, STICKERS_GIFS, AUDIO_FILES }

    /** One archived message, with its sensitive fields already unsealed. */
    public static final class Entry {
        public final long rowId;
        public final String msgId;
        public final String thread;
        public final String sender;
        public final String chat;
        public final long senderId;
        public final String kind;
        public final String text;
        /** The words before an edit, empty unless {@link #status} is EDITED and the earlier words were captured. */
        public final String editedFrom;
        public final long sentAt;
        public final long receivedAt;
        public final long expiresAt;
        public final String status;
        public final String source;
        public final String mediaName;
        public final String mediaKind;
        public final String mediaStatus;
        public final long mediaBytes;
        /**
         * A structural fingerprint of what the message object carried when it was scanned — counts, media
         * type names and address schemes only, never an address or a value. Shown on the entry while its
         * media is missing, so the reason is visible instead of guessed.
         */
        public final String diag;

        Entry(long rowId, String msgId, String thread, String sender, String chat, long senderId, String kind,
              String text, String editedFrom, long sentAt, long receivedAt, long expiresAt, String status,
              String source, String mediaName, String mediaKind, String mediaStatus, long mediaBytes,
              String diag) {
            this.rowId = rowId;
            this.msgId = msgId;
            this.thread = thread;
            this.sender = sender;
            this.chat = chat;
            this.senderId = senderId;
            this.kind = kind;
            this.text = text;
            this.editedFrom = editedFrom;
            this.sentAt = sentAt;
            this.receivedAt = receivedAt;
            this.expiresAt = expiresAt;
            this.status = status;
            this.source = source;
            this.mediaName = mediaName;
            this.mediaKind = mediaKind;
            this.mediaStatus = mediaStatus;
            this.mediaBytes = mediaBytes;
            this.diag = diag == null ? "" : diag;
        }

        public boolean recalled() { return STATUS_RECALLED.equals(status); }
        public boolean hasMedia() { return MEDIA_STORED.equals(mediaStatus) && !mediaName.isEmpty(); }
        /** Milliseconds of life left at [now]; never negative in a list, since expired entries are filtered out. */
        public long remainingMs(long now) { return Math.max(0, expiresAt - now); }
    }

    /** Totals for the vault's statistics row. Sizes are the sealed files' sizes on disk. */
    static final class Stats {
        final int entries;
        final int recalled;
        final int mediaStored;
        final long mediaBytes;
        final long databaseBytes;
        final long nextExpiryAt;
        Stats(int entries, int recalled, int mediaStored, long mediaBytes, long databaseBytes, long nextExpiryAt) {
            this.entries = entries;
            this.recalled = recalled;
            this.mediaStored = mediaStored;
            this.mediaBytes = mediaBytes;
            this.databaseBytes = databaseBytes;
            this.nextExpiryAt = nextExpiryAt;
        }
    }

    // --- Crypto ---------------------------------------------------------------------------------------------

    /** Seals the small fields (words, names). Tests swap in a software key, as the message log's tests do. */
    static volatile MessageLog.Vault vault;

    static MessageLog.Vault vault() {
        MessageLog.Vault current = vault;
        if (current == null) {
            synchronized (LOCK) {
                if (vault == null) vault = new MessageLog.KeystoreVault(KEY_ALIAS);
                current = vault;
            }
        }
        return current;
    }

    /** Gives the key media streams are sealed with, and forgets it. Tests swap in a software key. */
    interface MediaKeys {
        SecretKey key() throws Exception;
        void forget() throws Exception;
    }

    static volatile MediaKeys mediaKeys;

    static MediaKeys mediaKeys() {
        MediaKeys current = mediaKeys;
        if (current == null) {
            synchronized (LOCK) {
                if (mediaKeys == null) mediaKeys = new MediaKeys() {
                    @Override public SecretKey key() throws Exception { return keystoreKey(MEDIA_KEY_ALIAS); }
                    @Override public void forget() throws Exception { forgetKey(MEDIA_KEY_ALIAS); }
                };
                current = mediaKeys;
            }
        }
        return current;
    }

    private static SecretKey keystoreKey(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry entry = store.getEntry(alias, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }

    private static void forgetKey(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) store.deleteEntry(alias);
    }

    private static byte[] sealText(String value) throws Exception {
        if (value == null || value.isEmpty()) return null;
        return vault().seal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String openText(byte[] blob) {
        if (blob == null || blob.length == 0) return "";
        try {
            return new String(vault().open(blob), StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            // A field whose key is gone reads as empty; the entry itself still lists, with its times and status.
            return "";
        }
    }

    /** Streams [in] into [file], GCM-sealed: a 12-byte nonce, then the ciphertext. Returns the bytes sealed. */
    static long sealStream(InputStream in, File file) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, mediaKeys().key());
        byte[] iv = cipher.getIV();
        long written = 0;
        // The cipher is driven by hand rather than through a CipherOutputStream, whose close() would close the
        // file first and leave nothing to sync: the tag is written, the file synced, and only then closed.
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(iv);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                byte[] sealed = cipher.update(buffer, 0, read);
                if (sealed != null) out.write(sealed);
                written += read;
                if (written > MAX_MEDIA_BYTES) throw new IOException("media size limit");
            }
            byte[] tail = cipher.doFinal();
            if (tail != null) out.write(tail);
            out.flush();
            out.getFD().sync();
        }
        return written;
    }

    /** The plain bytes sealed in [file], streamed back out. Null when the file is gone. */
    static InputStream openStream(File file) throws Exception {
        if (file == null || !file.isFile()) return null;
        FileInputStream raw = new FileInputStream(file);
        byte[] iv = new byte[IV_BYTES];
        int got = 0;
        while (got < IV_BYTES) {
            int read = raw.read(iv, got, IV_BYTES - got);
            if (read < 0) { raw.close(); return null; }
            got += read;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, mediaKeys().key(), new GCMParameterSpec(TAG_BITS, iv));
        return new CipherInputStream(raw, cipher);
    }

    /** The whole media file for [entry], unsealed. For pictures and tests; video and audio play from a cache file. */
    static byte[] openMedia(Context context, Entry entry) {
        if (context == null || entry == null || !entry.hasMedia() || entry.expiresAt <= clock.getAsLong()) return null;
        File file = mediaFile(context, entry.mediaName);
        try (InputStream in = openStream(file)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (Exception unreadable) {
            Settings.hookFailedPrivately(KEY, "Can't open a vault media file", unreadable);
            return null;
        }
    }

    /**
     * Unseals [entry]'s media into the cache folder so a player can open it, and returns that file. The copy is a
     * plain file in the app's own cache, deleted by Android with the cache and by the next purge at the latest.
     */
    static File mediaCacheFile(Context context, Entry entry) {
        if (context == null || entry == null || !entry.hasMedia()) return null;
        VaultMediaFormat format = VaultMediaFormat.forMime(entry.mediaKind);
        String suffix = format == null ? ".bin" : format.suffix;
        File out = null;
        try (InputStream in = openStream(mediaFile(context, entry.mediaName))) {
            if (in == null) return null;
            out = File.createTempFile("vault_" + entry.mediaName + "_", suffix, context.getCacheDir());
            try (FileOutputStream sink = new FileOutputStream(out)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) sink.write(buffer, 0, read);
            }
            synchronized (LOCK) {
                if (!liveEntryLocked(db(context), entry.rowId) || !out.isFile() || out.length() == 0) {
                    out.delete();
                    return null;
                }
            }
            return out;
        } catch (Exception unreadable) {
            if (out != null) out.delete();
            Settings.hookFailedPrivately(KEY, "Can't prepare a vault media file to play", unreadable);
            return null;
        }
    }

    /** Told how a gallery export ended: the copy is in the phone's gallery, or it couldn't be put there. */
    interface GalleryResult { void done(boolean saved); }

    static boolean exportable(Entry entry) {
        return entry.hasMedia() && android.os.Build.VERSION.SDK_INT >= 29
            && (KIND_PHOTO.equals(entry.kind) || KIND_VIDEO.equals(entry.kind) || KIND_AUDIO.equals(entry.kind)
                || KIND_GIF.equals(entry.kind) || KIND_STICKER.equals(entry.kind));
    }

    /** Exports an authorized copy on the media worker. */
    static void exportToGallery(Context context, Entry entry, GalleryResult result) {
        if (result == null) return;
        if (context == null || entry == null || !exportable(entry)) {
            result.done(false);
            return;
        }
        try {
            MEDIA.execute(() -> result.done(saveToGallery(context, entry)));
        } catch (RuntimeException rejected) {
            result.done(false);
        }
    }

    static boolean saveToGallery(Context context, Entry entry) {
        if (!exportable(entry) || entry.expiresAt <= clock.getAsLong()) return false;
        android.content.ContentResolver resolver = context.getContentResolver();
        android.net.Uri item = null;
        try (InputStream in = openStream(mediaFile(context, entry.mediaName))) {
            if (in == null) return false;
            String declared = KIND_VIDEO.equals(entry.kind) ? MessageLogAttachment.VIDEO
                : KIND_AUDIO.equals(entry.kind) ? MessageLogAttachment.AUDIO : MessageLogAttachment.PHOTO;
            VaultMediaFormat.Payload payload = VaultMediaFormat.probe(in, declared);
            if (payload == null || !payload.format.accepts(declared)) return false;
            VaultMediaFormat format = payload.format;
            boolean video = format.mime.startsWith("video/");
            boolean audio = format.mime.startsWith("audio/");
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                "hush_vault_" + entry.mediaName.substring(0, Math.min(12, entry.mediaName.length())) + format.suffix);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, format.mime);
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                video ? "Movies/HushVault" : audio ? "Music/HushVault" : "Pictures/HushVault");
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            String volume = android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY;
            android.net.Uri collection = video ? android.provider.MediaStore.Video.Media.getContentUri(volume)
                : audio ? android.provider.MediaStore.Audio.Media.getContentUri(volume)
                : android.provider.MediaStore.Images.Media.getContentUri(volume);

            item = resolver.insert(collection, values);
            if (item == null) return false;
            try (OutputStream out = resolver.openOutputStream(item)) {
                if (out == null) throw new IOException("no gallery stream");
                byte[] bytes = new byte[16 * 1024];
                int read;
                while ((read = payload.bytes.read(bytes)) != -1) out.write(bytes, 0, read);
            }
            synchronized (LOCK) {
                if (!liveEntryLocked(db(context), entry.rowId)) throw new IOException("entry expired");
                ContentValues done = new ContentValues();
                done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(item, done, null, null) != 1) throw new IOException("export publish failed");
            }
            VaultDiag.media("export", "saved");
            return true;
        } catch (Exception failure) {
            if (item != null) {
                try { resolver.delete(item, null, null); } catch (Exception ignored) {}
            }
            VaultDiag.media("export", "failed");
            return false;
        }
    }

    static File mediaFile(Context context, String name) {
        if (context == null || name == null || !name.matches("[0-9a-f]{64}")) return null;
        return new File(new File(context.getFilesDir(), MEDIA_DIR), name);
    }

    // --- Database -------------------------------------------------------------------------------------------

    private static final Object LOCK = new Object();
    private static final java.util.Set<String> IN_FLIGHT = new java.util.HashSet<>();
    private static Helper helper;

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context.getApplicationContext(), DB_NAME, null, 2); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE entries ("
                + "row_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "dedup TEXT UNIQUE NOT NULL,"
                + "msg_id TEXT NOT NULL DEFAULT '',"
                + "thread TEXT NOT NULL DEFAULT '',"
                + "sender BLOB, chat BLOB, sender_id INTEGER NOT NULL DEFAULT 0,"
                + "kind TEXT NOT NULL,"
                + "body BLOB, edited_from BLOB,"
                + "sent_at INTEGER NOT NULL, received_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,"
                + "status TEXT NOT NULL DEFAULT 'ACTIVE', source TEXT NOT NULL DEFAULT '',"
                + "media_name TEXT NOT NULL DEFAULT '', media_kind TEXT NOT NULL DEFAULT '',"
                + "media_status TEXT NOT NULL DEFAULT 'NONE', media_bytes INTEGER NOT NULL DEFAULT 0,"
                + "diag TEXT NOT NULL DEFAULT '')");
            db.execSQL("CREATE INDEX idx_vault_expires ON entries(expires_at)");
            db.execSQL("CREATE INDEX idx_vault_msg ON entries(msg_id)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Version 2 adds the per-entry media fingerprint. The archive itself is never dropped.
            if (oldVersion < 2) db.execSQL("ALTER TABLE entries ADD COLUMN diag TEXT NOT NULL DEFAULT ''");
        }

        @Override public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            // Additive so older Vault builds can open the same schema if rolled back.
            db.execSQL("CREATE TABLE IF NOT EXISTS vault_video_identity ("
                + "row_id INTEGER PRIMARY KEY, poster_key TEXT NOT NULL, sent_at INTEGER NOT NULL,"
                + "notification_id TEXT NOT NULL DEFAULT '', playback_id TEXT NOT NULL DEFAULT '')");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_video_identity"
                + " ON vault_video_identity(poster_key,sent_at)");
            pruneVideoIdentities(db);
        }
    }

    private static SQLiteDatabase db(Context context) {
        synchronized (LOCK) {
            if (helper == null) {
                helper = new Helper(context);
                SQLiteDatabase opened = helper.getWritableDatabase();
                recoverMediaLocked(context, opened);
                VaultDiag.init("db=" + DB_NAME + " version=" + opened.getVersion());
                VaultDiag.databaseOpen("db=" + DB_NAME + " version=" + opened.getVersion());
                return opened;
            }
            return helper.getWritableDatabase();
        }
    }

    /** Drops the cached helper. Tests call it between cases; production never needs to. */
    static void resetForTests() {
        synchronized (LOCK) {
            if (helper != null) { helper.close(); helper = null; }
            DEFERRED_MEDIA.clear();
        }
    }

    // --- Capture --------------------------------------------------------------------------------------------

    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HushVault");
        thread.setDaemon(true);
        return thread;
    });
    private static final ThreadPoolExecutor MEDIA = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "HushVaultMedia");
            thread.setDaemon(true);
            return thread;
        });
    private static final long RESOLUTION_WAIT_MS = 10_000;
    private static final int MAX_DEFERRED_MEDIA = 64;
    private static final int MAX_DEFERRED_ADDRESSES = 8;
    private static final int MAX_ADDRESS_LENGTH = 8192;
    private static final List<DeferredMedia> DEFERRED_MEDIA = new ArrayList<>();
    private static boolean deferredCleanupScheduled;

    private static final class DeferredMedia {
        final String id, thread, diag;
        final MessageLogAttachment.Found found;
        final long expiresAt;
        final boolean nativePlayback;

        DeferredMedia(String id, String thread, MessageLogAttachment.Found found, String diag, long expiresAt,
                      boolean nativePlayback) {
            this.id = id;
            this.thread = thread;
            this.found = found;
            this.diag = diag;
            this.expiresAt = expiresAt;
            this.nativePlayback = nativePlayback;
        }
    }

    private static final AtomicBoolean PURGE_SCHEDULED = new AtomicBoolean();

    /**
     * A capture from one of the hooks. Only queues the record and returns; the write and any media fetch happen
     * on the vault's own threads, never on Messenger's message path.
     */
    static void record(String text, String thread, String id, String source, MessageLogAttachment.Found found) {
        record(text, thread, id, source, found, null, "");
    }

    /**
     * A capture whose scan also learned the media's kind and a fingerprint of what the message carried, for
     * the many messages whose media has no usable address at notification time: the entry is still filed
     * under its kind, with the reason on it, instead of looking like a plain text message.
     */
    static void record(String text, String thread, String id, String source, MessageLogAttachment.Found found,
                       String shapeKind, String diag) {
        record(text, thread, id, source, found, shapeKind, diag, 0);
    }

    static void record(String text, String thread, String id, String source, MessageLogAttachment.Found found,
                       String shapeKind, String diag, long sentAt) {
        final String keptText = text, keptThread = thread, keptId = id, keptSource = source;
        final MessageLogAttachment.Found keptFound = found;
        final String keptShape = shapeKind, keptDiag = diag == null ? "" : diag;
        try {
            // The write waits a moment, as the message log's does, so the notification the sender's name is
            // read from has been posted by the time the entry is written.
            WRITER.schedule(() -> recordNow(keptText, keptThread, keptId, keptSource, keptFound,
                sentAt, 0, "", "", System.currentTimeMillis(), keptShape, keptDiag),
                MessageLog.NAME_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            Settings.hookFailedPrivately(KEY, "Can't queue a message for the vault", rejected);
        }
    }

    /** Captures a row surface already queued in this minute, by row key and a digest of its words. */
    private static final java.util.LinkedHashMap<String, Long> RECENT_ROWS = new java.util.LinkedHashMap<>();

    /** A capture from a chat row, which carries the message's own send time and sender. See {@link #recordRowNow}. */
    static void recordRow(String text, String msgId, String threadingId, long sentAt, long senderId,
                          int contentType, int contentSubtype) {
        // A row is rendered again at every scroll and redraw, so the same words under the same key queue once a
        // minute at most; the database merge would only have repeated what the first capture wrote.
        String key = (msgId != null && !msgId.isEmpty() ? msgId : threadingId == null ? "" : threadingId)
            + '\u0001' + MessageLog.digest(text == null ? "" : text);
        long now = System.currentTimeMillis();
        synchronized (RECENT_ROWS) {
            RECENT_ROWS.values().removeIf(at -> now - at > 60_000 || at > now);
            Long seen = RECENT_ROWS.get(key);
            if (seen != null && now - seen <= 60_000) return;
            RECENT_ROWS.put(key, now);
            while (RECENT_ROWS.size() > 512) {
                java.util.Iterator<String> oldest = RECENT_ROWS.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
        try {
            WRITER.execute(() -> recordRowNow(text, msgId, threadingId, sentAt, senderId, contentType, contentSubtype,
                System.currentTimeMillis()));
        } catch (RuntimeException rejected) {
            Settings.hookFailedPrivately(KEY, "Can't queue a chat row for the vault", rejected);
        }
    }

    /**
     * Writes one capture on the calling thread and returns its row id, or -1 when nothing was written (a duplicate
     * that changed nothing still returns the existing row's id). Tests call this directly.
     *
     * <p>[found] is the message's media as the notification hook read it, or null. Media is fetched afterwards,
     * off this thread, and only when the vault's own keep switch for its kind is on.
     */
    static long recordNow(String text, String thread, String id, String source,
                          MessageLogAttachment.Found found, long sentAt, long senderId,
                          String sender, String chat, long now) {
        return recordNow(text, thread, id, source, found, sentAt, senderId, sender, chat, now, null, "");
    }

    /** {@link #recordNow} with the scan's kind and fingerprint, for a message whose media carried no address. */
    static long recordNow(String text, String thread, String id, String source,
                          MessageLogAttachment.Found found, long sentAt, long senderId,
                          String sender, String chat, long now, String shapeKind, String diag) {
        Context context = Settings.appContext;
        if (context == null) return -1;
        if (found != null && MessageLogAttachment.EPHEMERAL.equals(found.kind)) {
            VaultDiag.filtered("view_once");
            return -1;
        }
        boolean attachment = text == null;
        String words = attachment ? "" : MessageLog.clip(text, MAX_TEXT_CHARS);
        String keptThread = MessageLog.clip(thread == null ? "" : thread, MAX_THREAD_CHARS);
        String keptId = MessageLog.clip(id == null ? "" : id, MAX_ID_CHARS);
        String kind = kindOf(words, found, attachment);
        String shaped = kindForShape(shapeKind);
        if (shaped != null && (KIND_TEXT.equals(kind) || KIND_ATTACHMENT.equals(kind))) kind = shaped;
        // A sticker or a GIF arrives shaped like a photo — its data is a photo's data — so the scan's declared
        // shape refines a photo kind into the kind the attachment actually is.
        if (shaped != null && KIND_PHOTO.equals(kind)
            && (KIND_STICKER.equals(shaped) || KIND_GIF.equals(shaped)
                || KIND_VIDEO.equals(shaped) || KIND_AUDIO.equals(shaped))) kind = shaped;
        // A message that is only emoji is filed as one, so the vault's kinds say what the message is.
        if (KIND_TEXT.equals(kind) && isEmojiOnly(words)) kind = KIND_EMOJI;
        // The notification hook hands over no names; the message log reads them off the notification that
        // showed the message, and so does the vault, the same way, without touching the log's own copies.
        String keptSender = sender == null ? "" : sender;
        String keptChat = chat == null ? "" : chat;
        if (keptSender.isEmpty() && SOURCE_NOTIFICATION.equals(source) && !keptThread.isEmpty()) {
            try {
                android.service.notification.StatusBarNotification shown = ChatNotifications.find(context, keptThread);
                if (shown != null) {
                    String named = ChatNotifications.sender(shown.getNotification(), words, keptThread);
                    String group = ChatNotifications.conversation(shown.getNotification());
                    if (named != null) keptSender = MessageLog.clip(named, MAX_NAME_CHARS);
                    if (group != null && !group.equals(named)) keptChat = MessageLog.clip(group, MAX_NAME_CHARS);
                }
            } catch (RuntimeException unreadable) {
                // The entry is still archived, without a name.
            }
        }
        try {
            synchronized (LOCK) {
                SQLiteDatabase db = db(context);
                purgeLocked(context, db, now);
                boolean playback = SOURCE_ROW.equals(source) && found != null;
                VideoIdentity link = videoIdentity(db, found, sentAt, keptId, now, playback);
                String archiveId = link == null ? keptId : link.messageId;
                String dedup = !archiveId.isEmpty() ? "m:" + archiveId
                    : "t:" + MessageLog.digest(keptThread + '\u0001' + words + '\u0001'
                        + ((sentAt > 0 ? sentAt : now) / LOOSE_MERGE_WINDOW_MS));
                long rowId = mergeOrInsertLocked(db, archiveId, dedup, !archiveId.isEmpty(), keptThread, keptSender,
                    keptChat, senderId, kind, words, sentAt > 0 ? sentAt : now, now, source, found, attachment,
                    diag == null ? "" : diag, shaped != null);
                if (rowId >= 0) {
                    rememberVideoIdentity(db, rowId, found, sentAt, keptId, playback);
                    if (found != null && (link == null || !link.recalled)) {
                        queueMediaLocked(context, db, rowId, kind, found, playback);
                    }
                    drainDeferredLocked(context, db, archiveId, now);
                    Settings.activeAt.put(KEY, now);
                }
                return rowId;
            }
        } catch (SQLiteFullException full) {
            VaultDiag.insertFailed("SQLiteFullException");
            Settings.hookFailedPrivately(KEY, "The phone's storage is full, so a message couldn't be archived", full);
            return -1;
        } catch (Throwable failure) {
            VaultDiag.insertFailed(failure.getClass().getSimpleName());
            Settings.hookFailedPrivately(KEY, "Can't archive a message in the vault", failure);
            return -1;
        }
    }

    /**
     * A row's capture. A row is how an open chat shows a message, so a fresh one is an arrival; an old one is
     * history. History only ever fills an entry that already exists (by id, or by threading id), because
     * archiving it as new would invent an arrival time it never had. The placeholder row Messenger swaps in for
     * an unsent encrypted message (type 5, subtype 1, the message log's constants) is not a capture either.
     */
    static long recordRowNow(String text, String msgId, String threadingId, long sentAt, long senderId,
                             int contentType, int contentSubtype, long now) {
        if (text == null || text.isEmpty()) return -1;
        if (contentType == Settings.UNSENT_PLACEHOLDER_TYPE && contentSubtype == Settings.UNSENT_PLACEHOLDER_SUBTYPE) return -1;
        Context context = Settings.appContext;
        if (context == null) return -1;
        String keptId = MessageLog.clip(msgId == null ? "" : msgId, MAX_ID_CHARS);
        String keptThreading = MessageLog.clip(threadingId == null ? "" : threadingId, MAX_ID_CHARS);
        boolean fresh = sentAt > 0 && now - sentAt <= ROW_ARRIVAL_WINDOW_MS && sentAt <= now + ROW_ARRIVAL_WINDOW_MS;
        try {
            synchronized (LOCK) {
                SQLiteDatabase db = db(context);
                purgeLocked(context, db, now);
                // Rows key an entry by the message id when they have one, else by the threading id, which is how
                // the row surface itself tells one message from another.
                String key = !keptId.isEmpty() ? keptId : keptThreading;
                if (key.isEmpty()) return -1;
                if (!fresh) {
                    Cursor existing = db.query("entries", new String[] {"row_id"},
                        "msg_id=? OR dedup=? OR dedup=?", new String[] {key, "m:" + key, "r:" + key},
                        null, null, null, "1");
                    try {
                        if (!existing.moveToFirst()) return -1;
                    } finally { existing.close(); }
                }
                String dedup = !keptId.isEmpty() ? "m:" + keptId : "r:" + keptThreading;
                long rowId = mergeOrInsertLocked(db, key, dedup, !keptId.isEmpty(), "", "", "",
                    senderId, KIND_TEXT, MessageLog.clip(text, MAX_TEXT_CHARS), sentAt > 0 ? sentAt : now, now,
                    SOURCE_ROW, null, false, "", false);
                if (rowId >= 0) {
                    drainDeferredLocked(context, db, key, now);
                    Settings.activeAt.put(KEY, now);
                }
                return rowId;
            }
        } catch (SQLiteFullException full) {
            VaultDiag.insertFailed("SQLiteFullException");
            Settings.hookFailedPrivately(KEY, "The phone's storage is full, so a message couldn't be archived", full);
            return -1;
        } catch (Throwable failure) {
            VaultDiag.insertFailed(failure.getClass().getSimpleName());
            Settings.hookFailedPrivately(KEY, "Can't archive a chat row in the vault", failure);
            return -1;
        }
    }

    /** What kind of entry a capture is, from its media when it has some and from Messenger's own words when not. */
    static String kindOf(String words, MessageLogAttachment.Found found, boolean attachment) {
        if (found != null) {
            String shaped = kindForShape(found.kind);
            return shaped == null ? KIND_ATTACHMENT : shaped;
        }
        if (!attachment) return KIND_TEXT;
        String said = words == null ? "" : words.toLowerCase(java.util.Locale.ROOT);
        if (said.contains("sticker")) return KIND_STICKER;
        if (said.contains("gif")) return KIND_GIF;
        if (said.contains("voice") || said.contains("audio")) return KIND_AUDIO;
        if (said.contains("video")) return KIND_VIDEO;
        if (said.contains("photo") || said.contains("image")) return KIND_PHOTO;
        if (said.contains("file")) return KIND_FILE;
        return KIND_ATTACHMENT;
    }

    /** The vault kind a scan's shape names, or null when it names none the vault files media under. */
    static String kindForShape(String shapeKind) {
        if (shapeKind == null) return null;
        if (MessageLogAttachment.AUDIO.equals(shapeKind)) return KIND_AUDIO;
        if (MessageLogAttachment.VIDEO.equals(shapeKind)) return KIND_VIDEO;
        if (MessageLogAttachment.PHOTO.equals(shapeKind)) return KIND_PHOTO;
        if (MessageLogAttachment.STICKER.equals(shapeKind)) return KIND_STICKER;
        if (MessageLogAttachment.GIF.equals(shapeKind)) return KIND_GIF;
        if (MessageLogAttachment.FILE.equals(shapeKind)) return KIND_FILE;
        return null;
    }

    /**
     * Whether [words] is nothing but emoji — pictographs, their skin tones and joiners, and whitespace. A
     * message of words with an emoji in it is a text message; a message that IS an emoji is filed as one.
     */
    static boolean isEmojiOnly(String words) {
        if (words == null || words.isEmpty() || words.length() > 64) return false;
        boolean any = false;
        for (int i = 0; i < words.length();) {
            int cp = words.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 || Character.isWhitespace(cp)) continue;
            if ((cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF) || cp == 0x203C || cp == 0x2049
                || cp == 0x00A9 || cp == 0x00AE || cp == 0x2122 || cp == 0x2139) {
                any = true;
                continue;
            }
            return false;
        }
        return any;
    }

    /** Delivers resolved metadata on the writer, after the hook has checked the message. */
    static void attachFound(String id, String thread, MessageLogAttachment.Found found, String diag) {
        enqueueResolved(id, thread, found, diag, false);
    }

    static void attachNativePlayback(String id, MessageLogAttachment.Found found, long sentAt) {
        if (id.isEmpty() || id.length() > MAX_ID_CHARS) return;
        try {
            WRITER.execute(() -> {
                if (Settings.vaultWanted()) recordNativePlaybackNow(id, found, sentAt, System.currentTimeMillis());
            });
        } catch (RuntimeException rejected) {
            VaultDiag.media("associate", "queue_rejected");
        }
    }

    static void recordNativePlaybackNow(String id, MessageLogAttachment.Found found, long sentAt, long now) {
        Context context = Settings.appContext;
        if (context == null || id == null || id.isEmpty() || id.length() > MAX_ID_CHARS || found == null
            || (!MessageLogAttachment.VIDEO.equals(found.kind) && !MessageLogAttachment.AUDIO.equals(found.kind))) return;
        try {
            synchronized (LOCK) {
                SQLiteDatabase db = db(context);
                if (attachExactLocked(context, db, id, "", found, "", now, true) != 0) return;
                VideoIdentity link = videoIdentity(db, found, sentAt, id, now, true);
                if (link != null) {
                    rememberVideoIdentity(db, link.rowId, found, sentAt, id, true);
                    attachExactLocked(context, db, link.messageId, "", found, "", now, true);
                    return;
                }
                // A notification without the server ID cannot establish this identity. The stock playback
                // model carries the ID and send time, so a fresh arrival can create its own typed entry.
                if (sentAt > 0 && now - sentAt <= ROW_ARRIVAL_WINDOW_MS && sentAt <= now + ROW_ARRIVAL_WINDOW_MS) {
                    recordNow(null, "", id, SOURCE_ROW, found, sentAt, 0, "", "", now, found.kind, "");
                    VaultDiag.media("associate", "native_playback_arrival");
                } else {
                    deferLocked(id, "", found, "", now, true);
                }
            }
        } catch (RuntimeException failure) {
            VaultDiag.media("associate", "database_error");
        }
    }

    private static final class VideoIdentity {
        final long rowId;
        final String messageId;
        final boolean recalled;

        VideoIdentity(long rowId, String messageId, boolean recalled) {
            this.rowId = rowId;
            this.messageId = messageId;
            this.recalled = recalled;
        }
    }

    private static boolean validVideoIdentity(MessageLogAttachment.Found found, long sentAt, String id) {
        return found != null && MessageLogAttachment.VIDEO.equals(found.kind) && !found.videoPosterKey.isEmpty()
            && sentAt > 0 && id != null && !id.isEmpty();
    }

    private static VideoIdentity videoIdentity(SQLiteDatabase db, MessageLogAttachment.Found found, long sentAt,
                                               String id, long now, boolean playback) {
        if (!validVideoIdentity(found, sentAt, id)) return null;
        String own = playback ? "playback_id" : "notification_id";
        String other = playback ? "notification_id" : "playback_id";
        try (Cursor row = db.rawQuery("SELECT e.row_id,e.msg_id,e.status,v." + own + ",v." + other
                + " FROM vault_video_identity v JOIN entries e ON e.row_id=v.row_id"
                + " WHERE v.poster_key=? AND v.sent_at=? AND e.expires_at>? LIMIT 2",
            new String[] {found.videoPosterKey, String.valueOf(sentAt), String.valueOf(now)})) {
            if (!row.moveToFirst()) return null;
            if (row.getCount() != 1) {
                VaultDiag.media("associate", "ambiguous_video_identity");
                return null;
            }
            String previous = row.getString(3);
            if (row.getString(4).isEmpty() || (!previous.isEmpty() && !id.equals(previous))) return null;
            VaultDiag.media("associate", "video_identity_matched");
            return new VideoIdentity(row.getLong(0), row.getString(1), STATUS_RECALLED.equals(row.getString(2)));
        }
    }

    private static void rememberVideoIdentity(SQLiteDatabase db, long rowId, MessageLogAttachment.Found found,
                                              long sentAt, String id, boolean playback) {
        if (!validVideoIdentity(found, sentAt, id)) return;
        ContentValues initial = new ContentValues();
        initial.put("row_id", rowId);
        initial.put("poster_key", found.videoPosterKey);
        initial.put("sent_at", sentAt);
        db.insertWithOnConflict("vault_video_identity", null, initial, SQLiteDatabase.CONFLICT_IGNORE);
        String column = playback ? "playback_id" : "notification_id";
        ContentValues alias = new ContentValues();
        alias.put(column, id);
        db.update("vault_video_identity", alias,
            "row_id=? AND poster_key=? AND sent_at=? AND (" + column + "='' OR " + column + "=?)",
            new String[] {String.valueOf(rowId), found.videoPosterKey, String.valueOf(sentAt), id});
    }

    private static void pruneVideoIdentities(SQLiteDatabase db) {
        db.execSQL("DELETE FROM vault_video_identity WHERE row_id NOT IN (SELECT row_id FROM entries)");
    }

    private static void enqueueResolved(String id, String thread, MessageLogAttachment.Found found, String diag,
                                        boolean nativePlayback) {
        if (id == null || id.isEmpty()) return;
        try {
            WRITER.execute(() -> {
                if (Settings.vaultWanted()) {
                    attachFoundNow(id, thread, found, diag, System.currentTimeMillis(), nativePlayback);
                }
            });
        } catch (RuntimeException rejected) {
            VaultDiag.media("associate", "queue_rejected");
        }
    }

    static boolean attachFoundNow(String id, MessageLogAttachment.Found found, String diag, long now) {
        return attachFoundNow(id, "", found, diag, now);
    }

    static boolean attachFoundNow(String id, String thread, MessageLogAttachment.Found found, String diag, long now) {
        return attachFoundNow(id, thread, found, diag, now, false);
    }

    static boolean attachFoundNow(String id, String thread, MessageLogAttachment.Found found, String diag,
                                  long now, boolean nativePlayback) {
        Context context = Settings.appContext;
        if (context == null || id == null || id.isEmpty() || id.length() > MAX_ID_CHARS) return false;
        if (found != null && MessageLogAttachment.EPHEMERAL.equals(found.kind)) return false;
        String keptThread = MessageLog.clip(thread == null ? "" : thread, MAX_THREAD_CHARS);
        try {
            synchronized (LOCK) {
                SQLiteDatabase db = db(context);
                int match = attachExactLocked(context, db, id, keptThread, found, diag, now, nativePlayback);
                if (match == 0 && found != null) deferLocked(id, keptThread, found, diag, now, nativePlayback);
                return match == 1;
            }
        } catch (Exception failure) {
            VaultDiag.media("associate", "database_error");
            return false;
        }
    }

    /** Returns 0 if a row can still arrive, -1 for conflicting identities, or 1 for a match. */
    private static int attachExactLocked(Context context, SQLiteDatabase db, String id, String thread,
                                         MessageLogAttachment.Found found, String diag, long now,
                                         boolean nativePlayback) {
        try (Cursor row = db.query("entries", new String[] {"row_id", "thread", "kind", "diag", "status"},
            "msg_id=? AND expires_at>?", new String[] {id, String.valueOf(now)}, null, null, null, "2")) {
            if (!row.moveToFirst()) return 0;
            if (row.getCount() != 1) {
                VaultDiag.media("associate", "ambiguous_id");
                return -1;
            }
            String storedThread = row.getString(1);
            if (!thread.isEmpty() && !storedThread.isEmpty() && !thread.equals(storedThread)) {
                VaultDiag.media("associate", "thread_mismatch");
                return -1;
            }
            long rowId = row.getLong(0);
            if (nativePlayback && !acceptsNativePlayback(row.getString(2), row.getString(4), found)) {
                VaultDiag.media("associate", "native_playback_ineligible");
                return -1;
            }
            if (diag != null && !diag.isEmpty() && !diag.equals(row.getString(3))) {
                ContentValues values = new ContentValues();
                values.put("diag", diag);
                db.update("entries", values, "row_id=?", new String[] {String.valueOf(rowId)});
            }
            if (found != null) queueMediaLocked(context, db, rowId, row.getString(2), found, nativePlayback);
            VaultDiag.media("associate", nativePlayback ? "native_playback_matched" : "matched");
            return 1;
        }
    }

    private static boolean acceptsNativePlayback(String kind, String status, MessageLogAttachment.Found found) {
        if (found == null || STATUS_RECALLED.equals(status)) return false;
        String expected = kindForShape(found.kind);
        return (KIND_VIDEO.equals(expected) || KIND_AUDIO.equals(expected))
            && (expected.equals(kind) || KIND_ATTACHMENT.equals(kind)
                || (KIND_VIDEO.equals(expected) && KIND_PHOTO.equals(kind)));
    }

    private static void deferLocked(String id, String thread, MessageLogAttachment.Found found, String diag, long now,
                                    boolean nativePlayback) {
        DEFERRED_MEDIA.removeIf(held -> held.expiresAt <= now);
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        long expiresAt = now + RESOLUTION_WAIT_MS;
        for (Iterator<DeferredMedia> items = DEFERRED_MEDIA.iterator(); items.hasNext();) {
            DeferredMedia held = items.next();
            if (held.nativePlayback != nativePlayback || !held.id.equals(id) || !held.thread.equals(thread)
                || !held.found.kind.equals(found.kind)) continue;
            if (Arrays.equals(held.found.addresses, found.addresses)) return;
            expiresAt = held.expiresAt;
            addresses.addAll(Arrays.asList(held.found.addresses));
            items.remove();
            break;
        }
        for (String address : found.addresses) {
            if (addresses.size() >= MAX_DEFERRED_ADDRESSES) break;
            if (address != null && address.length() <= MAX_ADDRESS_LENGTH) addresses.add(address);
        }
        if (addresses.isEmpty()) return;
        if (DEFERRED_MEDIA.size() == MAX_DEFERRED_MEDIA) DEFERRED_MEDIA.remove(0);
        DEFERRED_MEDIA.add(new DeferredMedia(id, thread,
            new MessageLogAttachment.Found(addresses.toArray(new String[0]), found.kind), diag, expiresAt,
            nativePlayback));
        VaultDiag.media("associate", "waiting_for_insert");
        if (!deferredCleanupScheduled) {
            deferredCleanupScheduled = true;
            WRITER.scheduleWithFixedDelay(() -> {
                synchronized (LOCK) {
                    long time = System.currentTimeMillis();
                    DEFERRED_MEDIA.removeIf(held -> held.expiresAt <= time);
                }
            }, RESOLUTION_WAIT_MS, 1_000, TimeUnit.MILLISECONDS);
        }
    }

    private static void drainDeferredLocked(Context context, SQLiteDatabase db, String id, long now) {
        if (id.isEmpty()) return;
        for (Iterator<DeferredMedia> items = DEFERRED_MEDIA.iterator(); items.hasNext();) {
            DeferredMedia held = items.next();
            if (held.expiresAt <= now || (held.id.equals(id)
                && attachExactLocked(context, db, id, held.thread, held.found, held.diag, now, held.nativePlayback) != 0)) {
                items.remove();
            }
        }
    }

    private static void queueMediaLocked(Context context, SQLiteDatabase db, long rowId, String declared,
                                         MessageLogAttachment.Found found) {
        queueMediaLocked(context, db, rowId, declared, found, false);
    }

    private static void queueMediaLocked(Context context, SQLiteDatabase db, long rowId, String declared,
                                         MessageLogAttachment.Found found, boolean nativePlayback) {
        String current = mediaStatusLocked(db, rowId);
        String kind = found.kind;
        if (MessageLogAttachment.PHOTO.equals(kind)) {
            if (KIND_VIDEO.equals(declared)) kind = MessageLogAttachment.VIDEO;
            if (KIND_AUDIO.equals(declared)) kind = MessageLogAttachment.AUDIO;
            if (KIND_STICKER.equals(declared)) kind = MessageLogAttachment.STICKER;
            if (KIND_GIF.equals(declared)) kind = MessageLogAttachment.GIF;
        }
        boolean stored = MEDIA_STORED.equals(current);
        if (stored && (!nativePlayback || !hasStoredPosterLocked(db, rowId))) return;
        if (!mediaWanted(kind)) {
            if (!stored && !MEDIA_PENDING.equals(current)) {
                updateMediaStatusLocked(db, rowId, MEDIA_NOT_KEPT, "", kind, 0);
            }
            return;
        }
        if (!stored) updateMediaStatusLocked(db, rowId, MEDIA_PENDING, "", kind, 0);
        fetchMedia(context, rowId, new MessageLogAttachment.Found(found.addresses, kind), nativePlayback);
    }

    /** Whether the vault's own keep switch for this media kind is on. The message log's switches don't decide. */
    static boolean mediaWanted(String foundKind) {
        android.content.SharedPreferences prefs = Settings.preferences;
        if (prefs == null) return false;
        String key = MessageLogAttachment.AUDIO.equals(foundKind) ? KEEP_VOICE
            : MessageLogAttachment.VIDEO.equals(foundKind) ? KEEP_VIDEOS : KEEP_PHOTOS;
        return prefs.getBoolean(key, false);
    }

    /**
     * Inserts the capture, or merges it into the entry it belongs to. Matching is by the capture's dedup key
     * first (the message's own id when a surface hands one over, a row's threading id under its own prefix
     * when it doesn't, a digest of thread and words when neither surface has an id), then, for word captures,
     * loosely: the same words in the same chat within {@link #LOOSE_MERGE_WINDOW_MS}, where a surface that
     * names no chat (a chat row) matches any. The loose match is what lets the vault's two surfaces agree on
     * one message when only one of them ever saw its id; it never joins two captures that each carry a
     * different message id of their own, and it merges nothing when more than one entry could be the match.
     * A merge never moves the first capture's times (the 72 hours run from the first capture), never overwrites
     * a recall, fills only empty fields, upgrades a weaker key to the message's own id when a later capture
     * brings it, and treats different words under one id as an edit.
     */
    private static long mergeOrInsertLocked(SQLiteDatabase db, String msgId, String dedup, boolean strongId,
                                            String thread, String sender,
                                            String chat, long senderId, String kind, String words,
                                            long sentAt, long now, String source,
                                            MessageLogAttachment.Found found, boolean attachment,
                                            String diag, boolean mediaShaped) throws Exception {
        long existing = -1;
        boolean keepExistingId = false;
        Cursor byDedup = db.query("entries", new String[] {"row_id"}, "dedup=?", new String[] {dedup}, null, null, null, "1");
        try { if (byDedup.moveToFirst()) existing = byDedup.getLong(0); } finally { byDedup.close(); }
        if (existing < 0 && (KIND_TEXT.equals(kind) || KIND_EMOJI.equals(kind)) && !words.isEmpty()) {
            Cursor loose = db.query("entries", new String[] {"row_id", "body", "thread", "dedup", "sent_at",
                    "sender_id", "kind", "source"},
                "kind IN (?,?) AND received_at>=? AND expires_at>?",
                new String[] {KIND_TEXT, KIND_EMOJI, String.valueOf(now - LOOSE_MERGE_WINDOW_MS),
                    String.valueOf(now)},
                null, null, "received_at DESC");
            try {
                long match = -1;
                boolean matchKeepsId = false;
                while (loose.moveToNext()) {
                    if (!words.equals(openText(loose.getBlob(1)))) continue;
                    String otherThread = loose.getString(2);
                    if (otherThread == null) otherThread = "";
                    if (!thread.isEmpty() && !otherThread.isEmpty() && !thread.equals(otherThread)) continue;
                    String otherDedup = loose.getString(3);
                    boolean otherStrong = otherDedup != null && otherDedup.startsWith("m:");
                    boolean keepsId = false;
                    if (strongId && otherStrong && !otherDedup.equals(dedup)) {
                        // Two surfaces named the same message by different ids — the shape of an encrypted
                        // chat, whose notification and whose chat row spell one message's id differently.
                        // Two ids from the SAME surface are two messages (each surface's ids are its own
                        // authority), so the merge is cross-surface only; and the same words alone don't
                        // prove sameness (a reaction sent twice is two messages), so the send times must
                        // also agree closely and the senders must not disagree. When they do agree, the
                        // entry keeps the first id it was filed under.
                        String otherSource = loose.getString(7);
                        if (otherSource != null && otherSource.equals(source)) continue;
                        long otherSentAt = loose.getLong(4);
                        long otherSender = loose.getLong(5);
                        if (Math.abs(sentAt - otherSentAt) > SAME_MESSAGE_WINDOW_MS) continue;
                        if (otherSender != 0 && senderId != 0 && otherSender != senderId) continue;
                        keepsId = true;
                    }
                    if (match >= 0) { match = -1; break; }
                    match = loose.getLong(0);
                    matchKeepsId = keepsId;
                }
                existing = match;
                keepExistingId = matchKeepsId && match >= 0;
            } finally { loose.close(); }
        }
        if (existing < 0) {
            ContentValues values = new ContentValues();
            values.put("dedup", dedup);
            values.put("msg_id", msgId);
            values.put("thread", thread);
            values.put("sender", sealText(sender));
            values.put("chat", sealText(chat));
            values.put("sender_id", senderId);
            values.put("kind", kind);
            values.put("body", sealText(words));
            values.put("sent_at", sentAt);
            values.put("received_at", now);
            values.put("expires_at", now + TTL_MS);
            values.put("status", STATUS_ACTIVE);
            values.put("source", source);
            values.put("media_status", found != null ? MEDIA_PENDING
                : attachment || mediaShaped ? MEDIA_NO_ADDRESS : MEDIA_NONE);
            values.put("diag", diag);
            long inserted = db.insertOrThrow("entries", null, values);
            VaultDiag.encryptSuccess();
            VaultDiag.dedup("inserted");
            VaultDiag.insertSuccess(inserted);
            return inserted;
        }
        // Merge into the existing entry.
        Cursor row = db.query("entries",
            new String[] {"body", "edited_from", "status", "sender", "chat", "thread", "kind", "media_status",
                "msg_id", "dedup", "diag"},
            "row_id=?", new String[] {String.valueOf(existing)}, null, null, null, "1");
        try {
            if (!row.moveToFirst()) {
                VaultDiag.dedup("already_present");
                return existing;
            }
            String oldWords = openText(row.getBlob(0));
            String status = row.getString(2);
            ContentValues values = new ContentValues();
            if (strongId && !keepExistingId && !msgId.isEmpty() && !dedup.equals(row.getString(9))) {
                // A later capture brought the message's own id to an entry a weaker key started: the entry
                // takes the id, so a recall naming it finds this entry. No other entry can hold that key —
                // the dedup lookup above would have matched it instead.
                values.put("msg_id", msgId);
                values.put("dedup", dedup);
            }
            if (!words.isEmpty() && !oldWords.isEmpty() && !words.equals(oldWords) && !STATUS_RECALLED.equals(status)) {
                values.put("edited_from", sealText(oldWords));
                values.put("body", sealText(words));
                values.put("status", STATUS_EDITED);
            } else if (!words.isEmpty() && oldWords.isEmpty()) {
                values.put("body", sealText(words));
            }
            // A name is filled whenever the entry has none yet — an entry written before its notification
            // could be read holds an empty sealed name, not a missing one, and an empty name is still none.
            String oldSender = openText(row.getBlob(3));
            if ((oldSender == null || oldSender.isEmpty()) && sender != null && !sender.isEmpty()) {
                values.put("sender", sealText(sender));
            }
            String oldChat = openText(row.getBlob(4));
            if ((oldChat == null || oldChat.isEmpty()) && chat != null && !chat.isEmpty()) {
                values.put("chat", sealText(chat));
            }
            if ((row.getString(5) == null || row.getString(5).isEmpty()) && !thread.isEmpty()) values.put("thread", thread);
            String oldKind = row.getString(6);
            if ((KIND_TEXT.equals(oldKind) || KIND_ATTACHMENT.equals(oldKind))
                && (KIND_PHOTO.equals(kind) || KIND_VIDEO.equals(kind) || KIND_AUDIO.equals(kind))) {
                values.put("kind", kind);
            } else if (KIND_TEXT.equals(oldKind) && !KIND_TEXT.equals(kind)) {
                values.put("kind", kind);
            }
            String oldDiag = row.getString(10);
            if ((oldDiag == null || oldDiag.isEmpty()) && diag != null && !diag.isEmpty()) {
                values.put("diag", diag);
            }
            if (values.size() > 0) db.update("entries", values, "row_id=?", new String[] {String.valueOf(existing)});
            VaultDiag.dedup(values.containsKey("edited_from") ? "edited" : "merged");
            return existing;
        } finally { row.close(); }
    }

    private static boolean liveEntryLocked(SQLiteDatabase db, long rowId) {
        try (Cursor row = db.query("entries", new String[] {"row_id"}, "row_id=? AND expires_at>?",
            new String[] {String.valueOf(rowId), String.valueOf(clock.getAsLong())}, null, null, null, "1")) {
            return row.moveToFirst();
        }
    }

    private static void recoverMediaLocked(Context context, SQLiteDatabase db) {
        ContentValues interrupted = new ContentValues();
        interrupted.put("media_status", MEDIA_FAILED);
        int recovered = db.update("entries", interrupted, "media_status=?", new String[] {MEDIA_PENDING});
        if (recovered > 0) VaultDiag.media("persist", "interrupted");

        File dir = new File(context.getFilesDir(), MEDIA_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Set<String> referenced = new java.util.HashSet<>();
            try (Cursor rows = db.query("entries", new String[] {"media_name"}, "media_name<>''",
                null, null, null, null)) {
                while (rows.moveToNext()) referenced.add(rows.getString(0));
            }
            for (File file : files) {
                if (!referenced.contains(file.getName())) file.delete();
            }
        }
        deletePlaybackFiles(context, "");
    }

    private static String mediaStatusLocked(SQLiteDatabase db, long rowId) {
        Cursor row = db.query("entries", new String[] {"media_status"}, "row_id=?",
            new String[] {String.valueOf(rowId)}, null, null, null, "1");
        try {
            return row.moveToFirst() ? row.getString(0) : MEDIA_NONE;
        } finally { row.close(); }
    }

    private static void updateMediaStatusLocked(SQLiteDatabase db, long rowId, String status, String name,
                                                String kind, long bytes) {
        ContentValues values = new ContentValues();
        values.put("media_status", status);
        if (name != null && !name.isEmpty()) values.put("media_name", name);
        if (kind != null && !kind.isEmpty()) values.put("media_kind", kind);
        if (bytes > 0) values.put("media_bytes", bytes);
        db.update("entries", values, "row_id=?", new String[] {String.valueOf(rowId)});
    }

    private static boolean hasStoredPosterLocked(SQLiteDatabase db, long rowId) {
        try (Cursor row = db.query("entries", new String[] {"media_kind", "status"}, "row_id=?",
            new String[] {String.valueOf(rowId)}, null, null, null, "1")) {
            if (!row.moveToFirst() || STATUS_RECALLED.equals(row.getString(1))) return false;
            String mime = row.getString(0);
            return mime != null && mime.startsWith("image/");
        }
    }

    private static boolean mayPersistLocked(SQLiteDatabase db, long rowId, boolean nativePlayback) {
        return liveEntryLocked(db, rowId) && (!MEDIA_STORED.equals(mediaStatusLocked(db, rowId))
            || (nativePlayback && hasStoredPosterLocked(db, rowId)));
    }

    private static void fetchMedia(Context context, long rowId, MessageLogAttachment.Found found,
                                   boolean nativePlayback) {
        String job = rowId + ":" + MessageLog.digest(nativePlayback + found.kind + Arrays.toString(found.addresses));
        if (!IN_FLIGHT.add(job)) return;
        try {
            MEDIA.execute(() -> {
                try {
                    persistMedia(context, rowId, found, nativePlayback);
                } catch (Exception failure) {
                    VaultDiag.media("persist", "database_error");
                } finally {
                    synchronized (LOCK) { IN_FLIGHT.remove(job); }
                }
            });
        } catch (RuntimeException rejected) {
            IN_FLIGHT.remove(job);
            if (!MEDIA_STORED.equals(mediaStatusLocked(db(context), rowId))) {
                updateMediaStatusLocked(db(context), rowId, MEDIA_FAILED, "", found.kind, 0);
            }
            VaultDiag.media("fetch", "queue_rejected");
        }
    }

    private static boolean captureBlocked() {
        android.content.SharedPreferences prefs = Settings.preferences;
        return prefs == null || !prefs.getBoolean(KEY, false) || prefs.getBoolean("paused", false)
            || CrashGuard.isSafeMode();
    }

    private static void persistMedia(Context context, long rowId, MessageLogAttachment.Found found,
                                     boolean nativePlayback) {
        synchronized (LOCK) {
            if (!mayPersistLocked(db(context), rowId, nativePlayback)) return;
        }

        File dir = new File(context.getFilesDir(), MEDIA_DIR);
        File target = new File(dir, MessageLog.digest("vault-row:" + rowId + (nativePlayback ? ":" + found.kind : "")));
        List<String> candidates = VaultMedia.ordered(found);
        String result = MEDIA_UNAVAILABLE;
        for (String address : candidates) {
            if (captureBlocked()) {
                result = MEDIA_NOT_KEPT;
                break;
            }
            File part = null;
            try {
                VaultDiag.media("fetch", (nativePlayback ? "native_" : "resolved_") + found.kind);
                VaultMediaFormat format;
                String actualKind;
                try (InputStream in = VaultMedia.open(address)) {
                    if (in == null) {
                        VaultDiag.media("fetch", "unavailable");
                        continue;
                    }
                    VaultMediaFormat.Payload payload = VaultMediaFormat.probe(in, found.kind);
                    if (payload == null || !payload.format.accepts(found.kind)) {
                        VaultDiag.media("identify", payload == null ? "unknown_format" : "rejected_" + payload.format.name());
                        result = MEDIA_FAILED;
                        continue;
                    }
                    format = payload.format;
                    actualKind = format.kind(found.kind);
                    VaultDiag.media("identify", format.name());
                    if (!mediaWanted(actualKind)) {
                        result = MEDIA_NOT_KEPT;
                        continue;
                    }
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("media directory");
                    part = File.createTempFile("pending-", ".part", dir);
                    sealStream(payload.bytes, part);
                }
                VaultDiag.media("encryption", "sealed");
                synchronized (LOCK) {
                    SQLiteDatabase db = db(context);
                    if (!mayPersistLocked(db, rowId, nativePlayback)) return;
                    if (captureBlocked() || !mediaWanted(actualKind)) {
                        result = MEDIA_NOT_KEPT;
                        break;
                    }
                    String oldName = "";
                    if (nativePlayback) {
                        try (Cursor row = db.query("entries", new String[] {"media_name"}, "row_id=?",
                            new String[] {String.valueOf(rowId)}, null, null, null, "1")) {
                            if (row.moveToFirst()) oldName = row.getString(0);
                        }
                    }
                    if (!part.renameTo(target)) throw new IOException("publish failed");
                    try {
                        ContentValues values = new ContentValues();
                        values.put("media_status", MEDIA_STORED);
                        values.put("media_name", target.getName());
                        values.put("media_kind", format.mime);
                        values.put("media_bytes", target.length());
                        values.put("kind", kindForShape(actualKind));
                        if (db.update("entries", values, "row_id=?", new String[] {String.valueOf(rowId)}) != 1) {
                            throw new IOException("entry disappeared");
                        }
                    } catch (Exception failure) {
                        target.delete();
                        throw failure;
                    }
                    if (oldName != null && oldName.matches("[0-9a-f]+") && !oldName.equals(target.getName())) {
                        new File(dir, oldName).delete();
                    }
                }
                VaultDiag.media("persist", "stored");
                return;
            } catch (Exception failure) {
                result = isStorageFull(failure) ? MEDIA_STORAGE_FULL : MEDIA_FAILED;
                VaultDiag.media("persist", MEDIA_STORAGE_FULL.equals(result) ? "storage_full" : "read_or_write_error");
                if (MEDIA_STORAGE_FULL.equals(result)) break;
            } finally {
                if (part != null) part.delete();
            }
        }
        synchronized (LOCK) {
            SQLiteDatabase db = db(context);
            if (liveEntryLocked(db, rowId) && !MEDIA_STORED.equals(mediaStatusLocked(db, rowId))) {
                updateMediaStatusLocked(db, rowId, result, "", found.kind, 0);
            }
        }
    }

    /** Whether a failure chain says the disk is full (ENOSPC), as opposed to a network or format failure. */
    static boolean isStorageFull(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof android.system.ErrnoException
                && ((android.system.ErrnoException) cause).errno == android.system.OsConstants.ENOSPC) return true;
            String message = cause.getMessage();
            if (message != null && (message.contains("No space left") || message.contains("ENOSPC"))) return true;
        }
        return false;
    }

    // --- Recalls --------------------------------------------------------------------------------------------

    /** A recall the hooks saw. Queued like a capture; the mark itself never creates an entry. */
    static void markRecalled(String id) {
        if (id == null || id.isEmpty()) return;
        String kept = MessageLog.clip(id, MAX_ID_CHARS);
        try {
            WRITER.execute(() -> markRecalledNow(kept));
        } catch (RuntimeException rejected) {
            Settings.hookFailedPrivately(KEY, "Can't queue a recall for the vault", rejected);
        }
    }

    /** Marks the entry for [id] Recalled on the calling thread. Returns whether an entry was there to mark. */
    static boolean markRecalledNow(String id) {
        Context context = Settings.appContext;
        if (context == null || id == null || id.isEmpty()) return false;
        synchronized (LOCK) {
            try {
                ContentValues values = new ContentValues();
                values.put("status", STATUS_RECALLED);
                SQLiteDatabase db = db(context);
                int changed = db.update("entries", values, "msg_id=? AND status!=?",
                    new String[] {id, STATUS_RECALLED});
                if (changed == 0) {
                    try (Cursor aliases = db.query("vault_video_identity", new String[] {"row_id"},
                        "notification_id=? OR playback_id=?", new String[] {id, id}, null, null, null, "2")) {
                        if (aliases.moveToFirst() && aliases.getCount() == 1) {
                            changed = db.update("entries", values, "row_id=? AND status!=?",
                                new String[] {String.valueOf(aliases.getLong(0)), STATUS_RECALLED});
                        }
                    }
                }
                return changed > 0;
            } catch (Throwable failure) {
                Settings.hookFailedPrivately(KEY, "Can't mark a recalled message in the vault", failure);
                return false;
            }
        }
    }

    // --- Reads ----------------------------------------------------------------------------------------------

    private static final String[] COLUMNS = {"row_id", "msg_id", "thread", "sender", "chat", "sender_id", "kind",
        "body", "edited_from", "sent_at", "received_at", "expires_at", "status", "source", "media_name",
        "media_kind", "media_status", "media_bytes", "diag"};

    private static Entry readEntry(Cursor row) {
        return new Entry(row.getLong(0), row.getString(1), row.getString(2) == null ? "" : row.getString(2),
            openText(row.getBlob(3)), openText(row.getBlob(4)), row.getLong(5), row.getString(6),
            openText(row.getBlob(7)), openText(row.getBlob(8)), row.getLong(9), row.getLong(10), row.getLong(11),
            row.getString(12), row.getString(13), row.getString(14), row.getString(15), row.getString(16),
            row.getLong(17), row.getString(18));
    }

    /**
     * The vault's entries, newest first, narrowed by [filter], by [thread] when it isn't null, and by [query]
     * matching the words, sender or chat. Expired entries are purged first and are never returned, whatever the
     * purge schedule has or hasn't done.
     */
    static List<Entry> entries(Filter filter, String thread, String query, long now) {
        Context context = Settings.appContext;
        List<Entry> out = new ArrayList<>();
        if (context == null) return out;
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = db(context);
                purgeLocked(context, db, now);
                StringBuilder where = new StringBuilder("expires_at>?");
                List<String> args = new ArrayList<>();
                args.add(String.valueOf(now));
                if (filter == Filter.RECALLED) where.append(" AND status='").append(STATUS_RECALLED).append("'");
                else if (filter == Filter.MEDIA) where.append(" AND kind IN ('").append(KIND_PHOTO).append("','").append(KIND_VIDEO).append("')");
                else if (filter == Filter.STICKERS_GIFS) where.append(" AND kind IN ('").append(KIND_STICKER).append("','").append(KIND_GIF).append("')");
                else if (filter == Filter.AUDIO_FILES) where.append(" AND kind IN ('").append(KIND_AUDIO).append("','").append(KIND_FILE).append("')");
                if (thread != null) { where.append(" AND thread=?"); args.add(thread); }
                Cursor rows = db.query("entries", COLUMNS, where.toString(),
                    args.toArray(new String[0]), null, null, "received_at DESC");
                try {
                    String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
                    while (rows.moveToNext()) {
                        Entry entry = readEntry(rows);
                        if (!needle.isEmpty()
                            && !entry.text.toLowerCase(java.util.Locale.ROOT).contains(needle)
                            && !entry.sender.toLowerCase(java.util.Locale.ROOT).contains(needle)
                            && !entry.chat.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
                        out.add(entry);
                    }
                } finally { rows.close(); }
            } catch (Throwable failure) {
                Settings.hookFailedPrivately(KEY, "Can't read the vault", failure);
            }
        }
        return out;
    }

    /** The chats that have live entries, as (thread, chat name, count) rows, busiest first. */
    static List<String[]> conversations(long now) {
        Context context = Settings.appContext;
        List<String[]> out = new ArrayList<>();
        if (context == null) return out;
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = db(context);
                purgeLocked(context, db, now);
                Cursor rows = db.query("entries", new String[] {"thread", "chat", "COUNT(*)"},
                    "expires_at>?", new String[] {String.valueOf(now)}, "thread", null, "COUNT(*) DESC");
                try {
                    while (rows.moveToNext()) {
                        out.add(new String[] {rows.getString(0) == null ? "" : rows.getString(0),
                            openText(rows.getBlob(1)), String.valueOf(rows.getLong(2))});
                    }
                } finally { rows.close(); }
            } catch (Throwable failure) {
                Settings.hookFailedPrivately(KEY, "Can't list the vault's chats", failure);
            }
        }
        return out;
    }

    static Stats stats(long now) {
        Context context = Settings.appContext;
        if (context == null) return new Stats(0, 0, 0, 0, 0, 0);
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = db(context);
                purgeLocked(context, db, now);
                int entries = 0, recalled = 0, media = 0;
                long mediaBytes = 0, next = 0;
                Cursor rows = db.query("entries",
                    new String[] {"COUNT(*)", "SUM(status='RECALLED')", "SUM(media_status='STORED')",
                        "COALESCE(SUM(media_bytes),0)", "COALESCE(MIN(expires_at),0)"},
                    "expires_at>?", new String[] {String.valueOf(now)}, null, null, null);
                try {
                    if (rows.moveToFirst()) {
                        entries = rows.getInt(0);
                        recalled = rows.getInt(1);
                        media = rows.getInt(2);
                        mediaBytes = rows.getLong(3);
                        next = rows.getLong(4);
                    }
                } finally { rows.close(); }
                File dbFile = context.getDatabasePath(DB_NAME);
                return new Stats(entries, recalled, media, mediaBytes, dbFile == null ? 0 : dbFile.length(), next);
            } catch (Throwable failure) {
                Settings.hookFailedPrivately(KEY, "Can't measure the vault", failure);
                return new Stats(0, 0, 0, 0, 0, 0);
            }
        }
    }

    // --- Expiry and clearing ----------------------------------------------------------------------------------

    /** Starts the hourly purge once per process. Settings calls it at startup when the control is installed. */
    static void scheduleExpiry() {
        if (PURGE_SCHEDULED.compareAndSet(false, true)) {
            WRITER.scheduleWithFixedDelay(Vault72h::purgeNow, PURGE_DELAY_MS, PURGE_PERIOD_MS, TimeUnit.MILLISECONDS);
        }
    }

    static void purgeNow() {
        Context context = Settings.appContext;
        if (context == null) return;
        synchronized (LOCK) {
            try {
                purgeLocked(context, db(context), System.currentTimeMillis());
            } catch (Throwable failure) {
                // Caught here because a periodic task that throws is never run again.
                Settings.hookFailedPrivately(KEY, "Can't expire old messages in the vault", failure);
            }
        }
    }

    /** Deletes expired rows and their media files. The caller holds {@link #LOCK}. Returns how many went. */
    static int purgeLocked(Context context, SQLiteDatabase db, long now) {
        List<String> media = new ArrayList<>();
        Cursor rows = db.query("entries", new String[] {"media_name"}, "expires_at<=?",
            new String[] {String.valueOf(now)}, null, null, null);
        try {
            while (rows.moveToNext()) if (rows.getString(0) != null && !rows.getString(0).isEmpty()) media.add(rows.getString(0));
        } finally { rows.close(); }
        int removed = db.delete("entries", "expires_at<=?", new String[] {String.valueOf(now)});
        if (removed > 0) pruneVideoIdentities(db);
        for (String name : media) {
            deletePlaybackFiles(context, name);
            File file = mediaFile(context, name);
            if (file != null && file.exists() && !file.delete()) {
                Settings.hookFailedPrivately(KEY, "Can't delete an expired vault media file", new IOException("delete failed: " + name));
            }
        }
        return removed;
    }

    private static void deletePlaybackFiles(Context context, String name) {
        File[] files = context.getCacheDir().listFiles((dir, file) -> file.startsWith("vault_" + name)
            && file.matches("vault_[0-9a-f]{64}([_.].*)"));
        if (files != null) for (File file : files) file.delete();
    }

    /** Deletes the whole archive at once: every row, every media file, and both keys, so nothing can be reopened. */
    static boolean clearAll() {
        Context context = Settings.appContext;
        if (context == null) return false;
        synchronized (LOCK) {
            boolean cleared = true;
            DEFERRED_MEDIA.clear();
            deletePlaybackFiles(context, "");
            try {
                SQLiteDatabase db = db(context);
                db.delete("entries", null, null);
                pruneVideoIdentities(db);
            } catch (Throwable failure) {
                cleared = false;
                Settings.hookFailedPrivately(KEY, "Can't clear the vault", failure);
            }
            File dir = new File(context.getFilesDir(), MEDIA_DIR);
            String[] names = dir.list();
            if (names != null) for (String name : names) cleared &= new File(dir, name).delete();
            if (dir.exists()) cleared &= dir.delete();
            try { vault().forget(); } catch (Throwable failure) { cleared = false; }
            // Through the seam, so a swapped-in key is forgotten its own way; one never materialised in this
            // process still has its keystore entry dropped directly.
            try {
                MediaKeys keys = mediaKeys;
                if (keys != null) keys.forget();
                else forgetKey(MEDIA_KEY_ALIAS);
            } catch (Throwable failure) { cleared = false; }
            vault = null;
            mediaKeys = null;
            return cleared;
        }
    }

    /**
     * A repatch without this control leaves the database and media behind, and the settings screen can't clear
     * them any more. Settings calls this at startup then, so the vault doesn't outlive the control.
     */
    static java.util.concurrent.Future<?> dropLeftovers(Context context) {
        boolean dbExists = context.getDatabasePath(DB_NAME).exists();
        boolean mediaExists = new File(context.getFilesDir(), MEDIA_DIR).exists();
        if (!dbExists && !mediaExists) return null;
        return WRITER.submit(() -> {
            synchronized (LOCK) {
                try { db(context).delete("entries", null, null); } catch (Throwable ignored) {}
                resetForTests();
                context.deleteDatabase(DB_NAME);
                File dir = new File(context.getFilesDir(), MEDIA_DIR);
                String[] names = dir.list();
                if (names != null) for (String name : names) new File(dir, name).delete();
                dir.delete();
                try { new MessageLog.KeystoreVault(KEY_ALIAS).forget(); } catch (Throwable ignored) {}
                try { forgetKey(MEDIA_KEY_ALIAS); } catch (Throwable ignored) {}
            }
        });
    }
}
