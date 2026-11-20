package app.hushmessenger.extension;

import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Test-build diagnostics for incoming media (HANDOFF §17–§18): one redacted line per message the notification
 * hook hands over, saying what media rode along — whether a video is there, whether it is ephemeral and of what
 * type and state, and the SHAPE of each of the video's addresses: scheme, host family, path length, file kind,
 * and the NAMES of its query parameters. Never the address itself, never a parameter value, never message
 * content: a signed media address is a credential, and this log exists to be sent to whoever is analysing the
 * build. Off by default, a sub-switch under Message log, and read-only — it changes nothing about the message.
 * The point is one question: when a view-once video arrives, what does this surface actually see?
 *
 * <p>The v2 line also separates what v1 ran together (HANDOFF §20): for the video and the ephemeral marker it
 * says whether the field itself is missing from the attachment ("absent"), there but holding nothing ("null"),
 * or holding data ("present"); how many address fields the video's class declares against how many are set;
 * and how many of the attachment's fields hold anything at all, which is what tells a hollow shell — media
 * data never handed to the notification flow — from a full attachment whose media fields alone are empty.
 * Still only states and counts: no value of any field is ever written.
 */
final class MediaDiagnostics {
    private MediaDiagnostics() {}

    /** The switch under Message log in Privacy. Off until a test build asks for it. */
    static final String KEY = "media_diagnostics";
    static final String TAG = "HushMediaDiag";

    private static final String ATTACHMENT = "com.facebook.messaging.model.attachment.Attachment";
    private static final String AUDIO_DATA = "com.facebook.messaging.model.attachment.AudioData";
    private static final String IMAGE_DATA = "com.facebook.messaging.model.attachment.ImageData";
    private static final String VIDEO_DATA = "com.facebook.messaging.model.attachment.VideoData";
    private static final String EPHEMERAL_DATA = "com.facebook.messaging.model.attachment.EphemeralMediaData";
    private static final String EPHEMERAL_TYPE = "com.facebook.messaging.model.attachment.EphemeralMediaType";
    private static final String EPHEMERAL_STATE = "com.facebook.messaging.model.attachment.EphemeralMediaState";
    private static final String URI = "android.net.Uri";

    /** Where an observation goes. Tests swap it. */
    interface Sink {
        void log(String line);
    }

    static volatile Sink sink = line -> Log.i(TAG, line);

    static boolean wanted() {
        SharedPreferences prefs = Settings.preferences;
        return prefs != null && Settings.running() && prefs.getBoolean(KEY, false);
    }

    /** Called with every message the notification hook sees. Never throws, and reads nothing while off. */
    static void observe(Object message) {
        if (message == null) return;
        try {
            if (!wanted()) return;
            sink.log(describe(message));
        } catch (Throwable error) {
            Settings.hookFailedPrivately(KEY, "Can't describe a message for media diagnostics", error);
        }
    }

    /**
     * The one line for [message]. Attachments are found the way MessageLogAttachment finds them — the message's
     * list fields, by class name — because on an obfuscated build nothing here has a usable name.
     */
    static String describe(Object message) throws Exception {
        int attachments = 0;
        boolean sawList = false;
        Probe probe = new Probe();
        for (Field field : message.getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            Object held = field.get(message);
            if (!(held instanceof List)) continue;
            sawList = true;
            for (Object element : (List<?>) held) {
                if (element == null || !ATTACHMENT.equals(element.getClass().getName())) continue;
                attachments++;
                probe.merge(probe(element));
            }
        }
        StringBuilder line = new StringBuilder("media-diag v2 path=notification dir=incoming");
        line.append(" attachments=").append(sawList ? String.valueOf(attachments) : "unknown");
        line.append(" video=").append(probe.video ? "yes" : "no");
        line.append(" videoField=").append(probe.videoField);
        line.append(" videoData=").append(probe.videoData);
        line.append(" ephemeral=").append(probe.ephemeral);
        line.append(" ephemeralField=").append(probe.ephemeralField);
        line.append(" ephemeralData=").append(probe.ephemeralData);
        line.append(" audioData=").append(probe.audioData);
        line.append(" imageData=").append(probe.imageData);
        line.append(" uriFields=").append(probe.uriFields < 0 ? "na" : String.valueOf(probe.uriFields));
        line.append(" uriSet=").append(probe.uriSet);
        line.append(" filled=").append(attachments == 0 ? "na" : probe.filledRefs + "/" + probe.totalRefs);
        line.append(" uris=[");
        for (int i = 0; i < probe.addresses.size(); i++) {
            if (i > 0) line.append(' ');
            line.append(probe.addresses.get(i));
        }
        return line.append(']').toString();
    }

    /**
     * What a message's attachments carry, as states a line can print without printing a value. A field's state
     * is "na" until an attachment is seen, then "absent" (the class declares no field of that type), "null"
     * (the field is there and holds nothing) or "present". Merging keeps the most informative state, so one
     * populated attachment answers for a message whose other attachments are shells.
     */
    static final class Probe {
        boolean video;
        String videoField = "na";
        String videoData = "na";
        String ephemeral = "absent";
        String ephemeralField = "na";
        String ephemeralData = "na";
        String audioData = "na";
        String imageData = "na";
        /** Address fields the video's class declares, or -1 while no video field has been seen. */
        int uriFields = -1;
        /** Address fields actually holding a value, across every video seen. */
        int uriSet;
        /** Reference fields on the attachments, and how many of them hold anything. */
        int filledRefs;
        int totalRefs;
        final List<String> addresses = new ArrayList<>();

        void merge(Probe other) {
            video |= other.video;
            videoField = stronger(videoField, other.videoField);
            videoData = stronger(videoData, other.videoData);
            ephemeralField = stronger(ephemeralField, other.ephemeralField);
            if ("present".equals(other.ephemeralData)) ephemeral = other.ephemeral;
            ephemeralData = stronger(ephemeralData, other.ephemeralData);
            audioData = stronger(audioData, other.audioData);
            imageData = stronger(imageData, other.imageData);
            if (other.uriFields >= 0) uriFields = other.uriFields;
            uriSet += other.uriSet;
            filledRefs += other.filledRefs;
            totalRefs += other.totalRefs;
            addresses.addAll(other.addresses);
        }

        private static String stronger(String a, String b) {
            return rank(b) > rank(a) ? b : a;
        }

        private static int rank(String state) {
            switch (state) {
                case "present": return 3;
                case "null": return 2;
                case "absent": return 1;
                default: return 0;
            }
        }
    }

    /** The states of one attachment. Visible to tests, which can hand it shapes no message fixture can carry. */
    static Probe probe(Object attachment) throws Exception {
        Probe probe = new Probe();
        for (Field field : attachment.getClass().getDeclaredFields()) {
            if (field.getType().isPrimitive()) continue;
            probe.totalRefs++;
            try {
                field.setAccessible(true);
                if (field.get(attachment) != null) probe.filledRefs++;
            } catch (RuntimeException unreadable) {
                // A field that won't open counts as holding nothing; the rest of the shape still answers.
            }
        }
        Field video = fieldOf(attachment, VIDEO_DATA);
        probe.videoField = video == null ? "absent" : "present";
        if (video == null) {
            probe.videoData = "na";
        } else {
            probe.uriFields = uriFieldCount(video.getType());
            Object data = read(video, attachment);
            probe.videoData = data == null ? "null" : "present";
            if (data != null) {
                probe.video = true;
                for (Field address : data.getClass().getDeclaredFields()) {
                    if (!URI.equals(address.getType().getName())) continue;
                    address.setAccessible(true);
                    Object value = address.get(data);
                    if (value != null) {
                        probe.uriSet++;
                        probe.addresses.add(redact(value.toString()));
                    }
                }
            }
        }
        Field ephemeral = fieldOf(attachment, EPHEMERAL_DATA);
        probe.ephemeralField = ephemeral == null ? "absent" : "present";
        if (ephemeral == null) {
            probe.ephemeralData = "na";
        } else {
            Object data = read(ephemeral, attachment);
            probe.ephemeralData = data == null ? "null" : "present";
            if (data != null) probe.ephemeral = ephemeralName(data);
        }
        probe.audioData = dataState(attachment, AUDIO_DATA);
        probe.imageData = dataState(attachment, IMAGE_DATA);
        return probe;
    }

    /** The first field on [holder] declared as [type], or null when its class declares none. */
    private static Field fieldOf(Object holder, String type) {
        for (Field field : holder.getClass().getDeclaredFields()) {
            if (type.equals(field.getType().getName())) return field;
        }
        return null;
    }

    private static Object read(Field field, Object holder) throws Exception {
        field.setAccessible(true);
        return field.get(holder);
    }

    /** "absent" when [holder]'s class declares no [type] field, "null" when it holds nothing, else "present". */
    private static String dataState(Object holder, String type) throws Exception {
        Field field = fieldOf(holder, type);
        if (field == null) return "absent";
        return read(field, holder) == null ? "null" : "present";
    }

    /** How many address fields [videoType] declares, counted off the type so a null value still answers. */
    private static int uriFieldCount(Class<?> videoType) {
        int count = 0;
        for (Field field : videoType.getDeclaredFields()) {
            if (URI.equals(field.getType().getName())) count++;
        }
        return count;
    }

    /** "type:VIEW_ONCE,state:UNOPENED" — the ephemeral data's own enum names, which are protocol constants. */
    private static String ephemeralName(Object data) throws Exception {
        String type = "?", state = "?";
        for (Field field : data.getClass().getDeclaredFields()) {
            String kind = field.getType().getName();
            if (!EPHEMERAL_TYPE.equals(kind) && !EPHEMERAL_STATE.equals(kind)) continue;
            field.setAccessible(true);
            Object value = field.get(data);
            if (value instanceof Enum) {
                if (EPHEMERAL_TYPE.equals(kind)) type = ((Enum<?>) value).name();
                else state = ((Enum<?>) value).name();
            }
        }
        return "type:" + type + ",state:" + state;
    }

    /**
     * An address reduced to its shape. The raw string never reaches the output: not the host, not the path, and
     * above all not a query value — the signature and expiry in a media address are credentials, so only the
     * parameter NAMES are kept, which is all telling progressive MP4 from DASH ever needs.
     */
    static String redact(String address) {
        try {
            Uri uri = Uri.parse(address);
            String scheme = uri.getScheme();
            List<String> segments = uri.getPathSegments();
            TreeSet<String> names = new TreeSet<>();
            try {
                names.addAll(uri.getQueryParameterNames());
            } catch (Throwable unsupported) {
                // An opaque address has no query to name; the rest of the shape still says something.
            }
            return "{scheme:" + (scheme == null ? "none" : scheme)
                + ",host:" + hostFamily(uri.getHost())
                + ",seg:" + segments.size()
                + ",ext:" + extension(segments)
                + ",q:[" + String.join("+", names) + "]}";
        } catch (Throwable unparseable) {
            return "{unparseable}";
        }
    }

    /** Which family a host belongs to, never the host itself. */
    static String hostFamily(String host) {
        if (host == null || host.isEmpty()) return "none";
        if (host.endsWith("fbcdn.net")) return "fbcdn";
        if (host.endsWith("facebook.com") || host.endsWith("fb.com")) return "facebook";
        if (host.endsWith("cdninstagram.com")) return "instagram";
        return "other";
    }

    /** The file kind the last path segment names, when it's a kind this log distinguishes. */
    static String extension(List<String> segments) {
        if (segments.isEmpty()) return "none";
        String last = segments.get(segments.size() - 1);
        int dot = last.lastIndexOf('.');
        if (dot < 0 || dot == last.length() - 1) return "none";
        String ext = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "mp4": case "mpd": case "m4a": case "aac": case "mov":
            case "jpg": case "jpeg": case "png": case "webp": case "gif":
                return ext;
            default:
                return "other";
        }
    }
}
