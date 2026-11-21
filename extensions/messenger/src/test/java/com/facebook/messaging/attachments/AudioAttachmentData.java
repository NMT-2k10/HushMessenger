package com.facebook.messaging.attachments;

import android.net.Uri;

/** What Messenger's resolver produces for one voice clip: the address it plays from. */
public final class AudioAttachmentData {
    public long A00;
    public Uri A01;
    public String A04;

    public static AudioAttachmentData of(Uri playable) {
        AudioAttachmentData data = new AudioAttachmentData();
        data.A01 = playable;
        return data;
    }
}
