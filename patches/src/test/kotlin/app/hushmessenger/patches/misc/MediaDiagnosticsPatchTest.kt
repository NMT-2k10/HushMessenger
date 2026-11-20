package app.hushmessenger.patches.misc

import app.hushmessenger.patches.controls.fixtureClass
import app.hushmessenger.patches.controls.fixtureMethod
import app.hushmessenger.patches.controls.hookId
import app.morphe.patcher.patch.PatchException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaDiagnosticsPatchTest {
    private val attachment = "Lcom/facebook/messaging/model/attachment/Attachment;"
    private val ephemeral = "Lcom/facebook/messaging/model/attachment/EphemeralMediaData;"
    private val video = "Lcom/facebook/messaging/model/attachment/VideoData;"
    private val session = "Lcom/facebook/auth/usersession/FbUserSession;"
    private val message = "Lcom/facebook/messaging/model/messages/Message;"
    private val videoData = "Lcom/facebook/messaging/attachments/VideoAttachmentData;"

    private val storesBoth = """
        invoke-direct {p0}, Ljava/lang/Object;-><init>()V
        iput-object v0, p0, $attachment->A06:$ephemeral
        iput-object v0, p0, $attachment->A08:$video
        return-void
    """.trimIndent()

    private fun attachmentClass() = fixtureClass(attachment, listOf(
        fixtureMethod("$attachment<init>(LX/2pr;)V", storesBoth),
        fixtureMethod("$attachment<init>(Landroid/os/Parcel;)V", storesBoth),
    ))

    @Test fun findsTheAssemblyConstructorAndNotTheParcelOne() {
        val found = findDataBuiltHook(listOf(attachmentClass()))
        assertEquals("$attachment<init>(LX/2pr;)V", found?.let(::hookId))
    }

    @Test fun assemblyFinderNeedsBothStores() {
        val onlyVideo = fixtureClass(attachment, listOf(fixtureMethod("$attachment<init>(LX/2pr;)V", """
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            iput-object v0, p0, $attachment->A08:$video
            return-void
        """.trimIndent())))
        assertNull(findDataBuiltHook(listOf(onlyVideo)))
        assertNull(findDataBuiltHook(emptyList()))
    }

    private val readsVideo = """
        iget-object v0, v1, $attachment->A08:$video
        const/4 v0, 0x0
        return-object v0
    """.trimIndent()

    private fun bridgeClass(type: String, body: String = readsVideo) =
        fixtureClass(type, listOf(fixtureMethod("$type->A0C($session$message)$videoData", body)))

    @Test fun findsThePlayerBridge() {
        val found = findPlayerBridgeHook(listOf(bridgeClass("LX/4TJ;"), attachmentClass()))
        assertEquals("LX/4TJ;->A0C($session$message)$videoData", found?.let(::hookId))
    }

    @Test fun bridgeFinderNeedsTheVideoRead() {
        val noRead = bridgeClass("LX/4TJ;", """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent())
        assertNull(findPlayerBridgeHook(listOf(noRead)))
    }

    @Test fun bridgeFinderRefusesTwoCandidates() {
        assertNull(findPlayerBridgeHook(listOf(bridgeClass("LX/4TJ;"), bridgeClass("LX/9ZZ;"))))
    }

    @Test fun validatorsRefuseMethodsWithoutASpareRegister() {
        val crampedBuilt = fixtureMethod("$attachment<init>(LX/2pr;)V", storesBoth, registers = 2)
        assertFailsWith<PatchException> { crampedBuilt.validateDataBuiltHook() }
        val crampedBridge = fixtureMethod("LX/4TJ;->A0C($session$message)$videoData", readsVideo, registers = 3)
        assertFailsWith<PatchException> { crampedBridge.validatePlayerBridgeHook() }
    }

    @Test fun injectorsAskTheGateBeforeHandingAnythingOver() {
        val built = fixtureMethod("$attachment<init>(LX/2pr;)V", storesBoth)
        built.injectDataBuilt()
        val builtText = built.implementation!!.instructions.joinToString("\n") { it.toString() }
        assertTrue(builtText.contains("Settings;->mediaDiagnosticsWanted()Z"), builtText)
        assertTrue(builtText.contains("Settings;->observeDataBuilt(Ljava/lang/Object;)V"), builtText)
        assertTrue(builtText.indexOf("mediaDiagnosticsWanted") < builtText.indexOf("observeDataBuilt"), builtText)

        val bridge = fixtureMethod("LX/4TJ;->A0C($session$message)$videoData", readsVideo)
        bridge.injectPlayerBridge()
        val bridgeText = bridge.implementation!!.instructions.joinToString("\n") { it.toString() }
        assertTrue(bridgeText.contains("Settings;->mediaDiagnosticsWanted()Z"), bridgeText)
        assertTrue(bridgeText.contains("Settings;->observePlayerBridge(Ljava/lang/Object;)V"), bridgeText)
        assertTrue(bridgeText.indexOf("mediaDiagnosticsWanted") < bridgeText.indexOf("observePlayerBridge"), bridgeText)
    }

    @Test fun thePatchStaysOutOfTheDefaultSelection() {
        assertEquals(MEDIA_DIAGNOSTICS_PATCH, mediaDiagnosticsPatch.name)
        assertTrue(mediaDiagnosticsPatch.default == false)
    }
}
