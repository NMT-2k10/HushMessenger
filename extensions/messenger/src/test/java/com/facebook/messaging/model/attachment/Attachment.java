package com.facebook.messaging.model.attachment;

/**
 * One attachment on a message, shaped like Messenger's: the media it holds sits in a field named nothing useful, and which
 * kind it is shows only in that field's type.
 */
public final class Attachment {
    public int A00;
    public long A01;
    public AttachmentType A04;
    public AudioData A05;
    public EphemeralMediaData A06;
    public ImageData A07;
    public VideoData A08;
    public java.util.Map<String, String> A09;
    public String A0C;
    public String A0J;
    public String A0L;

    public static Attachment audio(AudioData audio) {
        Attachment attachment = new Attachment();
        attachment.A05 = audio;
        attachment.A0L = "attachment-id";
        return attachment;
    }

    /** A view-once photo: the picture's sizes are there for a thumbnail, and the ephemeral data says what it is. */
    public static Attachment ephemeral(ImageData image) {
        Attachment attachment = new Attachment();
        attachment.A06 = new EphemeralMediaData();
        attachment.A07 = image;
        attachment.A0L = "attachment-id";
        return attachment;
    }

    public static Attachment image(ImageData image) {
        Attachment attachment = new Attachment();
        attachment.A07 = image;
        attachment.A0L = "attachment-id";
        return attachment;
    }

    public static Attachment video(VideoData video) {
        Attachment attachment = new Attachment();
        attachment.A08 = video;
        return attachment;
    }

    /** An attachment that declares its type, with a photo's data under it — a sticker's actual shape. */
    public static Attachment typed(AttachmentType type, ImageData image) {
        Attachment attachment = new Attachment();
        attachment.A04 = type;
        attachment.A07 = image;
        attachment.A0L = "attachment-id";
        return attachment;
    }

    /** An attachment that declares its type and carries no data objects at all — a voice clip's shape in an encrypted chat. */
    public static Attachment typedOnly(AttachmentType type) {
        Attachment attachment = new Attachment();
        attachment.A04 = type;
        attachment.A0L = "attachment-id";
        return attachment;
    }
}
