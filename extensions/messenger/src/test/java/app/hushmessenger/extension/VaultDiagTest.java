package app.hushmessenger.extension;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * The vault's structured trace: every event is safe to emit with any detail, including none, and
 * emitting one can never throw into the code it watches.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class VaultDiagTest {
    @Test public void everyEventIsSafeToEmit() {
        VaultDiag.event("VAULT_INIT", null);
        VaultDiag.event("VAULT_INIT", "");
        VaultDiag.init("db=test version=2");
        VaultDiag.databaseOpen("db=test version=2");
        VaultDiag.featureEnabled();
        VaultDiag.featureEnabled();
        VaultDiag.hook("notification");
        VaultDiag.hook("notification");
        VaultDiag.hook("bridge_return");
        VaultDiag.extracted("notification", null, 0);
        VaultDiag.extracted("bridge_return", "photo", 3);
        VaultDiag.filtered("view_once");
        VaultDiag.dedup("inserted");
        VaultDiag.encryptSuccess();
        VaultDiag.insertSuccess(7);
        VaultDiag.insertFailed("SQLiteFullException");
        assertTrue(true);
    }
}
