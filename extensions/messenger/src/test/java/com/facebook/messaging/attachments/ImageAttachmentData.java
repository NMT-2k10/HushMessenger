package com.facebook.messaging.attachments;

import com.facebook.ui.media.attachments.model.MediaResource;

/** What Messenger's resolver produces for one photo: two sets of resolved addresses and the resource it wrapped. */
public final class ImageAttachmentData {
    public Integer A00;
    public String A01;
    public ImageAttachmentUris A05;
    public ImageAttachmentUris A06;
    public MediaResource A09;

    public static ImageAttachmentData of(ImageAttachmentUris uris, MediaResource resource) {
        ImageAttachmentData data = new ImageAttachmentData();
        data.A05 = uris;
        data.A09 = resource;
        return data;
    }
}
