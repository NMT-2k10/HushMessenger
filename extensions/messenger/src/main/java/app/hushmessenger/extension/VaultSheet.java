package app.hushmessenger.extension;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.File;
import java.io.FileInputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
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
    private static final int PAGE = 32;
    private static final int MAX_THUMBNAIL_INPUT = 4 * 1024 * 1024;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "HushVaultScreen");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService PLAYBACK = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "HushVaultPlayback");
        thread.setDaemon(true);
        return thread;
    });

    private final SettingsActivity page;
    private Dialog dialog;
    private android.widget.ScrollView scroll;
    private LinearLayout list;
    private TextView total;
    private Button sortButton, moreFilters;
    private final EnumMap<Vault72h.Filter, Button> chips = new EnumMap<>(Vault72h.Filter.class);
    private final List<Vault72h.PageKey> pageStarts = new ArrayList<>();
    private int pageIndex;
    private Vault72h.Filter filter = Vault72h.Filter.ALL;
    private Vault72h.Sort sort = Vault72h.Sort.NEWEST;
    private boolean byChat;
    private String thread;
    private String query = "";
    private volatile long generation;
    private volatile long mediaGeneration;
    private long displayedRevision;
    private boolean loading;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable searchUpdate = this::load;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (dialog == null || !dialog.isShowing()) return;
            if (!loading && !page.isFinishing() && displayedRevision != Vault72h.revision()) load(true);
            ui.postDelayed(this, 750);
        }
    };
    private AudioPreview audioPreview;
    private Dialog mediaDialog;
    private android.widget.TextView captureStatus;

    VaultSheet(SettingsActivity page) {
        this.page = page;
    }

    /** Open and Clear under the vault's switch. The keep switches are added by the settings page itself. */
    void addActions(LinearLayout content) {
        captureStatus = page.ui.text("", 13, page.ui.warning, false);
        page.ui.add(content, captureStatus, 4);
        refreshCaptureStatus();
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

    private String captureBlockCaption() {
        if (CrashGuard.isSafeMode()) return page.text.get("vault_capture_safe_mode");
        if (Settings.preferences != null && Settings.preferences.getBoolean("paused", false)) {
            return page.text.get("vault_capture_paused");
        }
        return "";
    }

    void refreshCaptureStatus() {
        if (captureStatus == null) return;
        String caption = captureBlockCaption();
        captureStatus.setText(caption);
        captureStatus.setVisibility(caption.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    void destroy() {
        if (mediaDialog != null) mediaDialog.dismiss();
        stopAudio();
        if (dialog != null) dialog.dismiss();
    }

    private void stopAudio() {
        if (audioPreview != null) audioPreview.close();
    }

    void stopAudioPreview() {
        mediaGeneration++;
        stopAudio();
    }

    void showVault() {
        if (page.isFinishing() || page.isDestroyed() || dialog != null) return;
        filter = Vault72h.Filter.ALL;
        byChat = false;
        thread = null;
        query = "";
        sort = Vault72h.Sort.NEWEST;
        resetPaging();
        chips.clear();
        SettingsDialog sheet = new SettingsDialog(page, page.ui, page.text,
            page.text.get("vault_title"), "");
        total = page.ui.text("", 13, page.ui.muted, false);
        total.setTag("vault_stats");
        sheet.addHeader(total, 4);
        String blocked = captureBlockCaption();
        if (!blocked.isEmpty()) sheet.addHeader(page.ui.notice(blocked, true), 8);
        dialog = sheet.dialog;
        scroll = sheet.scroll;
        dialog.setOnDismissListener(dismissed -> {
            if (dialog == dismissed) {
                ui.removeCallbacks(refresh);
                ui.removeCallbacks(searchUpdate);
                mediaGeneration++;
                stopAudio();
                if (mediaDialog != null) mediaDialog.dismiss();
                dialog = null;
                scroll = null;
                list = null;
                total = null;
                chips.clear();
                pageStarts.clear();
                sortButton = null;
                moreFilters = null;
                generation++;
            }
        });
        EditText search = page.ui.field(page.text.get("vault_search_hint"), Vault72h.MAX_TEXT_CHARS, true);
        search.setTag("vault_search");
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                query = s == null ? "" : s.toString();
                resetPaging();
                generation++;
                loading = true;
                ui.removeCallbacks(searchUpdate);
                ui.postDelayed(searchUpdate, 220);
            }
        });
        sheet.addHeader(search, 10);
        LinearLayout filters = page.ui.row();
        filters.setTag("vault_filters");
        addFilter(filters, "vault_filter_all", Vault72h.Filter.ALL);
        addFilter(filters, "vault_filter_text", Vault72h.Filter.RECALLED_TEXT);
        addFilter(filters, "vault_photo", Vault72h.Filter.PHOTOS);
        addFilter(filters, "vault_video", Vault72h.Filter.VIDEOS);
        addFilter(filters, "vault_sticker", Vault72h.Filter.STICKERS);
        addFilter(filters, "vault_filter_voice", Vault72h.Filter.VOICE);
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(page);
        scroller.addView(filters);
        sheet.addHeader(scroller, 8);
        LinearLayout options = page.ui.row();
        if (page.ui.largeText) options.setOrientation(LinearLayout.VERTICAL);
        sortButton = page.ui.button(page.text.get("vault_sort_newest"));
        sortButton.setTag("vault_sort");
        sortButton.setOnClickListener(clicked -> {
            PopupMenu menu = new PopupMenu(page, sortButton);
            menu.getMenu().add(0, 1, 0, page.text.get("vault_sort_newest")).setCheckable(true)
                .setChecked(sort == Vault72h.Sort.NEWEST);
            menu.getMenu().add(0, 2, 1, page.text.get("vault_sort_oldest")).setCheckable(true)
                .setChecked(sort == Vault72h.Sort.OLDEST);
            menu.setOnMenuItemClickListener(item -> {
                sort = item.getItemId() == 1 ? Vault72h.Sort.NEWEST : Vault72h.Sort.OLDEST;
                sortButton.setText(item.getTitle());
                resetPaging();
                load();
                return true;
            });
            menu.show();
        });
        moreFilters = page.ui.button(page.text.get("vault_more_filters"));
        moreFilters.setTag("vault_more_filters");
        moreFilters.setOnClickListener(clicked -> showMoreFilters());
        options.addView(sortButton, optionParams(false));
        options.addView(moreFilters, optionParams(true));
        sheet.addHeader(options, 8);
        updateSelectedFilters();
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
        sheet.setFocusableInTouchMode(true);
        sheet.requestFocus();
        load();
        ui.postDelayed(refresh, 750);
    }

    private LinearLayout.LayoutParams optionParams(boolean second) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(page.ui.largeText ? -1 : 0, -2,
            page.ui.largeText ? 0 : 1);
        if (second) {
            if (page.ui.largeText) params.topMargin = page.ui.dp(6);
            else params.setMarginStart(page.ui.dp(8));
        }
        return params;
    }

    private void addFilter(LinearLayout row, String key, Vault72h.Filter target) {
        Button button = page.ui.button(page.text.get(key));
        button.setTag(key);
        button.setOnClickListener(clicked -> selectFilter(target));
        chips.put(target, button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        if (row.getChildCount() > 0) params.setMarginStart(page.ui.dp(8));
        row.addView(button, params);
    }

    private void updateSelectedFilters() {
        chips.forEach((kind, button) -> styleFilter(button, !byChat && kind == filter));
        styleFilter(moreFilters, byChat || !chips.containsKey(filter));
    }

    private void styleFilter(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? page.ui.onAccent : page.ui.heading);
        button.setBackground(selected ? page.ui.interactive(page.ui.accentFill, 0, 12)
            : page.ui.interactive(page.ui.card, page.ui.line, 12, page.ui.heading, 2));
    }

    private void selectFilter(Vault72h.Filter target) {
        byChat = target == null;
        if (target != null) filter = target;
        resetPaging();
        updateSelectedFilters();
        load();
    }

    private void showMoreFilters() {
        PopupMenu menu = new PopupMenu(page, moreFilters);
        String[] keys = {"vault_filter_recalled", "vault_filter_media", "vault_filter_stickers",
            "vault_filter_audio_files", "vault_filter_conversations"};
        Vault72h.Filter[] targets = {Vault72h.Filter.RECALLED, Vault72h.Filter.MEDIA, Vault72h.Filter.STICKERS_GIFS,
            Vault72h.Filter.AUDIO_FILES, null};
        for (int i = 0; i < keys.length; i++) {
            menu.getMenu().add(0, i, i, page.text.get(keys[i])).setCheckable(true)
                .setChecked(byChat ? targets[i] == null : targets[i] == filter);
        }
        menu.setOnMenuItemClickListener(item -> {
            selectFilter(targets[item.getItemId()]);
            return true;
        });
        menu.show();
    }

    private void resetPaging() {
        pageStarts.clear();
        pageStarts.add(null);
        pageIndex = 0;
    }

    /** Reads the vault off the main thread and renders what it finds. */
    private void load() {
        load(false);
    }

    private void load(boolean preserveScroll) {
        if (list == null) return;
        ui.removeCallbacks(searchUpdate);
        final long mine = ++generation;
        loading = true;
        final Vault72h.Filter wanted = filter;
        final boolean chats = byChat;
        final String keptThread = thread, keptQuery = query;
        final Vault72h.Sort wantedSort = sort;
        final Vault72h.PageKey after = pageStarts.get(pageIndex);
        WORK.execute(() -> {
            if (mine != generation) return;
            long revision = Vault72h.revision();
            long now = System.currentTimeMillis();
            Vault72h.Stats stats = Vault72h.stats(now);
            List<Vault72h.Entry> entries = chats ? null
                : Vault72h.entries(wanted, keptThread, keptQuery, wantedSort, after, PAGE + 1, now, () -> mine != generation);
            List<String[]> conversations = chats ? Vault72h.conversations(now) : null;
            page.runOnUiThread(() -> {
                if (dialog == null || mine != generation || list == null) return;
                loading = false;
                displayedRevision = revision;
                String nextExpiry = stats.nextExpiryAt > 0 ? when(stats.nextExpiryAt) : "—";
                total.setText(page.text.get("vault_stats", stats.entries, stats.recalled, stats.mediaStored, nextExpiry));
                int scrollY = preserveScroll ? scroll.getScrollY() : 0;
                render(stats, entries, conversations);
                if (preserveScroll && scrollY > 0) {
                    scroll.post(() -> {
                        if (mine == generation && scroll != null) scroll.scrollTo(0, scrollY);
                    });
                } else if (!preserveScroll) scroll.scrollTo(0, 0);
            });
        });
    }

    private void render(Vault72h.Stats stats, List<Vault72h.Entry> entries, List<String[]> conversations) {
        list.removeAllViews();
        if (thread != null) {
            Button all = page.ui.button(page.text.get("vault_all_chats"));
            all.setTag("vault_all_chats");
            all.setOnClickListener(clicked -> { thread = null; selectFilter(filter); });
            page.ui.add(list, all, 8);
        }
        if (conversations != null) {
            if (conversations.isEmpty()) {
                page.ui.add(list, page.ui.text(page.text.get("vault_empty"), 14, page.ui.muted, false), 12);
                return;
            }
            for (String[] chat : conversations) {
                Button row = page.ui.button((chat[1].isEmpty() ? chat[0] : chat[1]) + " · "
                    + page.text.get("vault_conversation_count", Integer.parseInt(chat[2])));
                row.setTag("vault_conversation");
                final String picked = chat[0];
                row.setOnClickListener(clicked -> { thread = picked; selectFilter(filter); });
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
        } else {
            for (int i = 0; i < Math.min(PAGE, entries.size()); i++) page.ui.add(list, card(entries.get(i)), 10);
        }
        if (pageIndex > 0 || (entries != null && entries.size() > PAGE)) {
            LinearLayout pagination = page.ui.row();
            if (page.ui.largeText) pagination.setOrientation(LinearLayout.VERTICAL);
            if (pageIndex > 0) {
                Button back = page.ui.button(page.text.get("back"));
                back.setTag("vault_previous_page");
                back.setOnClickListener(clicked -> {
                    if (loading) return;
                    pageStarts.remove(pageIndex--);
                    load();
                });
                pagination.addView(back, optionParams(false));
            }
            if (entries != null && entries.size() > PAGE) {
                Button next = page.ui.button(page.text.get("vault_more"));
                next.setTag("vault_more");
                next.setOnClickListener(clicked -> {
                    if (loading) return;
                    pageStarts.add(new Vault72h.PageKey(entries.get(PAGE - 1)));
                    pageIndex++;
                    load();
                });
                pagination.addView(next, optionParams(pagination.getChildCount() > 0));
            }
            page.ui.add(list, pagination, 10);
        }
    }

    private View card(Vault72h.Entry entry) {
        LinearLayout card = page.ui.panel();
        card.setTag("vault_entry");
        TextView heading = page.ui.text(sourceLabel(entry), 16, page.ui.text, true);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        page.ui.add(card, heading, 0);
        StringBuilder meta = new StringBuilder(kindLabel(entry.kind));
        meta.append(" · ").append(when(entry.receivedAt));
        meta.append(" · ").append(page.text.get("vault_expires", when(entry.expiresAt)));
        if (entry.recalled()) meta.append(" · ").append(page.text.get("vault_recalled"));
        if (Vault72h.STATUS_EDITED.equals(entry.status)) meta.append(" · ").append(page.text.get("vault_edited"));
        TextView metadata = page.ui.text(meta.toString(), 13, page.ui.muted, false);
        addKindIcon(metadata, entry.kind);
        page.ui.add(card, metadata, 4);
        if (!entry.text.isEmpty()) {
            TextView preview = page.ui.text(entry.text, 15, page.ui.text, false);
            if (entry.hasRecalledText() || !Vault72h.KIND_TEXT.equals(entry.kind)) {
                preview.setMaxLines(3);
                preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            preview.setTag("vault_preview");
            page.ui.add(card, preview, 6);
        }
        if (entry.hasRecalledText()) {
            Button read = page.ui.button(page.text.get("open"));
            read.setTag("vault_open_text");
            read.setOnClickListener(clicked -> openRecalledText(entry.rowId));
            page.ui.add(card, read, 8);
        }
        if (!entry.editedFrom.isEmpty()) {
            page.ui.add(card, page.ui.text(page.text.get("vault_edited_from", entry.editedFrom), 13, page.ui.muted, false), 4);
        }
        if (entry.hasMedia() && entry.mediaBytes <= MAX_THUMBNAIL_INPUT
            && (Vault72h.KIND_PHOTO.equals(entry.kind) || Vault72h.KIND_STICKER.equals(entry.kind)
                || Vault72h.KIND_GIF.equals(entry.kind))) {
            addThumbnail(card, entry);
        }
        if (!Vault72h.MEDIA_NONE.equals(entry.mediaStatus)) {
            String preview = mediaLabel(entry.mediaStatus);
            if (entry.hasMedia() && entry.mediaBytes > 0) {
                preview += " · " + android.text.format.Formatter.formatShortFileSize(page, entry.mediaBytes);
            }
            page.ui.add(card, page.ui.text(preview, 13, page.ui.muted, false), 6);
            if (entry.hasMedia()) {
                LinearLayout media = page.ui.row();
                Button open = page.ui.button(page.text.get("vault_open_media"));
                open.setTag("vault_open_media");
                open.setOnClickListener(clicked -> openMedia(entry.rowId));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.setMarginStart(page.ui.dp(8));
                media.addView(open, params);
                if (Vault72h.exportable(entry)) {
                    Button save = page.ui.button(page.text.get("vault_save_gallery"));
                    save.setTag("vault_save_gallery");
                    save.setOnClickListener(clicked -> saveToGallery(entry));
                    LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-2, -2);
                    saveParams.setMarginStart(page.ui.dp(8));
                    media.addView(save, saveParams);
                }
                page.ui.add(card, media, 6);
            }
        }
        if (!entry.diag.isEmpty() && !entry.hasMedia()) {
            page.ui.add(card, page.ui.text(entry.diag, 11, page.ui.muted, false), 2);
        }
        return card;
    }

    private void addThumbnail(LinearLayout card, Vault72h.Entry entry) {
        ImageView image = new ImageView(page);
        image.setScaleType(ImageView.ScaleType.FIT_START);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setVisibility(View.GONE);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, page.ui.dp(92));
        size.topMargin = page.ui.dp(8);
        card.addView(image, size);
        int pixels = page.ui.dp(160);
        long request = generation;
        WORK.execute(() -> {
            if (request != generation || entry.expiresAt <= Vault72h.clock.getAsLong()) return;
            byte[] bytes = Vault72h.openMedia(page, entry);
            if (bytes == null || bytes.length == 0) return;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return;
            options.inJustDecodeBounds = false;
            options.inSampleSize = Integer.highestOneBit(Math.max(1, Math.max(options.outWidth, options.outHeight) / pixels));
            android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) return;
            page.runOnUiThread(() -> {
                if (request != generation || entry.expiresAt <= Vault72h.clock.getAsLong()) return;
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
            });
        });
    }

    private String sourceLabel(Vault72h.Entry entry) {
        if (!entry.sender.isEmpty() && !entry.chat.isEmpty() && !entry.sender.equals(entry.chat)) {
            return entry.sender + " · " + entry.chat;
        }
        if (!entry.chat.isEmpty()) return entry.chat;
        if (!entry.sender.isEmpty()) return entry.sender;
        if (entry.senderId > 0) return String.valueOf(entry.senderId);
        return entry.thread.isEmpty() ? page.text.get("vault_unknown_sender") : entry.thread;
    }

    private void addKindIcon(TextView view, String kind) {
        int icon;
        switch (kind) {
            case Vault72h.KIND_TEXT: icon = android.R.drawable.ic_menu_edit; break;
            case Vault72h.KIND_VIDEO: icon = android.R.drawable.ic_media_play; break;
            case Vault72h.KIND_AUDIO: icon = android.R.drawable.ic_btn_speak_now; break;
            case Vault72h.KIND_PHOTO:
            case Vault72h.KIND_STICKER:
            case Vault72h.KIND_GIF: icon = android.R.drawable.ic_menu_gallery; break;
            default: return;
        }
        android.graphics.drawable.Drawable glyph = page.getDrawable(icon).mutate();
        glyph.setTint(page.ui.muted);
        glyph.setBounds(0, 0, page.ui.dp(16), page.ui.dp(16));
        view.setCompoundDrawablePadding(page.ui.dp(6));
        view.setCompoundDrawablesRelative(glyph, null, null, null);
    }

    private void openRecalledText(long rowId) {
        long request = ++mediaGeneration;
        PLAYBACK.execute(() -> {
            if (request != mediaGeneration) return;
            Vault72h.Entry entry = Vault72h.currentRecalledText(page, rowId);
            page.runOnUiThread(() -> {
                if (!currentMediaRequest(request)) return;
                if (entry == null || entry.expiresAt <= Vault72h.clock.getAsLong()) {
                    page.feedback(page.text.get("vault_text_unavailable"), Toast.LENGTH_SHORT);
                    return;
                }
                showRecalledText(entry);
            });
        });
    }

    private void showRecalledText(Vault72h.Entry entry) {
        if (mediaDialog != null) mediaDialog.dismiss();
        stopAudio();
        SettingsDialog detail = new SettingsDialog(page, page.ui, page.text, page.text.get("vault_filter_text"),
            sourceLabel(entry));
        page.ui.add(detail.body, page.ui.text(page.text.get("vault_saved_at", when(entry.receivedAt)),
            13, page.ui.muted, false), 0);
        String recalled = entry.recalledAt > 0 ? page.text.get("vault_recalled_at", when(entry.recalledAt))
            : page.text.get("vault_recalled");
        page.ui.add(detail.body, page.ui.text(recalled, 13, page.ui.muted, false), 4);
        TextView words = page.ui.text(entry.text, 16, page.ui.text, false);
        words.setTag("vault_text_body");
        page.ui.add(detail.body, words, 16);
        Button close = page.ui.button(page.text.get("vault_close"));
        close.setOnClickListener(clicked -> detail.dialog.dismiss());
        detail.action(close);
        Button copy = page.ui.primaryButton(page.text.get("vault_copy_text"));
        copy.setTag("vault_copy_text");
        copy.setOnClickListener(clicked -> {
            if (entry.expiresAt <= Vault72h.clock.getAsLong()) {
                detail.dialog.dismiss();
                return;
            }
            ClipboardManager clipboard = page.getSystemService(ClipboardManager.class);
            if (clipboard == null) return;
            ClipData clip = ClipData.newPlainText(page.text.get("vault_filter_text"), entry.text);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            clipboard.setPrimaryClip(clip);
            if (android.os.Build.VERSION.SDK_INT < 33) {
                page.feedback(page.text.get("vault_text_copied"), Toast.LENGTH_SHORT);
            }
        });
        detail.action(copy);
        Runnable expire = detail.dialog::dismiss;
        detail.dialog.setOnDismissListener(closed -> {
            ui.removeCallbacks(expire);
            if (mediaDialog == closed) mediaDialog = null;
        });
        mediaDialog = detail.dialog;
        detail.show();
        ui.postDelayed(expire, Math.max(0, entry.expiresAt - Vault72h.clock.getAsLong()));
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
    private void openMedia(long rowId) {
        long request = ++mediaGeneration;
        PLAYBACK.execute(() -> {
            if (request != mediaGeneration) return;
            Vault72h.Entry entry = Vault72h.currentMedia(page, rowId);
            if (entry == null) {
                page.runOnUiThread(() -> {
                    if (currentMediaRequest(request)) {
                        page.feedback(page.text.get("vault_media_unavailable"), Toast.LENGTH_LONG);
                    }
                });
                return;
            }
            if (Vault72h.KIND_PHOTO.equals(entry.kind) || Vault72h.KIND_STICKER.equals(entry.kind)
                || Vault72h.KIND_GIF.equals(entry.kind)) {
                openImage(entry, request);
                return;
            }
            File playable = Vault72h.mediaCacheFile(page, entry);
            page.runOnUiThread(() -> {
                if (!currentMediaRequest(request)) {
                    if (playable != null) playable.delete();
                    return;
                }
                if (playable == null) {
                    page.feedback(page.text.get("vault_media_unavailable"), Toast.LENGTH_LONG);
                } else if (Vault72h.KIND_VIDEO.equals(entry.kind)) {
                    playVideo(playable);
                } else {
                    playAudio(playable, entry.expiresAt);
                }
            });
        });
    }

    private void openImage(Vault72h.Entry entry, long request) {
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
            if (!currentMediaRequest(request)) return;
            if (shownDrawable == null && shown == null) {
                page.feedback(page.text.get("vault_media_unavailable"), Toast.LENGTH_LONG);
                return;
            }
            showPicture(shownDrawable, shown);
            VaultDiag.media("viewer", "image_open");
        });
    }

    private boolean currentMediaRequest(long request) {
        return request == mediaGeneration && dialog != null && dialog.isShowing()
            && !page.isFinishing() && !page.isDestroyed();
    }

    private void showPicture(android.graphics.drawable.Drawable drawable, android.graphics.Bitmap picture) {
        if (mediaDialog != null) mediaDialog.dismiss();
        stopAudio();
        Dialog viewer = new Dialog(page, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(page);
        frame.setBackgroundColor(android.graphics.Color.BLACK);
        ImageView image = new ImageView(page);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (drawable != null) image.setImageDrawable(drawable);
        else image.setImageBitmap(picture);
        frame.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));
        Button close = page.ui.primaryButton(page.text.get("vault_close"));
        android.widget.FrameLayout.LayoutParams closeParams =
            new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        closeParams.bottomMargin = page.ui.dp(20);
        frame.addView(close, closeParams);
        close.setOnClickListener(clicked -> viewer.dismiss());
        viewer.setContentView(frame);
        android.graphics.drawable.AnimatedImageDrawable animation = drawable instanceof android.graphics.drawable.AnimatedImageDrawable
            ? (android.graphics.drawable.AnimatedImageDrawable) drawable : null;
        viewer.setOnDismissListener(closed -> {
            if (animation != null) animation.stop();
            if (mediaDialog == closed) mediaDialog = null;
        });
        mediaDialog = viewer;
        viewer.show();
        if (animation != null) animation.start();
    }

    private void playVideo(File playable) {
        if (mediaDialog != null) mediaDialog.dismiss();
        stopAudio();
        SettingsDialog sheet = new SettingsDialog(page, page.ui, page.text, page.text.get("vault_title"), "");
        VideoView video = new VideoView(page);
        video.setMediaController(new android.widget.MediaController(page));
        video.setOnPreparedListener(prepared -> VaultDiag.media("viewer", "video_ready"));
        video.setOnErrorListener((media, what, extra) -> {
            VaultDiag.media("viewer", "video_error");
            page.feedback(page.text.get("vault_media_failed"), Toast.LENGTH_LONG);
            sheet.dialog.dismiss();
            return true;
        });
        sheet.dialog.setOnDismissListener(closed -> {
            video.stopPlayback();
            playable.delete();
            if (mediaDialog == closed) mediaDialog = null;
        });
        mediaDialog = sheet.dialog;
        sheet.body.addView(video, new LinearLayout.LayoutParams(-1, page.ui.dp(320)));
        Button close = page.ui.primaryButton(page.text.get("vault_close"));
        close.setOnClickListener(clicked -> sheet.dialog.dismiss());
        sheet.action(close);
        sheet.show();
        video.setVideoPath(playable.getAbsolutePath());
        video.start();
    }

    private void playAudio(File playable, long expiresAt) {
        if (mediaDialog != null) mediaDialog.dismiss();
        stopAudio();
        audioPreview = new AudioPreview();
        mediaDialog = audioPreview.sheet.dialog;
        audioPreview.open(playable, expiresAt);
    }

    private final class AudioPreview {
        final SettingsDialog sheet = new SettingsDialog(page, page.ui, page.text, page.text.get("vault_audio"), "");
        final TextView time = page.ui.text(page.text.get("vault_media_pending"), 15, page.ui.text, false);
        final SeekBar seekBar = new SeekBar(page);
        final Button toggle = page.ui.primaryButton(page.text.get("link_player_play"));
        final MediaPlayer media = new MediaPlayer();
        final AudioManager audio = page.getSystemService(AudioManager.class);
        final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
        final AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(this::focusChanged, ui)
            .build();
        final Runnable update = this::updateTime;
        final Runnable expire = this::close;
        int duration;
        boolean playing;
        boolean focused;
        boolean dragging;
        boolean seeking;

        void open(File playable, long expiresAt) {
            Button close = page.ui.button(page.text.get("vault_close"));
            toggle.setEnabled(false);
            toggle.setOnClickListener(clicked -> {
                if (playing) pause();
                else start();
            });
            close.setOnClickListener(clicked -> close());
            sheet.dialog.setOnDismissListener(dismissed -> close());
            sheet.dialog.setVolumeControlStream(AudioManager.STREAM_MUSIC);
            seekBar.setEnabled(false);
            seekBar.setContentDescription(page.text.get("vault_audio_seek"));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar bar) {
                    dragging = true;
                }

                @Override public void onProgressChanged(SeekBar bar, int position, boolean fromUser) {
                    if (!fromUser) return;
                    if (dragging) showTime(position);
                    else seek(position);
                }

                @Override public void onStopTrackingTouch(SeekBar bar) {
                    dragging = false;
                    seek(bar.getProgress());
                }
            });
            page.ui.add(sheet.body, time, 0);
            page.ui.add(sheet.body, seekBar, 8);
            sheet.action(close);
            sheet.action(toggle);
            sheet.show();
            ui.postDelayed(expire, Math.max(0L, expiresAt - System.currentTimeMillis()));

            media.setOnErrorListener((player, what, extra) -> {
                fail("audio_error");
                return true;
            });
            media.setOnPreparedListener(prepared -> {
                if (audioPreview != this) return;
                duration = Math.max(0, prepared.getDuration());
                seekBar.setMax(duration);
                seekBar.setKeyProgressIncrement(1000);
                seekBar.setEnabled(duration > 0);
                toggle.setEnabled(true);
                VaultDiag.media("viewer", "audio_ready");
                updateTime();
                start();
            });
            media.setOnCompletionListener(done -> {
                if (audioPreview != this) return;
                playing = false;
                releaseFocus();
                toggle.setText(page.text.get("link_player_replay"));
                ui.removeCallbacks(update);
                if (!dragging) showPosition(duration);
            });
            media.setOnSeekCompleteListener(player -> {
                if (audioPreview != this) return;
                seeking = false;
                updateTime();
            });
            try (FileInputStream input = new FileInputStream(playable)) {
                media.setAudioAttributes(attributes);
                media.setDataSource(input.getFD());
                media.prepareAsync();
            } catch (Exception unreadable) {
                fail("audio_source_error");
            } finally {
                // MediaPlayer owns its descriptor now. Only the sealed copy remains on disk.
                playable.delete();
            }
        }

        private void start() {
            if (audioPreview != this || !toggle.isEnabled()) return;
            try {
                if (audio != null && !focused) {
                    focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                    if (!focused) {
                        VaultDiag.media("viewer", "audio_focus_denied");
                        page.feedback(page.text.get("message_log_clip_missing"), Toast.LENGTH_SHORT);
                        return;
                    }
                }
                media.start();
                playing = true;
                toggle.setText(page.text.get("link_player_pause"));
                VaultDiag.media("viewer", "audio_started");
                updateTime();
            } catch (RuntimeException failure) {
                fail("audio_start_error");
            }
        }

        private void pause() {
            try {
                media.pause();
                playing = false;
                releaseFocus();
                toggle.setText(page.text.get("link_player_play"));
                updateTime();
            } catch (RuntimeException failure) {
                fail("audio_pause_error");
            }
        }

        private void focusChanged(int change) {
            if (audioPreview == this && playing && change < 0) pause();
        }

        private void seek(int position) {
            if (audioPreview != this || !seekBar.isEnabled()) return;
            try {
                seeking = true;
                media.seekTo(position, MediaPlayer.SEEK_CLOSEST);
                if (!playing) toggle.setText(page.text.get("link_player_play"));
                showPosition(position);
                VaultDiag.media("viewer", "audio_seek");
            } catch (RuntimeException failure) {
                fail("audio_seek_error");
            }
        }

        private void updateTime() {
            ui.removeCallbacks(update);
            if (audioPreview != this) return;
            try {
                if (!dragging && !seeking) showPosition(media.getCurrentPosition());
                if (playing) ui.postDelayed(update, 250);
            } catch (RuntimeException failure) {
                fail("audio_position_error");
            }
        }

        private void showPosition(int position) {
            seekBar.setProgress(position);
            showTime(position);
        }

        private void showTime(int position) {
            long totalSeconds = (duration + 999L) / 1000;
            long seconds = position >= duration ? totalSeconds : position / 1000;
            String label = DateUtils.formatElapsedTime(seconds) + " / " + DateUtils.formatElapsedTime(totalSeconds);
            if (!label.contentEquals(time.getText())) time.setText(label);
        }

        private void releaseFocus() {
            if (focused) {
                focused = false;
                audio.abandonAudioFocusRequest(focus);
            }
        }

        private void fail(String status) {
            if (audioPreview != this) return;
            VaultDiag.media("viewer", status);
            close();
            page.feedback(page.text.get("message_log_clip_missing"), Toast.LENGTH_LONG);
        }

        void close() {
            if (audioPreview != this) return;
            audioPreview = null;
            ui.removeCallbacks(update);
            ui.removeCallbacks(expire);
            media.release();
            releaseFocus();
            if (mediaDialog == sheet.dialog) mediaDialog = null;
            if (sheet.dialog.isShowing()) sheet.dialog.dismiss();
        }
    }

    private void clearVault() {
        WORK.execute(() -> {
            boolean cleared = Vault72h.clearAll();
            page.runOnUiThread(() -> {
                page.feedback(page.text.get(cleared ? "vault_cleared" : "vault_clear_failed"), Toast.LENGTH_LONG);
                if (dialog != null) {
                    if (cleared) resetPaging();
                    load();
                }
            });
        });
    }
}
