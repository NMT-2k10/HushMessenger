package com.facebook.messaging.model.attachment;

/**
 * A view-once attachment's data, shaped like Messenger's: it says what the media is and where it stands, and it
 * never carries an address for it.
 */
public final class EphemeralMediaData {
    public long A00 = 60_000;
    public String A02 = "PHOTO";
}
