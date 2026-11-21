package com.facebook.messaging.attachments;

import android.net.Uri;

/** A photo's resolved addresses as Messenger's resolver hands them out: plain Uris, named nothing useful. */
public final class ImageAttachmentUris {
    public Uri A00;
    public Uri A01;
    public Uri A02;
    public Uri A03;
    public Uri A04;
    public Uri A05;

    public static ImageAttachmentUris of(Uri... uris) {
        ImageAttachmentUris out = new ImageAttachmentUris();
        if (uris.length > 0) out.A00 = uris[0];
        if (uris.length > 1) out.A01 = uris[1];
        if (uris.length > 2) out.A02 = uris[2];
        if (uris.length > 3) out.A03 = uris[3];
        if (uris.length > 4) out.A04 = uris[4];
        if (uris.length > 5) out.A05 = uris[5];
        return out;
    }
}
