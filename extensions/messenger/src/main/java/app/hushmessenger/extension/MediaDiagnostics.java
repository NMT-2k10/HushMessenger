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
 */
final class MediaDiagnostics {
    private MediaDiagnostics() {}

    /** The switch under Message log in Privacy. Off until a test build asks for it. */
    static final String KEY = "media_diagnostics";
    static final String TAG = "HushMediaDiag";

    private static final String ATTACHMENT = "com.facebook.messaging.model.attachment.Attachment";
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
        boolean video = false;
        String ephemeral = "absent";
        List<String> addresses = new ArrayList<>();
        boolean sawList = false;
        for (Field field : message.getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            Object held = field.get(message);
            if (!(held instanceof List)) continue;
            sawList = true;
            for (Object element : (List<?>) held) {
                if (element == null || !ATTACHMENT.equals(element.getClass().getName())) continue;
                attachments++;
                Object data = only(element, EPHEMERAL_DATA);
                if (data != null) ephemeral = ephemeralName(data);
                Object videoData = only(element, VIDEO_DATA);
                if (videoData != null) {
                    video = true;
                    for (Field address : videoData.getClass().getDeclaredFields()) {
                        if (!URI.equals(address.getType().getName())) continue;
                        address.setAccessible(true);
                        Object value = address.get(videoData);
                        if (value != null) addresses.add(redact(value.toString()));
                    }
                }
            }
        }
        StringBuilder line = new StringBuilder("media-diag v1 path=notification dir=incoming");
        line.append(" attachments=").append(sawList ? String.valueOf(attachments) : "unknown");
        line.append(" video=").append(video ? "yes" : "no");
        line.append(" ephemeral=").append(ephemeral);
        line.append(" uris=[");
        for (int i = 0; i < addresses.size(); i++) {
            if (i > 0) line.append(' ');
            line.append(addresses.get(i));
        }
        return line.append(']').toString();
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

    /** The one field on [holder] whose type is [type], read by type name the way the rest of the log reads them. */
    private static Object only(Object holder, String type) throws Exception {
        for (Field field : holder.getClass().getDeclaredFields()) {
            if (!type.equals(field.getType().getName())) continue;
            field.setAccessible(true);
            return field.get(holder);
        }
        return null;
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
