package app.hushmessenger.extension;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The files the message log keeps for a photo, video or voice message. A message arrives carrying the address of its own
 * media, and Messenger fetches that only when the photo is opened or the clip is played, so an unsend before then takes
 * the media with it and leaves the log's entry with nothing to show. With {@link MessageLog#KEEP_PHOTOS},
 * {@link MessageLog#KEEP_VIDEOS} or {@link MessageLog#KEEP_VOICE} on, the file is fetched as the message is logged and
 * sealed with the log's own key, so it stays in the log's screen afterwards.
 *
 * Everything is read off the message object the notification hook hands over, by type rather than by field name: the
 * message carries attachments, an attachment carries its audio, image or video, the audio has exactly one address, the
 * image has maps of addresses with a size against each, and the video has its own address beside its thumbnail's. Those
 * type names are the same in every build, while the field names are not, so nothing here has to be recorded per build.
 */
final class MessageLogAttachment {
    private MessageLogAttachment() {}

    private static final String ATTACHMENT = "com.facebook.messaging.model.attachment.Attachment";
    private static final String AUDIO_DATA = "com.facebook.messaging.model.attachment.AudioData";
    private static final String IMAGE_DATA = "com.facebook.messaging.model.attachment.ImageData";
    private static final String IMAGE_MAP = "com.facebook.messaging.model.attachment.AttachmentImageMap";
    private static final String IMAGE_URL = "com.facebook.messaging.model.attachment.ImageUrl";
    private static final String VIDEO_DATA = "com.facebook.messaging.model.attachment.VideoData";
    private static final String EPHEMERAL_DATA = "com.facebook.messaging.model.attachment.EphemeralMediaData";
    private static final String MEDIA_RESOURCE = "com.facebook.ui.media.attachments.model.MediaResource";
    private static final String URI = "android.net.Uri";

    /** The kind {@link #foundForVault} reports for a message whose media is view-once (ephemeral). */
    static final String EPHEMERAL = "ephemeral";

    /** What an entry's media is when the kept file is a voice clip. A photo keeps the empty kind a picture always had. */
    static final String AUDIO = "audio";
    /** A photo's kind. The log has always kept photos, so its files stay the ones with no kind written against them. */
    static final String PHOTO = "";
    /** A video's kind. */
    static final String VIDEO = "video";
    /** A clip bigger than this isn't kept. A minute of Messenger's voice audio is a few hundred kilobytes. */
    static final int MAX_AUDIO_BYTES = 4 * 1024 * 1024;
    /** A photo bigger than this isn't kept. Messenger sends a camera photo at around a megabyte. */
    static final int MAX_PHOTO_BYTES = 8 * 1024 * 1024;
    /**
     * A video bigger than this isn't kept. Messenger shrinks what it sends, so a short one is a few megabytes, and the
     * log holds its files to MessageLogMedia.MAX_TOTAL_BYTES in any case.
     */
    static final int MAX_VIDEO_BYTES = 16 * 1024 * 1024;
    private static final int TIMEOUT_MS = 20_000;
    /** Files waiting to be fetched. Past it the oldest waiting is dropped, and its message is still logged. */
    private static final int MAX_WAITING = 16;

    /**
     * The list fields of each class that has been looked at, so the walk runs once per build and not once per message.
     * Weak on the class, since a key held strongly would pin Messenger's class loader.
     */
    private static final Map<Class<?>, Field[]> FIELDS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Field[] NONE = new Field[0];

    private static final ThreadPoolExecutor FETCHER = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(MAX_WAITING), runnable -> {
            Thread thread = new Thread(runnable, "HushMessageLogMedia");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());

    /** Takes the address over from the download, so a test can answer without a network. */
    static java.util.function.Function<String, byte[]> download = MessageLogAttachment::get;
    /** The same for asking how big a file is without fetching it. */
    static java.util.function.ToLongFunction<String> measure = MessageLogAttachment::length;

    /**
     * What to fetch for one message and what the file will be. A video arrives with its own address and its thumbnail's,
     * and nothing on the message says which is which, so both are carried here and told apart off Messenger's thread.
     */
    static final class Found {
        final String[] addresses;
        final String kind;

        Found(String address, String kind) {
            this(new String[] {address}, kind);
        }

        Found(String[] addresses, String kind) {
            this.addresses = addresses;
            this.kind = kind;
        }

        /** The one address, for a kind that only ever has one. */
        String address() {
            return addresses[0];
        }
    }

    /**
     * The media on [message] worth keeping, or null when it carries none, every switch is off, or the message object
     * isn't shaped the way this build's was. Runs on Messenger's own thread, so it only reads fields and never fetches.
     */
    static Found found(Object message) {
        if (message == null || !(kept(AUDIO) || kept(PHOTO) || kept(VIDEO))) return null;
        try {
            for (Field field : fields(message.getClass())) {
                Object attachments = field.get(message);
                if (!(attachments instanceof List)) continue;
                for (Object attachment : (List<?>) attachments) {
                    Found found = foundOn(attachment);
                    if (found != null) return found;
                }
            }
        } catch (Throwable unreadable) {
            // The message is still logged, without its media.
            Settings.hookFailedPrivately(MessageLog.KEY, "Can't read a message's media address", unreadable);
        }
        return null;
    }

    /**
     * The media on [message] the Temporary Vault may keep, or null when it carries none worth keeping. Unlike
     * {@link #found}, the message log's keep switches don't decide here — the vault has its own — but an
     * attachment that carries ephemeral (view-once) data is never the vault's: when a message's only attachment
     * is one, the answer is a {@link Found} of kind {@link #EPHEMERAL} with no addresses, which tells the caller
     * to skip the message entirely. Runs on Messenger's own thread, so it only reads fields and never fetches.
     */
    static Found foundForVault(Object message) {
        if (message == null) return null;
        boolean ephemeralOnly = false;
        try {
            for (Field field : fields(message.getClass())) {
                Object attachments = field.get(message);
                if (!(attachments instanceof List)) continue;
                for (Object attachment : (List<?>) attachments) {
                    if (attachment == null || !ATTACHMENT.equals(attachment.getClass().getName())) continue;
                    if (only(attachment, EPHEMERAL_DATA) != null) {
                        ephemeralOnly = true;
                        continue;
                    }
                    Object audio = only(attachment, AUDIO_DATA);
                    String audioAddress = audio == null ? null : signed(only(audio, URI));
                    if (audioAddress != null) return new Found(audioAddress, AUDIO);
                    Object video = only(attachment, VIDEO_DATA);
                    String[] videoAddresses = video == null ? null : addressesOn(video);
                    if (videoAddresses != null) return new Found(videoAddresses, VIDEO);
                    Object image = only(attachment, IMAGE_DATA);
                    String imageAddress = image == null ? null : largest(image);
                    if (imageAddress != null) return new Found(imageAddress, PHOTO);
                }
            }
        } catch (Throwable unreadable) {
            // The message is still archived, without its media.
            Settings.hookFailedPrivately(Vault72h.KEY, "Can't read a message's media address for the vault", unreadable);
        }
        return ephemeralOnly ? new Found(new String[0], EPHEMERAL) : null;
    }

    /**
     * Everything the vault's scan of one message found: the media worth fetching, the kind of media the message
     * carries even when nothing on it could be fetched, whether the message is view-once, and a structural
     * fingerprint of what the message object held. The fingerprint counts fields and names media types and
     * address schemes only — never an address, a key or any other value the message carried.
     */
    static final class VaultRead {
        final Found found;
        final String shapeKind;
        final boolean ephemeral;
        final String diag;

        VaultRead(Found found, String shapeKind, boolean ephemeral, String diag) {
            this.found = found;
            this.shapeKind = shapeKind;
            this.ephemeral = ephemeral;
            this.diag = diag;
        }
    }

    /**
     * The vault's full scan of [message]. Where {@link #foundForVault} stops at an attachment's own typed data,
     * this also reads the message's media resources — the handles Messenger's thread view itself resolves a
     * photo, video or voice clip from, encrypted chats included — and the attachment's extra string map, and it
     * reports the media's kind and a fingerprint even when no usable address was on the message, so a capture
     * can be filed under the right kind with the reason showing. A message carrying view-once data is reported
     * ephemeral and its media resources are never read: view-once media is not the vault's, on any surface.
     */
    static VaultRead inspectForVault(Object message) {
        if (message == null) return new VaultRead(null, null, false, "");
        StringBuilder diag = new StringBuilder();
        try {
            boolean ephemeralSeen = false;
            String shape = null;
            Found found = null;
            int attachments = 0;
            for (Field field : fields(message.getClass())) {
                Object held = field.get(message);
                if (!(held instanceof List)) continue;
                for (Object item : (List<?>) held) {
                    if (item == null || !ATTACHMENT.equals(item.getClass().getName())) continue;
                    attachments++;
                    if (only(item, EPHEMERAL_DATA) != null) {
                        ephemeralSeen = true;
                        continue;
                    }
                    Object audio = only(item, AUDIO_DATA);
                    Object video = only(item, VIDEO_DATA);
                    Object image = only(item, IMAGE_DATA);
                    if (shape == null) {
                        if (audio != null) shape = AUDIO;
                        else if (video != null) shape = VIDEO;
                        else if (image != null) shape = PHOTO;
                    }
                    if (attachments == 1) {
                        diag.append("att=1");
                        if (image != null) diag.append(" img=1 maps=").append(mapSizes(image));
                        if (video != null) diag.append(" vid=1");
                        if (audio != null) diag.append(" aud=1");
                    }
                    if (found == null) {
                        String audioAddress = audio == null ? null : signed(only(audio, URI));
                        if (audioAddress != null) found = new Found(audioAddress, AUDIO);
                        else {
                            String[] videoAddresses = video == null ? null : addressesOn(video);
                            if (videoAddresses != null) found = new Found(videoAddresses, VIDEO);
                            else {
                                String imageAddress = image == null ? null : largest(image);
                                if (imageAddress != null) found = new Found(imageAddress, PHOTO);
                                else {
                                    String mapped = mappedAddress(item, shape);
                                    if (mapped != null && shape != null) found = new Found(mapped, shape);
                                }
                            }
                        }
                    }
                }
            }
            if (ephemeralSeen && found == null) {
                return new VaultRead(null, null, true, diag.toString());
            }
            if (!ephemeralSeen) {
                Found resource = resourceMedia(message, diag);
                if (resource != null) {
                    if (shape == null) shape = resource.kind;
                    if (found == null) found = resource;
                }
            }
            return new VaultRead(found, shape, false, diag.toString());
        } catch (Throwable unreadable) {
            Settings.hookFailedPrivately(Vault72h.KEY, "Can't scan a message's media for the vault", unreadable);
            return new VaultRead(null, null, false, diag.toString());
        }
    }

    /** The entry counts of an image's size maps, as "a,b" — how much of the photo the message itself carried. */
    private static String mapSizes(Object image) throws Exception {
        StringBuilder sizes = new StringBuilder();
        for (Field field : image.getClass().getDeclaredFields()) {
            if (!IMAGE_MAP.equals(field.getType().getName())) continue;
            field.setAccessible(true);
            Object map = field.get(image);
            Object held = map == null ? null : only(map, Map.class);
            if (sizes.length() > 0) sizes.append(',');
            sizes.append(held instanceof Map ? ((Map<?, ?>) held).size() : -1);
        }
        return sizes.length() == 0 ? "-" : sizes.toString();
    }

    /** An https address among an attachment's extra string map values, or null when it holds none. */
    private static String mappedAddress(Object attachment, String shape) throws Exception {
        if (shape == null) return null;
        for (Field field : attachment.getClass().getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            Object held = field.get(attachment);
            if (!(held instanceof Map)) continue;
            for (Object value : ((Map<?, ?>) held).values()) {
                String address = signed(value);
                if (address != null) return address;
            }
        }
        return null;
    }

    /**
     * The media on the message's own media resources, the handles the thread view resolves media from. A
     * resource names its kind in its type enum (the encrypted kinds included), and carries its addresses as
     * Uris of any scheme — a web address to fetch, or a file or content address on the phone that Messenger
     * has already fetched and decrypted, which the vault may copy like any file of its own app. Only counts,
     * the kind's name and the schemes go to [diag]; no address ever does.
     */
    private static Found resourceMedia(Object message, StringBuilder diag) throws Exception {
        Found best = null;
        int resources = 0;
        for (Field field : fields(message.getClass())) {
            Object held = field.get(message);
            if (!(held instanceof List)) continue;
            for (Object item : (List<?>) held) {
                if (item == null || !MEDIA_RESOURCE.equals(item.getClass().getName())) continue;
                resources++;
                String kind = null;
                boolean waveform = false;
                List<String> addresses = new ArrayList<>();
                StringBuilder schemes = new StringBuilder();
                for (Field part : item.getClass().getDeclaredFields()) {
                    part.setAccessible(true);
                    Object value = part.get(item);
                    if (value == null) continue;
                    if (part.getType().isEnum()) {
                        String name = ((Enum<?>) value).name();
                        if (name.contains("VIDEO")) kind = VIDEO;
                        else if (name.contains("AUDIO")) kind = AUDIO;
                        else if (name.contains("PHOTO")) kind = PHOTO;
                        if (resources == 1) diag.append(" mrk=").append(name);
                    } else if (part.getType().getName().endsWith("WaveformData")) {
                        waveform = true;
                    } else if (URI.equals(part.getType().getName())) {
                        String address = value.toString();
                        String scheme = schemeOf(address);
                        if (scheme == null) continue;
                        if (!addresses.contains(address)) addresses.add(address);
                        if (schemes.indexOf(scheme) < 0) {
                            if (schemes.length() > 0) schemes.append('+');
                            schemes.append(scheme);
                        }
                    }
                }
                if (kind == null && waveform) kind = AUDIO;
                if (resources == 1) diag.append(" mr=1 uris=").append(schemes.length() == 0 ? "-" : schemes);
                if (best == null && kind != null && !addresses.isEmpty()) {
                    best = new Found(addresses.toArray(new String[0]), kind);
                }
            }
        }
        if (resources > 1) diag.append(" mr=").append(resources);
        return best;
    }

    /** The scheme of an address the vault may use — a web address, or a file or content address on the phone. */
    private static String schemeOf(String address) {
        if (address.startsWith("https://")) return "https";
        if (address.startsWith("file://") || address.startsWith("file:")) return "file";
        if (address.startsWith("content://")) return "content";
        return null;
    }

    /** Whether media of [kind] is being kept: the log itself on and usable, and the switch under it on. */
    static boolean kept(String kind) {
        android.content.SharedPreferences prefs = Settings.preferences;
        String key = AUDIO.equals(kind) ? MessageLog.KEEP_VOICE : VIDEO.equals(kind) ? MessageLog.KEEP_VIDEOS : MessageLog.KEEP_PHOTOS;
        return prefs != null && prefs.getBoolean(key, false) && Settings.wouldUse(MessageLog.KEY);
    }

    /** The list fields of [type], which is where attachments sit. Every list is looked at, since none is named here. */
    private static Field[] fields(Class<?> type) {
        Field[] known = FIELDS.get(type);
        if (known != null) return known;
        List<Field> lists = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
            } catch (RuntimeException sealed) {
                continue;
            }
            lists.add(field);
        }
        Field[] found = lists.isEmpty() ? NONE : lists.toArray(NONE);
        FIELDS.put(type, found);
        return found;
    }

    /** What's worth keeping on [attachment], or null when it isn't an attachment or carries nothing this log keeps. */
    private static Found foundOn(Object attachment) throws Exception {
        if (attachment == null || !ATTACHMENT.equals(attachment.getClass().getName())) return null;
        if (kept(AUDIO)) {
            Object audio = only(attachment, AUDIO_DATA);
            String address = audio == null ? null : signed(only(audio, URI));
            if (address != null) return new Found(address, AUDIO);
        }
        if (kept(VIDEO)) {
            Object video = only(attachment, VIDEO_DATA);
            String[] addresses = video == null ? null : addressesOn(video);
            if (addresses != null) return new Found(addresses, VIDEO);
        }
        if (kept(PHOTO)) {
            Object image = only(attachment, IMAGE_DATA);
            String address = image == null ? null : largest(image);
            if (address != null) return new Found(address, PHOTO);
        }
        return null;
    }

    /** Every address [video] carries that's the message's own, or null when it carries none. */
    private static String[] addressesOn(Object video) throws Exception {
        List<String> found = new ArrayList<>();
        for (Field field : video.getClass().getDeclaredFields()) {
            if (!URI.equals(field.getType().getName())) continue;
            field.setAccessible(true);
            String address = signed(field.get(video));
            if (address != null && !found.contains(address)) found.add(address);
        }
        return found.isEmpty() ? null : found.toArray(new String[0]);
    }

    /** The address [held] points at, when it's the signed one the message came with. A file on the phone is left alone. */
    private static String signed(Object held) {
        if (held == null) return null;
        String url = held.toString();
        return url.startsWith("https://") ? url : null;
    }

    /**
     * The biggest picture [image] offers. Messenger hands over maps of the same photo at several sizes, each address with
     * its width and height against it, so the log keeps the one closest to what was sent rather than a preview of it.
     */
    private static String largest(Object image) throws Exception {
        String best = null;
        long widest = -1;
        for (Field field : image.getClass().getDeclaredFields()) {
            if (!IMAGE_MAP.equals(field.getType().getName())) continue;
            field.setAccessible(true);
            Object map = field.get(image);
            if (map == null) continue;
            Object sizes = only(map, Map.class);
            if (!(sizes instanceof Map)) continue;
            for (Object value : ((Map<?, ?>) sizes).values()) {
                if (value == null || !IMAGE_URL.equals(value.getClass().getName())) continue;
                String address = signed(only(value, String.class.getName()));
                if (address == null) continue;
                long area = area(value);
                if (area <= widest) continue;
                widest = area;
                best = address;
            }
        }
        return best;
    }

    /** The pixels of the picture at an address, from the two numbers beside it, or 0 when it doesn't say. */
    private static long area(Object imageUrl) throws Exception {
        long area = 1;
        int numbers = 0;
        for (Field field : imageUrl.getClass().getDeclaredFields()) {
            if (field.getType() != int.class) continue;
            field.setAccessible(true);
            area *= Math.max(0, field.getInt(imageUrl));
            numbers++;
        }
        return numbers == 0 ? 0 : area;
    }

    /** The value of [holder]'s one field declared as [type], or null when it has none, several, or that one is null. */
    private static Object only(Object holder, String type) throws Exception {
        Object found = null;
        for (Field field : holder.getClass().getDeclaredFields()) {
            if (!type.equals(field.getType().getName())) continue;
            if (found != null) return null;
            field.setAccessible(true);
            found = field.get(holder);
            if (found == null) return null;
        }
        return found;
    }

    /** The same by what the field can hold rather than what it's declared as, for a map behind a narrower type. */
    private static Object only(Object holder, Class<?> type) throws Exception {
        Object found = null;
        for (Field field : holder.getClass().getDeclaredFields()) {
            if (!type.isAssignableFrom(field.getType())) continue;
            if (found != null) return null;
            field.setAccessible(true);
            found = field.get(holder);
            if (found == null) return null;
        }
        return found;
    }

    /**
     * Fetches the file at [found] for message [id] in [thread] and seals it, then points the message's entry at it. The
     * fetch waits its turn behind any other, and a failure leaves the message logged without its media.
     */
    static void keep(String thread, String id, Found found) {
        if (thread == null || id == null || id.isEmpty() || found == null) return;
        try {
            FETCHER.execute(() -> keepNow(thread, id, found));
        } catch (RuntimeException rejected) {
            Settings.hookFailedPrivately(MessageLog.KEY, "Can't set up a message's download", rejected);
        }
    }

    /** {@link #keep} on the calling thread. Returns the name it sealed the file under, or "". */
    static String keepNow(String thread, String id, Found found) {
        Context context = Settings.appContext;
        if (context == null || found == null || !kept(found.kind)) return "";
        String name = name(thread, id, found.kind);
        try {
            // Messenger builds one message's notification twice, from the push and again from the sync, and the second
            // reaches here with the same name. One fetch is enough.
            File kept = MessageLogMedia.file(context, name);
            if (kept != null && kept.isFile()) return name;
            byte[] media = fetch(found);
            if (media == null || media.length == 0) return "";
            if (media.length > max(found.kind)) return "";
            File dir = new File(context.getFilesDir(), MessageLogMedia.DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't make " + MessageLogMedia.DIR);
            MessageLog.sealInto(dir, name, media);
            // An entry that already has its media keeps it, and the file just sealed is dropped by the next tidy.
            MessageLog.attachNow(thread, id, name, found.kind);
            return name;
        } catch (Throwable failure) {
            Settings.hookFailedPrivately(MessageLog.KEY, "Can't keep a message's media in the log", failure);
            return "";
        }
    }

    /**
     * The name a [kind] of media for this message is kept under. A photo takes the name the notification's own preview
     * would have taken, so whichever arrives first is the one picture that message has.
     */
    static String name(String thread, String id, String kind) {
        String of = thread + '\u0000' + id;
        return MessageLog.digest(kind.isEmpty() ? of : of + '\u0000' + kind);
    }

    /** The largest a [kind] of media can be and still be kept. */
    static int max(String kind) {
        return AUDIO.equals(kind) ? MAX_AUDIO_BYTES : VIDEO.equals(kind) ? MAX_VIDEO_BYTES : MAX_PHOTO_BYTES;
    }

    /**
     * The media for [found]. Where the server wouldn't say how big either of a video's addresses is, both are fetched and
     * the bigger one wins, since that's the only thing left that tells a video from its own thumbnail.
     */
    static byte[] fetch(Found found) {
        String address = pick(found);
        if (address != null) return download.apply(address);
        byte[] best = null;
        for (String each : found.addresses) {
            byte[] media = download.apply(each);
            if (media == null || (best != null && media.length <= best.length)) continue;
            best = media;
        }
        return best;
    }

    /**
     * Which of [found]'s addresses to fetch. One is the answer where there's one. Where there are two, a video's own and
     * its thumbnail's, the server is asked how big each is: a thumbnail is a fraction of its video, so the bigger one is
     * the video. Null means nothing answered. Runs on the fetcher's thread, since it asks over the network.
     */
    static String pick(Found found) {
        if (found.addresses.length == 1) return found.address();
        String best = null;
        long biggest = -1;
        for (String address : found.addresses) {
            long length = measure.applyAsLong(address);
            if (length <= biggest) continue;
            biggest = length;
            best = address;
        }
        return best;
    }

    /** How many bytes the server says are at [address], or -1 when it won't say. */
    private static long length(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return -1;
            return connection.getContentLengthLong();
        } catch (Exception unreachable) {
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** The bytes at [address], or null when the answer isn't one file the biggest kind's size. The kind's own cap follows. */
    private static byte[] get(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            if (connection.getContentLength() > MAX_VIDEO_BYTES) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_VIDEO_BYTES) return null;
                }
                return out.toByteArray();
            }
        } catch (Exception unreachable) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
