package com.facebook.messaging.model.attachment;

import java.util.LinkedHashMap;

/**
 * A photo's addresses keyed by which size they are. The map sits behind a narrower type than Map, as Messenger's does,
 * so a walk that only matches Map itself is caught.
 */
public final class AttachmentImageMap {
    /** Which kinds of size the map has, so there's something of another type to step over. */
    public String A00 = "preview,full";
    public LinkedHashMap<Object, Object> A01 = new LinkedHashMap<>();
    /** The map's file format, as Messenger's map carries its own. */
    public ImageFormat A08;

    public static AttachmentImageMap of(ImageUrl... urls) {
        AttachmentImageMap map = new AttachmentImageMap();
        for (int i = 0; i < urls.length; i++) map.A01.put("size-" + i, urls[i]);
        return map;
    }

    /** A map of an animated image: its entries, and the format that says what they are. */
    public static AttachmentImageMap gif(ImageUrl... urls) {
        AttachmentImageMap map = of(urls);
        map.A08 = ImageFormat.GIF;
        return map;
    }
}
