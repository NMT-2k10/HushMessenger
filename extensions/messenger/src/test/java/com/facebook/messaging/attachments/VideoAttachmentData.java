package com.facebook.messaging.attachments;

import android.net.Uri;
import com.facebook.ui.media.attachments.model.MediaResource;

/** What Messenger's resolver produces for one video: its playable addresses and the resource it wrapped. */
public final class VideoAttachmentData {
    public Integer A00;
    public Uri A0D;
    public Uri A0E;
    public Uri A0F;
    public MediaResource A0I;

    public static VideoAttachmentData of(Uri playable, MediaResource resource) {
        VideoAttachmentData data = new VideoAttachmentData();
        data.A0D = playable;
        data.A0I = resource;
        return data;
    }
}
