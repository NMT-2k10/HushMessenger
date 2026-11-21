package app.hushmessenger.extension;

import android.app.Dialog;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Temporary Vault's screen: the actions under its switch in settings (Open and Clear, plus the keep
 * switches the settings page adds beside them) and the sheet that lists what the vault holds — all messages,
 * only the recalled ones, photos and videos, stickers and GIFs, audio and files, or one conversation — with a
 * search box and the storage statistics. Every entry shows who sent it, when it arrived, when its 72 hours run
 * out and whether it was recalled or edited, and a kept photo, video or voice clip opens from here.
 */
final class VaultSheet {
    private static final int PAGE = 50;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "HushVaultScreen");
        thread.setDaemon(true);
        return thread;
    });

    private final SettingsActivity page;
    private Dialog dialog;
    private LinearLayout list;
    private Vault72h.Filter filter = Vault72h.Filter.ALL;
    private boolean byChat;
    private String thread;
    private String query = "";
    private int shown = PAGE;
    private long generation;
    private MediaPlayer player;

    VaultSheet(SettingsActivity page) {
        this.page = page;
    }

    /** Open and Clear under the vault's switch. The keep switches are added by the settings page itself. */
    void addActions(LinearLayout content) {
        LinearLayout actions = page.ui.row();
        Button view = page.ui.button(page.text.get("vault_view"));
        view.setTag("vault_view");
        view.setOnClickListener(clicked -> showVault());
        actions.addView(view);
        Button clear = page.ui.destructiveButton(page.text.get("vault_clear"));
        clear.setTag("vault_clear");
        clear.setOnClickListener(clicked -> clearVault());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-2, -2);
        clearParams.setMarginStart(page.ui.dp(8));
        actions.addView(clear, clearParams);
        page.ui.add(content, actions, 8);
    }

    void destroy() {
        stopAudio();
        if (dialog != null) dialog.dismiss();
    }

    private void stopAudio() {
        if (player != null) {
            try { player.stop(); } catch (RuntimeException ignored) {}
            player.release();
            player = null;
        }
    }

    void showVault() {
        if (page.isFinishing() || page.isDestroyed() || dialog != null) return;
        filter = Vault72h.Filter.ALL;
        byChat = false;
        thread = null;
        query = "";
        shown = PAGE;
        SettingsDialog sheet = new SettingsDialog(page, page.ui, page.text,
            page.text.get("vault_title"), page.text.get("vault_hint"));
        dialog = sheet.dialog;
        dialog.setOnDismissListener(dismissed -> {
            stopAudio();
            if (dialog == dismissed) {
                dialog = null;
                list = null;
                generation++;
            }
        });
        EditText search = page.ui.field(page.text.get("vault_search_hint"), 0, true);
        search.setTag("vault_search");
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                query = s == null ? "" : s.toString();
                shown = PAGE;
                load();
            }
        });
        sheet.body.addView(search);
        LinearLayout filters = page.ui.row();
        filters.setTag("vault_filters");
        addFilter(filters, "vault_filter_all", Vault72h.Filter.ALL, false);
        addFilter(filters, "vault_filter_recalled", Vault72h.Filter.RECALLED, false);
        addFilter(filters, "vault_filter_media", Vault72h.Filter.MEDIA, false);
        addFilter(filters, "vault_filter_stickers", Vault72h.Filter.STICKERS_GIFS, false);
        addFilter(filters, "vault_filter_audio_files", Vault72h.Filter.AUDIO_FILES, false);
        addFilter(filters, "vault_filter_conversations", null, true);
        LinearLayout filterScroll = new LinearLayout(page);
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(page);
        scroller.addView(filters);
        sheet.body.addView(scroller);
        list = page.ui.column();
        list.setTag("vault_list");
        sheet.body.addView(list);
        Button clear = page.ui.destructiveButton(page.text.get("vault_clear"));
        clear.setTag("vault_dialog_clear");
        clear.setOnClickListener(clicked -> clearVault());
        sheet.action(clear);
        Button close = page.ui.primaryButton(page.text.get("vault_close"));
        close.setTag("vault_dialog_close");
        close.setOnClickListener(clicked -> dialog.dismiss());
        sheet.action(close);
        sheet.show();
        load();
    }

    private void addFilter(LinearLayout row, String key, Vault72h.Filter target, boolean chats) {
        Button button = page.ui.button(page.text.get(key));
        button.setTag(key);
        button.setOnClickListener(clicked -> {
            byChat = chats;
            if (target != null) filter = target;
            shown = PAGE;
            load();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(page.ui.dp(8));
        row.addView(button, params);
    }

    /** Reads the vault off the main thread and renders what it finds. */
    private void load() {
        if (list == null) return;
        final long mine = ++generation;
        final Vault72h.Filter wanted = filter;
        final boolean chats = byChat;
        final String keptThread = thread, keptQuery = query;
        final int keepShown = shown;
        WORK.execute(() -> {
            long now = System.currentTimeMillis();
            Vault72h.Stats stats = Vault72h.stats(now);
            List<Vault72h.Entry> entries = chats ? null : Vault72h.entries(wanted, keptThread, keptQuery, now);
            List<String[]> conversations = chats ? Vault72h.conversations(now) : null;
            page.runOnUiThread(() -> {
                if (dialog == null || mine != generation || list == null) return;
                render(stats, entries, conversations, keepShown, now);
            });
        });
    }

    private void render(Vault72h.Stats stats, List<Vault72h.Entry> entries, List<String[]> conversations,
                        int keepShown, long now) {
        list.removeAllViews();
        String next = stats.nextExpiryAt > 0 ? when(stats.nextExpiryAt) : "—";
        TextView statsLine = page.ui.text(page.text.get("vault_stats", stats.entries, stats.recalled,
            stats.mediaStored, next), 13, page.ui.muted, false);
        statsLine.setTag("vault_stats");
        page.ui.add(list, statsLine, 4);
        if (conversations != null) {
            if (thread != null) {
                Button all = page.ui.button(page.text.get("vault_all_chats"));
                all.setTag("vault_all_chats");
                all.setOnClickListener(clicked -> { thread = null; byChat = false; load(); });
                page.ui.add(list, all, 8);
            }
            if (conversations.isEmpty()) {
                page.ui.add(list, page.ui.text(page.text.get("vault_empty"), 14, page.ui.muted, false), 12);
                return;
            }
            for (String[] chat : conversations) {
                Button row = page.ui.button((chat[1].isEmpty() ? chat[0] : chat[1]) + " · "
                    + page.text.get("vault_conversation_count", Integer.parseInt(chat[2])));
                row.setTag("vault_conversation");
                final String picked = chat[0];
                row.setOnClickListener(clicked -> { thread = picked; byChat = false; load(); });
                page.ui.add(list, row, 8);
            }
            return;
        }
        if (entries == null || entries.isEmpty()) {
            // An empty category is not an empty vault: with entries kept, the words say this part holds
            // nothing, so a filter with no matches never reads as if nothing was archived at all.
            page.ui.add(list, page.ui.text(
                page.text.get(stats.entries > 0 ? "vault_empty_category" : "vault_empty"),
                14, page.ui.muted, false), 12);
            return;
        }
        int limit = Math.min(keepShown, entries.size());
        for (int i = 0; i < limit; i++) page.ui.add(list, card(entries.get(i), now), 10);
        if (entries.size() > limit) {
            Button more = page.ui.button(page.text.get("vault_more"));
            more.setTag("vault_more");
            more.setOnClickListener(clicked -> { shown += PAGE; load(); });
            page.ui.add(list, more, 10);
        }
    }

    private View card(Vault72h.Entry entry, long now) {
        LinearLayout card = page.ui.panel();
        card.setTag("vault_entry");
        String who = !entry.chat.isEmpty() ? entry.chat : !entry.sender.isEmpty() ? entry.sender
            : entry.senderId > 0 ? String.valueOf(entry.senderId) : page.text.get("vault_unknown_sender");
        if (!entry.sender.isEmpty() && !entry.chat.isEmpty()) who = entry.sender + " · " + entry.chat;
        page.ui.add(card, page.ui.text(who, 15, page.ui.text, true), 0);
        StringBuilder meta = new StringBuilder(kindLabel(entry.kind));
        meta.append(" · ").append(when(entry.receivedAt));
        meta.append(" · ").append(page.text.get("vault_expires", when(entry.expiresAt)));
        if (entry.recalled()) meta.append(" · ").append(page.text.get("vault_recalled"));
        if (Vault72h.STATUS_EDITED.equals(entry.status)) meta.append(" · ").append(page.text.get("vault_edited"));
        page.ui.add(card, page.ui.text(meta.toString(), 13, page.ui.muted, false), 4);
        if (!entry.text.isEmpty()) page.ui.add(card, page.ui.text(entry.text, 15, page.ui.text, false), 6);
        if (!entry.editedFrom.isEmpty()) {
            page.ui.add(card, page.ui.text(page.text.get("vault_edited_from", entry.editedFrom), 13, page.ui.muted, false), 4);
        }
        if (!Vault72h.MEDIA_NONE.equals(entry.mediaStatus)) {
            LinearLayout media = page.ui.row();
            page.ui.add(media, page.ui.text(mediaLabel(entry.mediaStatus), 13, page.ui.muted, false), 0);
            if (entry.hasMedia()) {
                Button open = page.ui.button(page.text.get("vault_open_media"));
                open.setTag("vault_open_media");
                open.setOnClickListener(clicked -> openMedia(entry));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.setMarginStart(page.ui.dp(8));
                media.addView(open, params);
                if (Vault72h.KIND_PHOTO.equals(entry.kind) || Vault72h.KIND_VIDEO.equals(entry.kind)) {
                    Button save = page.ui.button(page.text.get("vault_save_gallery"));
                    save.setTag("vault_save_gallery");
                    save.setOnClickListener(clicked -> saveToGallery(entry));
                    LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-2, -2);
                    saveParams.setMarginStart(page.ui.dp(8));
                    media.addView(save, saveParams);
                }
            }
            page.ui.add(card, media, 6);
        }
        if (!entry.diag.isEmpty() && !entry.hasMedia()) {
            page.ui.add(card, page.ui.text(entry.diag, 11, page.ui.muted, false), 2);
        }
        return card;
    }

    /** Copies a kept photo or video into the phone's gallery; the copy there outlives the vault's 72 hours. */
    private void saveToGallery(Vault72h.Entry entry) {
        Vault72h.exportToGallery(page, entry, saved -> page.runOnUiThread(() ->
            page.feedback(page.text.get(saved ? "vault_saved_gallery" : "vault_save_gallery_failed"),
                Toast.LENGTH_LONG)));
    }

    private String kindLabel(String kind) {
        if (Vault72h.KIND_PHOTO.equals(kind)) return page.text.get("vault_photo");
        if (Vault72h.KIND_VIDEO.equals(kind)) return page.text.get("vault_video");
        if (Vault72h.KIND_AUDIO.equals(kind)) return page.text.get("vault_audio");
        if (Vault72h.KIND_STICKER.equals(kind)) return page.text.get("vault_sticker");
        if (Vault72h.KIND_GIF.equals(kind)) return page.text.get("vault_gif");
        if (Vault72h.KIND_FILE.equals(kind)) return page.text.get("vault_file");
        if (Vault72h.KIND_EMOJI.equals(kind)) return page.text.get("vault_emoji");
        if (Vault72h.KIND_ATTACHMENT.equals(kind)) return page.text.get("vault_attachment");
        return page.text.get("vault_text");
    }

    private String mediaLabel(String status) {
        if (Vault72h.MEDIA_NO_ADDRESS.equals(status)) return page.text.get("vault_media_no_address");
        if (Vault72h.MEDIA_NOT_KEPT.equals(status)) return page.text.get("vault_media_not_kept");
        if (Vault72h.MEDIA_STORED.equals(status)) return page.text.get("vault_media_stored");
        if (Vault72h.MEDIA_UNAVAILABLE.equals(status)) return page.text.get("vault_media_unavailable");
        if (Vault72h.MEDIA_STORAGE_FULL.equals(status)) return page.text.get("vault_media_storage_full");
        if (Vault72h.MEDIA_PENDING.equals(status)) return page.text.get("vault_media_pending");
        return page.text.get("vault_media_failed");
    }

    private String when(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time));
    }

    /** Opens a kept photo full-screen, or plays a kept video or voice clip, all from the sealed file. */
    private void openMedia(Vault72h.Entry entry) {
        if (Vault72h.KIND_PHOTO.equals(entry.kind) || Vault72h.KIND_STICKER.equals(entry.kind)
            || Vault72h.KIND_GIF.equals(entry.kind)) {
            WORK.execute(() -> {
                byte[] bytes = Vault72h.openMedia(page, entry);
                android.graphics.drawable.Drawable drawable = null;
                android.graphics.Bitmap picture = null;
                if (bytes != null && android.os.Build.VERSION.SDK_INT >= 28) {
                    // ImageDecoder plays animated GIF and WebP files as they were sent, not as a still
                    // first frame, and decodes still photos the same as the bitmap path did.
                    try {
                        drawable = android.graphics.ImageDecoder.decodeDrawable(
                            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)),
                            (decoder, info, source) -> {
                                int width = info.getSize().getWidth();
                                int height = info.getSize().getHeight();
                                int longest = Math.max(width, height);
                                if (longest > 2048) {
                                    decoder.setTargetSize(width * 2048 / longest, height * 2048 / longest);
                                }
                            });
                    } catch (Throwable undecodable) {
                        drawable = null;
                    }
                }
                if (bytes != null && drawable == null) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 1;
                    while (bounds.outWidth / (options.inSampleSize * 2) >= 1024) options.inSampleSize *= 2;
                    picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                }
                final android.graphics.drawable.Drawable shownDrawable = drawable;
                final android.graphics.Bitmap shown = picture;
                page.runOnUiThread(() -> {
                    if (shownDrawable == null && shown == null) {
                        page.feedback(page.text.get("vault_media_unavailable"), Toast.LENGTH_LONG);
                        return;
                    }
                    SettingsDialog sheet = new SettingsDialog(page, page.ui, page.text,
                        page.text.get("vault_title"), "");
                    ImageView view = new ImageView(page);
                    if (shownDrawable != null) {
                        view.setImageDrawable(shownDrawable);
                        if (shownDrawable instanceof android.graphics.drawable.AnimatedImageDrawable) {
                            ((android.graphics.drawable.AnimatedImageDrawable) shownDrawable).start();
                        }
                    } else {
                        view.setImageBitmap(shown);
                    }
                    view.setAdjustViewBounds(true);
                    sheet.body.addView(view);
                    Button close = page.ui.primaryButton(page.text.get("vault_close"));
                    close.setOnClickListener(clicked -> sheet.dialog.dismiss());
                    sheet.action(close);
                    sheet.show();
                });
            });
            return;
        }
        File playable = Vault72h.mediaCacheFile(page, entry);
        if (playable == null) {
            page.feedback(page.text.get("vault_media_unavailable"), Toast.LENGTH_LONG);
            return;
        }
        if (Vault72h.KIND_VIDEO.equals(entry.kind)) {
            SettingsDialog sheet = new SettingsDialog(page, page.ui, page.text, page.text.get("vault_title"), "");
            VideoView video = new VideoView(page);
            video.setVideoPath(playable.getAbsolutePath());
            video.setMediaController(new android.widget.MediaController(page));
            sheet.body.addView(video, new LinearLayout.LayoutParams(-1, page.ui.dp(320)));
            Button close = page.ui.primaryButton(page.text.get("vault_close"));
            close.setOnClickListener(clicked -> { video.stopPlayback(); sheet.dialog.dismiss(); });
            sheet.action(close);
            sheet.show();
            video.start();
            return;
        }
        // A voice clip or another audio file: play it, tapping again stops it.
        stopAudio();
        try {
            player = new MediaPlayer();
            player.setDataSource(playable.getAbsolutePath());
            player.setOnCompletionListener(done -> stopAudio());
            player.prepare();
            player.start();
        } catch (Exception unreadable) {
            stopAudio();
            page.feedback(page.text.get("vault_media_failed"), Toast.LENGTH_LONG);
        }
    }

    private void clearVault() {
        WORK.execute(() -> {
            boolean cleared = Vault72h.clearAll();
            page.runOnUiThread(() -> {
                page.feedback(page.text.get(cleared ? "vault_cleared" : "vault_clear_failed"), Toast.LENGTH_LONG);
                if (dialog != null) load();
            });
        });
    }
}
