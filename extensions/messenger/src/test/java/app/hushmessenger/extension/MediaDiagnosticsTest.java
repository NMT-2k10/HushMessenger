package app.hushmessenger.extension;

import android.net.Uri;
import com.facebook.messaging.model.attachment.Attachment;
import com.facebook.messaging.model.attachment.EphemeralMediaData;
import com.facebook.messaging.model.attachment.VideoData;
import com.facebook.messaging.model.messages.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * The diagnostic line a test build writes about incoming media (HANDOFF §18). The whole point is what the line
 * must never contain: a signed media address is a credential, so the tests hold the redaction to account — the
 * shape is described, the secret is not — and hold the switch to its default, which is off and silent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 36})
public class MediaDiagnosticsTest {
    /** A signed playable address and its thumbnail, shaped like the ones Messenger hands over. */
    private static final String PLAYABLE =
        "https://video.xx.fbcdn.net/v/t42.1790-2/512345678_901234567890123_456789_n.mp4?_nc_cat=108&oe=6A1B2C3D&oh=00_AfSECRET_SIGNATURE";
    private static final String THUMB =
        "https://scontent.xx.fbcdn.net/v/t51.2885-15/thumb_n.jpg?_nc_cat=99&oe=6A1B2C3E&oh=00_AfTHUMB_SECRET";

    private final List<String> lines = new ArrayList<>();

    @Before public void open() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.hookErrors.clear();
        CrashGuard.resetForTests();
        lines.clear();
        MediaDiagnostics.sink = lines::add;
    }

    private void enable() {
        Settings.preferences.edit().putBoolean(MediaDiagnostics.KEY, true).commit();
    }

    private static Message viewOnceVideo() {
        Attachment attachment = Attachment.video(new VideoData(Uri.parse(PLAYABLE), Uri.parse(THUMB)));
        attachment.A06 = EphemeralMediaData.viewOnce();
        return Message.with(attachment);
    }

    @Test public void offByDefaultAndSilent() {
        MediaDiagnostics.observe(viewOnceVideo());
        assertTrue(lines.isEmpty());
    }

    @Test public void describesAViewOnceVideoByShapeOnly() {
        enable();
        MediaDiagnostics.observe(viewOnceVideo());
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line, line.contains("attachments=1"));
        assertTrue(line, line.contains("video=yes"));
        assertTrue(line, line.contains("ephemeral=type:VIEW_ONCE,state:UNOPENED"));
        assertTrue(line, line.contains("{scheme:https,host:fbcdn,seg:3,ext:mp4,q:[_nc_cat+oe+oh]}"));
        assertTrue(line, line.contains("ext:jpg"));
    }

    @Test public void neverLeaksTheAddressOrItsSecrets() {
        enable();
        MediaDiagnostics.observe(viewOnceVideo());
        String line = lines.get(0);
        assertFalse(line, line.contains("SECRET_SIGNATURE"));
        assertFalse(line, line.contains("THUMB_SECRET"));
        assertFalse(line, line.contains("6A1B2C3D"));
        assertFalse(line, line.contains("fbcdn.net"));
        assertFalse(line, line.contains("512345678"));
        assertFalse(line, line.contains("_nc_cat=108"));
        assertFalse(line, line.contains("https://"));
    }

    @Test public void aMessageWithoutMediaStillGetsItsLine() {
        enable();
        MediaDiagnostics.observe(Message.with());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("attachments=0"));
        assertTrue(lines.get(0), lines.get(0).contains("video=no"));
        assertTrue(lines.get(0), lines.get(0).contains("ephemeral=absent"));
        assertTrue(lines.get(0), lines.get(0).contains("uris=[]"));
    }

    @Test public void aRegularVideoIsNotEphemeral() {
        enable();
        MediaDiagnostics.observe(Message.with(Attachment.video(new VideoData(Uri.parse(PLAYABLE)))));
        assertTrue(lines.get(0), lines.get(0).contains("video=yes"));
        assertTrue(lines.get(0), lines.get(0).contains("ephemeral=absent"));
    }

    @Test public void redactsFileAndDashShapes() {
        String file = MediaDiagnostics.redact("file:///storage/emulated/0/Movies/holiday.mp4");
        assertTrue(file, file.contains("scheme:file"));
        assertFalse(file, file.contains("storage"));
        assertFalse(file, file.contains("holiday"));
        String dash = MediaDiagnostics.redact("https://video.xx.fbcdn.net/dash/manifest.mpd?oe=ABC&oh=DEF");
        assertTrue(dash, dash.contains("ext:mpd"));
        assertFalse(dash, dash.contains("ABC"));
    }
}
