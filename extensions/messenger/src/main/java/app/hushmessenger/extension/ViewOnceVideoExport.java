package app.hushmessenger.extension;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Writes a whole video — picture, sound and full duration, not the single frame {@link ViewOnceSave} can take —
 * into the phone's pictures under Pictures/Messenger, for media the user is allowed to keep: their own sent
 * videos, media Messenger itself offers to save, or media the sender agreed to keep (AGENTS §2.2). It is the
 * export half of a full-video save: the caller resolves where the bytes are (a local file, a content Uri, the
 * attachment's own URL) and hands over a {@link Source}; this class only moves bytes — capped, hashed as they
 * pass, proven to be an MP4 before anything is written, and published through MediaStore with IS_PENDING like
 * ViewOnceSave's pictures, with MediaStore's own update count as the confirmation it landed.
 *
 * <p>It is deliberately NOT wired to the view-once viewer yet. The only hook that reaches that viewer today
 * hands the extension a {@link android.view.Window} (Settings.addScreenshotFlags / setScreenshotFlags →
 * ViewOnceSave.offer), which carries neither the media's Uri nor the message's direction, so it cannot tell the
 * user's own video from somebody else's — and this export must never run on media another sender limited. The
 * attachment-level hook that can resolve a Source and prove the message is the user's own is device-stage work
 * (HANDOFF §15); until it exists, nothing in Messenger calls this class.
 */
final class ViewOnceVideoExport {
    private ViewOnceVideoExport() {}

    /** The future sub-switch under `save_view_once`. Off until the hook above exists and asks for it. */
    static final String KEY = "save_view_once_video";
    static final String FOLDER = "Messenger";
    static final int HASH_CHARS = 12;
    /** No view-once clip comes near this; the cap exists so a wrong Source can't fill the phone. */
    static final long MAX_BYTES = 256L * 1024 * 1024;
    static final int BUFFER_BYTES = 64 * 1024;
    /** Stands in for the content hash in the MediaStore name until the bytes have passed through. */
    static final String PENDING_HASH = "000000000000";
    /** Enough of the file head to see the MP4 signature: a 4-byte box size, then "ftyp". */
    static final int HEADER_BYTES = 12;

    /** Where the video's bytes come from. The caller resolves it; tests swap it. */
    interface Source {
        InputStream open() throws IOException;
    }

    /** The MediaStore half: begin a pending item, stream into it, then publish or abandon it. Tests swap it. */
    interface Gallery {
        Uri begin(Context context, String name) throws IOException;
        OutputStream stream(Context context, Uri item) throws IOException;
        /** Reads the written item back, so the copy can be proven a complete MP4 before it is published. */
        InputStream readBack(Context context, Uri item) throws IOException;
        /** Publishes the item under its final name. Throws unless MediaStore confirms the publish. */
        void finish(Context context, Uri item, String name) throws IOException;
        void abandon(Context context, Uri item);
    }

    interface Done {
        void done(Result result);
    }

    /** What an export did. A failure keeps its reason so the caller can say so and report it. */
    static final class Result {
        final boolean saved;
        final long bytes;
        final String hash;
        final String name;
        final String problem;

        private Result(boolean saved, long bytes, String hash, String name, String problem) {
            this.saved = saved;
            this.bytes = bytes;
            this.hash = hash;
            this.name = name;
            this.problem = problem;
        }

        static Result saved(long bytes, String hash, String name) {
            return new Result(true, bytes, hash, name, null);
        }

        static Result failed(String problem) {
            return new Result(false, 0, null, null, problem);
        }
    }

    /** A copied stream: how many bytes passed, and their SHA-256. */
    static final class Copied {
        final long bytes;
        final String hash;

        Copied(long bytes, String hash) {
            this.bytes = bytes;
            this.hash = hash;
        }
    }

    /** The stream grew past the cap the caller allowed. The partial copy is the caller's to abandon. */
    static final class TooBig extends IOException {
        TooBig(long maxBytes) {
            super("Video is larger than the " + maxBytes + "-byte limit");
        }
    }

    static volatile Gallery gallery = new SharedVideos();
    static Executor writes = worker();

    /** Runs the export off the caller's thread and hands the result back on the main one. Never throws. */
    static void save(Context context, Source source, Done done) {
        try {
            writes.execute(() -> {
                Result result = export(context, source, new Date());
                new Handler(Looper.getMainLooper()).post(() -> done.done(result));
            });
        } catch (RejectedExecutionException full) {
            // The queue is a safety valve, not a crash: a full queue means "not now", said like any failure.
            Settings.hookFailedPrivately(KEY, "Can't queue a view-once video export", full);
            new Handler(Looper.getMainLooper()).post(() -> done.done(Result.failed("The export queue is full")));
        }
    }

    /**
     * The export itself, synchronously: open the source first so a dead source never leaves a MediaStore item
     * behind, prove the bytes are an MP4 before any item exists, then copy under the cap and publish under the
     * hashed name — or abandon the item and say why. Never throws.
     */
    static Result export(Context context, Source source, Date when) {
        Uri item = null;
        InputStream in = null;
        try {
            in = source.open();
            byte[] header = new byte[HEADER_BYTES];
            int head = 0;
            while (head < header.length) {
                int read = in.read(header, head, header.length - head);
                if (read == -1) break;
                head += read;
            }
            if (!isMp4(header, head)) {
                // An error page, an encrypted blob or the wrong media entirely must never become a saved ".mp4".
                Settings.hookFailedPrivately(KEY, "Can't export a view-once video",
                    new IOException("The source is not an MP4 stream"));
                return Result.failed("The source is not an MP4 stream");
            }
            item = gallery.begin(context, name(PENDING_HASH, when));
            Copied copied;
            try (OutputStream out = gallery.stream(context, item)) {
                copied = copy(new SequenceInputStream(new ByteArrayInputStream(header, 0, head), in), out, MAX_BYTES);
            }
            // A header alone proves nothing: the published file must scan as a complete MP4 — media box and
            // movie box both present, no box running past the end — or it is abandoned like any other failure.
            try (InputStream back = gallery.readBack(context, item)) {
                if (!hasMovieStructure(back)) throw new IOException("The copied file is not a complete MP4");
            }
            String name = name(copied.hash, when);
            gallery.finish(context, item, name);
            return Result.saved(copied.bytes, copied.hash, name);
        } catch (Throwable error) {
            if (item != null) {
                try {
                    gallery.abandon(context, item);
                } catch (Throwable ignored) {
                    // The failure below is the one worth reporting.
                }
            }
            Settings.hookFailedPrivately(KEY, "Can't export a view-once video", error);
            return Result.failed(String.valueOf(error.getMessage()));
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // The result is already decided; a stubborn stream changes nothing.
                }
            }
        }
    }

    /** An MP4 opens with a box whose type, at bytes 4–7, is "ftyp". Anything else is not a video file. */
    static boolean isMp4(byte[] header, int length) {
        return length >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
    }

    /**
     * Scans EVERY top-level box of a would-be MP4, all the way to the end of the stream: the file is complete
     * only if it holds both an {@code mdat} (the media itself) and a {@code moov} (the index a player needs),
     * and every box is well-formed — a plausible size, fully inside the stream. Finding both boxes early proves
     * nothing about what follows them, so the scan never stops early: a corrupt or truncated tail fails here
     * even though the header looked fine.
     */
    static boolean hasMovieStructure(InputStream in) throws IOException {
        boolean mdat = false, moov = false;
        byte[] head = new byte[16];
        while (true) {
            int got = readAtMost(in, head, 0, 8);
            if (got == 0) break; // The stream ended exactly on a box boundary: the scan is complete.
            if (got < 8) return false; // A box header cut in half at the end of the stream.
            long size = u32(head, 0);
            String type = new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long headerBytes = 8;
            if (size == 1) { // The real size follows as a 64-bit number.
                if (readAtMost(in, head, 8, 8) < 8) return false;
                size = u64(head, 8);
                headerBytes = 16;
            } else if (size == 0) { // The box runs to the end of the file; it must be the last one.
                if (type.equals("mdat")) mdat = true;
                if (type.equals("moov")) moov = true;
                break;
            }
            if (size < headerBytes) return false; // A box smaller than its own header is not a box.
            if (type.equals("mdat")) mdat = true;
            if (type.equals("moov")) moov = true;
            if (!skipFully(in, size - headerBytes)) return false; // The box runs past the end: truncated.
        }
        return mdat && moov;
    }

    private static int readAtMost(InputStream in, byte[] into, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(into, offset + total, length - total);
            if (read == -1) break;
            total += read;
        }
        return total;
    }

    private static boolean skipFully(InputStream in, long bytes) throws IOException {
        long left = bytes;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() == -1) return false;
                skipped = 1;
            }
            left -= skipped;
        }
        return true;
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static long u64(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (bytes[offset + i] & 0xFF);
        return value;
    }

    /**
     * Copies the stream, hashing as it goes, and refuses to write past {@code maxBytes}: a chunk that would
     * cross the cap is never written, so what's already in {@code out} never exceeds it.
     */
    static Copied copy(InputStream in, OutputStream out, long maxBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new IOException(impossible);
        }
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (total + read > maxBytes) throw new TooBig(maxBytes);
            out.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            total += read;
        }
        out.flush();
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        return new Copied(total, hex.substring(0, HASH_CHARS));
    }

    /** Like "View once video 2026-11-19 14.05.33 3f9a0c1d2b4e.mp4". */
    static String name(String hash, Date when) {
        return "View once video " + new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(when)
            + " " + hash + ".mp4";
    }

    /** The phone's shared pictures through MediaStore's video collection, which needs no permission from Android 10 on. */
    @android.annotation.TargetApi(29)
    static final class SharedVideos implements Gallery {
        static String folder() {
            return "Pictures/" + FOLDER;
        }

        @Override public Uri begin(Context context, String name) throws IOException {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder());
            // Other apps don't see it until the whole video is in.
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri item = context.getContentResolver().insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (item == null) throw new IOException("MediaStore made no item");
            return item;
        }

        @Override public OutputStream stream(Context context, Uri item) throws IOException {
            OutputStream out = context.getContentResolver().openOutputStream(item);
            if (out == null) throw new IOException("MediaStore gave no stream");
            return out;
        }

        @Override public InputStream readBack(Context context, Uri item) throws IOException {
            InputStream back = context.getContentResolver().openInputStream(item);
            if (back == null) throw new IOException("MediaStore gave no stream back");
            return back;
        }

        @Override public void finish(Context context, Uri item, String name) throws IOException {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            // The update count is MediaStore's own confirmation that the video is really published.
            if (context.getContentResolver().update(item, done, null, null) != 1) {
                throw new IOException("MediaStore didn't publish the video");
            }
        }

        @Override public void abandon(Context context, Uri item) {
            ContentResolver resolver = context.getContentResolver();
            try {
                resolver.delete(item, null, null);
            } catch (Throwable ignored) {
                // A leftover pending item stays invisible to other apps; the failure was already reported.
            }
        }
    }

    private static Executor worker() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), work -> {
            Thread thread = new Thread(work, "HushViewOnceVideoExport");
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
