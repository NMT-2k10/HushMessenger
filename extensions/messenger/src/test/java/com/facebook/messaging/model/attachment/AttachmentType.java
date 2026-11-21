package com.facebook.messaging.model.attachment;

/**
 * What an attachment declares itself to be, shaped like Messenger's own type enum: the names are the
 * declaration, and a sticker, an animated image and a voice clip are types of their own even when the
 * attachment's data objects are a photo's — or, for a voice clip in an encrypted chat, absent entirely.
 */
public enum AttachmentType {
    NONE, STICKER, IMAGE, ANIMATED_IMAGE, VIDEO, AUDIO, FILE, XMA,
    EPHEMERAL_IMAGE, EPHEMERAL_VIDEO, SELFIE_STICKER, POWER_UP,
    BROADCAST_CHANNEL_PROMPT_SUBMISSION, THIRD_PARTY_STICKER
}
