package com.facebook.messaging.model.attachment;

/**
 * An attachment's ephemeral marker, shaped like Messenger's: a timestamp and the two enums that say what kind of
 * disappearing media it is and what state it's in.
 */
public final class EphemeralMediaData {
    public long A00;
    public EphemeralMediaState A01;
    public EphemeralMediaType A02;

    public static EphemeralMediaData viewOnce() {
        EphemeralMediaData data = new EphemeralMediaData();
        data.A00 = 1_795_000_000_000L;
        data.A01 = EphemeralMediaState.UNOPENED;
        data.A02 = EphemeralMediaType.VIEW_ONCE;
        return data;
    }
}
