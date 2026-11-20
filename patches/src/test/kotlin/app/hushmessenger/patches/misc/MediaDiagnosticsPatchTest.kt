package app.hushmessenger.patches.misc

import app.hushmessenger.patches.controls.fixtureClass
import app.hushmessenger.patches.controls.fixtureMethod
import app.hushmessenger.patches.controls.hookId
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
    private val builderCtor = "$attachment-><init>(LX/2pr;)V"

    private val storesBoth = """
        invoke-direct {p0}, Ljava/lang/Object;-><init>()V
        iput-object v0, p0, $attachment->A06:$ephemeral
        iput-object v0, p0, $attachment->A08:$video
        return-void
    """.trimIndent()

    private fun attachmentClass() = fixtureClass(attachment, listOf(
        fixtureMethod(builderCtor, storesBoth),
        fixtureMethod("$attachment-><init>(Landroid/os/Parcel;)V", storesBoth),
    ))

    @Test fun findsTheAssemblyConstructorAndNotTheParcelOne() {
        val found = findDataBuiltHook(listOf(attachmentClass()))
        assertEquals(builderCtor, found?.hookId())
    }

    @Test fun assemblyFinderNeedsBothStores() {
        val onlyVideo = fixtureClass(attachment, listOf(fixtureMethod(builderCtor, """
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            iput-object v0, p0, $attachment->A08:$video
            return-void
        """.trimIndent())))
        assertNull(findDataBuiltHook(listOf(onlyVideo)))
        assertNull(findDataBuiltHook(emptyList()))
    }

    /** Regression: a constructor that only ever reads the two fields is not an assembly point. */
    @Test fun assemblyFinderIgnoresAConstructorThatOnlyReadsTheFields() {
        val readsOnly = fixtureClass(attachment, listOf(fixtureMethod(builderCtor, """
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            iget-object v0, p0, $attachment->A06:$ephemeral
            iget-object v0, p0, $attachment->A08:$video
            return-void
        """.trimIndent())))
        assertNull(findDataBuiltHook(listOf(readsOnly)))
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
        assertEquals("LX/4TJ;->A0C($session$message)$videoData", found?.hookId())
    }

    @Test fun bridgeFinderNeedsTheVideoRead() {
        val noRead = bridgeClass("LX/4TJ;", """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent())
        assertNull(findPlayerBridgeHook(listOf(noRead)))
    }

    /** Regression: a same-shaped method that only stores the video is not the bridge that reads it. */
    @Test fun bridgeFinderIgnoresAMethodThatOnlyStoresTheVideo() {
        val storesOnly = bridgeClass("LX/4TJ;", """
            iput-object v0, v1, $attachment->A08:$video
            const/4 v0, 0x0
            return-object v0
        """.trimIndent())
        assertNull(findPlayerBridgeHook(listOf(storesOnly)))
    }

    @Test fun bridgeFinderRefusesTwoCandidates() {
        assertNull(findPlayerBridgeHook(listOf(bridgeClass("LX/4TJ;"), bridgeClass("LX/9ZZ;"))))
    }

    @Test fun validatorsRefuseMethodsWithoutASpareRegister() {
        val crampedBuilt = fixtureMethod(builderCtor, storesBoth, registers = 2)
        assertFailsWith<PatchException> { crampedBuilt.validateDataBuiltHook() }
        val crampedBridge = fixtureMethod("LX/4TJ;->A0C($session$message)$videoData", readsVideo, registers = 3)
        assertFailsWith<PatchException> { crampedBridge.validatePlayerBridgeHook() }
    }

    private fun calledMethod(instruction: Instruction): MethodReference? =
        (instruction as? ReferenceInstruction)?.reference as? MethodReference

    /**
     * The gate is asked first and the hand-over happens only behind its branch, checked on the decoded
     * instructions themselves — opcodes and method references, never an instruction's toString.
     */
    private fun assertGateThenObserve(method: MutableMethod, observe: String, move: Opcode) {
        val instructions = method.implementation!!.instructions.toList()
        val gate = instructions.indexOfFirst { calledMethod(it)?.name == "mediaDiagnosticsWanted" }
        val call = instructions.indexOfFirst { calledMethod(it)?.name == observe }
        assertTrue(gate >= 0, "no gate call in ${instructions.map { it.opcode }}")
        assertTrue(call > gate, "the hand-over is not behind the gate in ${instructions.map { it.opcode }}")
        assertEquals("Lapp/hushmessenger/extension/Settings;", calledMethod(instructions[gate])?.definingClass)
        assertEquals(Opcode.MOVE_RESULT, instructions[gate + 1].opcode)
        assertTrue(instructions.subList(gate, call).any { it.opcode == Opcode.IF_EQZ },
            "no branch on the gate's answer in ${instructions.map { it.opcode }}")
        assertEquals(move, instructions[call - 1].opcode)
    }

    @Test fun injectorsAskTheGateBeforeHandingAnythingOver() {
        val built = fixtureMethod(builderCtor, storesBoth)
        built.injectDataBuilt()
        assertGateThenObserve(built, "observeDataBuilt", Opcode.MOVE_OBJECT)

        val bridge = fixtureMethod("LX/4TJ;->A0C($session$message)$videoData", readsVideo)
        bridge.injectPlayerBridge()
        assertGateThenObserve(bridge, "observePlayerBridge", Opcode.MOVE_OBJECT_FROM16)
    }

    @Test fun thePatchStaysOutOfTheDefaultSelection() {
        assertEquals(MEDIA_DIAGNOSTICS_PATCH, mediaDiagnosticsPatch.name)
        assertTrue(mediaDiagnosticsPatch.default == false)
    }
}
