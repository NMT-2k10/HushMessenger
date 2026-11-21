package com.facebook.ui.media.attachments.model;

import android.net.Uri;

/**
 * A media resource the way Messenger's thread view carries one: a type enum that names the kind (the
 * encrypted kinds included), and the media's addresses as Uris under names that say nothing. Nothing here
 * is named the way the extension looks for it.
 */
public final class MediaResource {
    public enum Kind { PHOTO, VIDEO, AUDIO, FILE, OTHER, ENCRYPTED_PHOTO, ENCRYPTED_VIDEO, ENCRYPTED_AUDIO }

    public Kind A0R;
    public Uri A0D;
    public Uri A0E;
    public Uri A0G;
    public String A0h;

    public static MediaResource of(Kind kind, Uri... uris) {
        MediaResource resource = new MediaResource();
        resource.A0R = kind;
        if (uris.length > 0) resource.A0D = uris[0];
        if (uris.length > 1) resource.A0E = uris[1];
        if (uris.length > 2) resource.A0G = uris[2];
        return resource;
    }
}
