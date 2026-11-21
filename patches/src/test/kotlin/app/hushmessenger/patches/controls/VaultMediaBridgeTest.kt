package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BRIDGE = "LX/VbBridge;"
private const val VIDEO_DATA = "Lcom/facebook/messaging/attachments/VideoAttachmentData;"
private const val AUDIO_DATA = "Lcom/facebook/messaging/attachments/AudioAttachmentData;"
private const val ID_FIELD = "LX/VbMessage;->A03:Ljava/lang/String;"
private const val PHOTOS = "$BRIDGE->A0E($FB_USER_SESSION$MESSAGE)$IMMUTABLE_LIST"
private const val VIDEO = "$BRIDGE->A0C($FB_USER_SESSION$MESSAGE)$VIDEO_DATA"
private const val AUDIO = "$BRIDGE->A0A($MESSAGE;Ljava/lang/String;)$AUDIO_DATA"
private const val PHOTOS_BODY = """
    if-eqz p1, :second
    return-object v0
    :second
    return-object v0
"""

/**
 * The vault's media bridge, above all its register discipline at a resolution's returns: the hand-off
 * may only read the register the return reads, never write it. The tested build's photo resolution
 * returns its list in v0 at every return, and an earlier version of this hook wrote the message and
 * its id into v0 on the way past — the caller got the id string in place of the list and died in
 * native code calling the list's iterator. These fixtures pin the corrected shape.
 */
class VaultMediaBridgeTest {
    @AfterTest fun reset() {
        messageLogContract = null
    }

    private fun contract() {
        messageLogContract = MessageLogContract(
            "LX/VbMessage;->A00:Ljava/lang/String;",
            "LX/VbSecret;->A00:Ljava/lang/String;",
            "LX/VbMessage;->A01:Ljava/lang/String;",
            "LX/VbCheck;->A00($MESSAGE)Z",
            ID_FIELD,
            "LX/VbMessage;->A02:Z",
        )
    }

    private fun codeOf(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) =
        method.implementation!!.instructions.toList()

    /** The five instructions the hand-off leaves in front of the return at [returnAt]. */
    private fun assertHandOff(code: List<Instruction>, returnAt: Int, messageRegister: Int, handOff: String) {
        val returned = (code[returnAt] as OneRegisterInstruction).registerA
        val copy = code[returnAt - 5] as TwoRegisterInstruction
        val holder = code[returnAt - 4] as TwoRegisterInstruction
        val branch = code[returnAt - 3] as OneRegisterInstruction
        val read = code[returnAt - 2] as TwoRegisterInstruction
        val call = code[returnAt - 1] as FiveRegisterInstruction
        assertEquals(Opcode.MOVE_OBJECT_FROM16, copy.opcode)
        assertEquals(returned, copy.registerB, "the result is copied out of the return's register")
        assertEquals(Opcode.MOVE_OBJECT_FROM16, holder.opcode)
        assertEquals(messageRegister, holder.registerB, "the message is copied out of its parameter register")
        assertEquals(Opcode.IF_EQZ, branch.opcode)
        assertEquals(holder.registerA, branch.registerA)
        assertEquals(Opcode.IGET_OBJECT, read.opcode)
        assertEquals(ID_FIELD, (read as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.INVOKE_STATIC, call.opcode)
        assertEquals(handOff, (call as ReferenceInstruction).reference.toString())
        assertEquals(listOf(holder.registerA, copy.registerA),
            listOf(call.registerC, call.registerD), "the hand-off gets the id and the copied result")
        // The load-bearing assertions: nothing in the block writes the register the return reads, and
        // the two scratch registers are not each other.
        for (write in listOf(copy, holder, read)) {
            assertTrue(write.registerA != returned,
                "the hand-off must not write v$returned, the register the return reads")
        }
        assertTrue(copy.registerA != holder.registerA, "the scratch registers must differ")
    }

    @Test fun thePhotoResolutionsReturnsKeepTheirRegister() {
        contract()
        val photos = fixtureMethod(PHOTOS, PHOTOS_BODY, 14)
        val video = fixtureMethod(VIDEO, "return-object v3", 8)
        val audio = fixtureMethod(AUDIO, "return-object v2", 8)
        val classes = listOf(fixtureClass(BRIDGE, listOf(photos, video, audio)))
        assertEquals(3, findVaultMediaBridge(classes).size)

        photos.injectVaultMediaBridge()
        val code = codeOf(photos)
        val returns = code.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
        assertEquals(2, returns.size)
        for (at in returns) {
            assertEquals(0, (code[at] as OneRegisterInstruction).registerA, "the stock return still reads v0")
            assertHandOff(code, at, 13, "$SETTINGS->vaultResolvedPhotos(Ljava/lang/String;Ljava/lang/Object;)V")
        }
    }

    @Test fun theVideoAndAudioReturnsKeepTheirRegisters() {
        contract()
        val video = fixtureMethod(VIDEO, "return-object v3", 8)
        val audio = fixtureMethod(AUDIO, "return-object v2", 8)
        val photos = fixtureMethod(PHOTOS, PHOTOS_BODY, 14)
        fixtureClass(BRIDGE, listOf(photos, video, audio))

        video.injectVaultMediaBridge()
        val videoCode = codeOf(video)
        val videoReturn = videoCode.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT }
        assertHandOff(videoCode, videoReturn, 7, "$SETTINGS->vaultResolvedVideo(Ljava/lang/String;Ljava/lang/Object;)V")

        audio.injectVaultMediaBridge()
        val audioCode = codeOf(audio)
        val audioReturn = audioCode.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT }
        assertHandOff(audioCode, audioReturn, 6, "$SETTINGS->vaultResolvedAudio(Ljava/lang/String;Ljava/lang/Object;)V")
    }

    @Test fun aResolutionWithoutTwoSpareLocalsIsRefused() {
        contract()
        // Five registers: three parameter words and the locals v0, v1 — with the return reading v0,
        // only v1 is left, and one scratch register is not enough to hand the result over safely.
        val cramped = fixtureMethod(PHOTOS, "return-object v0", 5)
        assertFailsWith<PatchException> { cramped.injectVaultMediaBridge() }
    }

    @Test fun theBridgeNeedsAllThreeResolutionsInOneClass() {
        val photos = fixtureMethod(PHOTOS, PHOTOS_BODY, 14)
        val video = fixtureMethod(VIDEO, "return-object v3", 8)
        val classes = listOf(fixtureClass(BRIDGE, listOf(photos, video)))
        assertEquals(0, findVaultMediaBridge(classes).size)
    }
}
