package app.hushmessenger.extension;

import android.util.Log;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Temporary Vault's structured trace, in the log under one tag, so a failure on a device says which
 * stage a message reached and where it stopped: VAULT_INIT, FEATURE_ENABLED, HOOK_INSTALLED,
 * HOOK_INVOKED, MESSAGE_EXTRACTED, MESSAGE_FILTERED, DEDUPLICATION_RESULT, DATABASE_OPEN,
 * ENCRYPT_SUCCESS, INSERT_SUCCESS, INSERT_FAILED.
 *
 * A detail carries only what cannot identify anything: a surface name, a media kind, a count, an
 * outcome, the vault's own row number, an exception's class name. Never a message's words, never an
 * address or a URL, never a message or thread id from Messenger, never a key. Logging itself is
 * wrapped so a diagnostic can never break the thing it watches, and an event that would repeat for
 * every message is logged in full anyway — the vault's volume is a message's handful of lines, and a
 * missing line is exactly what a diagnosis cannot afford.
 */
final class VaultDiag {
    private static final String TAG = "HushVault72H";
    private static final Set<String> INSTALLED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean ENABLED_LOGGED = new AtomicBoolean();

    private VaultDiag() {}

    static void event(String name, String detail) {
        try {
            Log.i(TAG, detail == null || detail.isEmpty() ? name : name + " " + detail);
        } catch (Throwable ignored) {
            // A diagnostic must never break what it watches.
        }
    }

    /** The vault's store was created in this process. */
    static void init(String detail) { event("VAULT_INIT", detail); }

    /** The vault's database is open, with its schema version. */
    static void databaseOpen(String detail) { event("DATABASE_OPEN", detail); }

    /** The vault's own switch is on and a capture path has taken its first message under it. */
    static void featureEnabled() {
        if (ENABLED_LOGGED.compareAndSet(false, true)) event("FEATURE_ENABLED", "control=" + Vault72h.KEY);
    }

    /**
     * A hook surface handed the vault something. The first invocation in a process is also the proof
     * the hook is installed — a surface that was never patched in can never call — so it is logged as
     * HOOK_INSTALLED once, and every invocation as HOOK_INVOKED.
     */
    static void hook(String surface) {
        if (INSTALLED.add(surface)) event("HOOK_INSTALLED", "surface=" + surface);
        event("HOOK_INVOKED", "surface=" + surface);
    }

    /** A message was taken apart on a surface: its media kind and how many addresses it carried. */
    static void extracted(String surface, String kind, int addresses) {
        event("MESSAGE_EXTRACTED",
            "surface=" + surface + " kind=" + (kind == null ? "none" : kind) + " addresses=" + addresses);
    }

    /** A message or a media delivery was deliberately not taken, with the reason as a code. */
    static void filtered(String reason) { event("MESSAGE_FILTERED", "reason=" + reason); }

    /** How a capture met what was already archived: inserted, merged, edited, already_present. */
    static void dedup(String result) { event("DEDUPLICATION_RESULT", "result=" + result); }

    static void media(String stage, String status) {
        event("MEDIA", "stage=" + stage + " status=" + status);
    }

    /** A record's fields were sealed and are about to be written. */
    static void encryptSuccess() { event("ENCRYPT_SUCCESS", ""); }

    /** A record is in the database, under the vault's own row number for it. */
    static void insertSuccess(long rowId) { event("INSERT_SUCCESS", "row=" + rowId); }

    /** A write failed, with only the failure's class name. */
    static void insertFailed(String failure) { event("INSERT_FAILED", "error=" + failure); }
}
