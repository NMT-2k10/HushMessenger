
package app.hushmessenger.patches.misc

import app.hushmessenger.patches.MessengerTarget
import app.hushmessenger.patches.controls.SETTINGS
import app.hushmessenger.patches.controls.hookId
import app.hushmessenger.patches.controls.settingsExtension
import app.hushmessenger.patches.messengerClasses
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val MEDIA_DIAGNOSTICS_PATCH = "Media diagnostics hooks"

private const val ATTACHMENT = "Lcom/facebook/messaging/model/attachment/Attachment;"
private const val EPHEMERAL_DATA = "Lcom/facebook/messaging/model/attachment/EphemeralMediaData;"
private const val VIDEO_DATA = "Lcom/facebook/messaging/model/attachment/VideoData;"
private const val SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val MESSAGE = "Lcom/facebook/messaging/model/messages/Message;"
private const val VIDEO_ATTACHMENT_DATA = "Lcom/facebook/messaging/attachments/VideoAttachmentData;"
private const val PARCEL = "Landroid/os/Parcel;"

private fun fail(detail: String): Nothing =
    throw PatchException("$MEDIA_DIAGNOSTICS_PATCH: $detail. Start with an unmodified supported APK.")

private val FIELD_STORES = setOf(
    Opcode.IPUT,
    Opcode.IPUT_WIDE,
    Opcode.IPUT_OBJECT,
    Opcode.IPUT_BOOLEAN,
    Opcode.IPUT_BYTE,
    Opcode.IPUT_CHAR,
    Opcode.IPUT_SHORT,
)

private val FIELD_READS = setOf(
    Opcode.IGET,
    Opcode.IGET_WIDE,
    Opcode.IGET_OBJECT,
    Opcode.IGET_BOOLEAN,
    Opcode.IGET_BYTE,
    Opcode.IGET_CHAR,
    Opcode.IGET_SHORT,
)

private fun touchesField(
    method: Method,
    owner: String,
    type: String,
    writes: Boolean,
): Boolean =
    method.implementation?.instructions?.any { instruction ->
        val reference =
            (instruction as? ReferenceInstruction)?.reference as? FieldReference
                ?: return@any false

        instruction.opcode in (if (writes) FIELD_STORES else FIELD_READS) &&
            reference.definingClass == owner &&
            reference.type == type
    } ?: false

internal fun findDataBuiltHook(classes: Iterable<ClassDef>): Method? {
    val attachment = classes.firstOrNull { it.type == ATTACHMENT } ?: return null

    return attachment.methods.filter { method ->
        method.name == "<init>" &&
            method.returnType == "V" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0].toString() != PARCEL &&
            touchesField(
                method,
                ATTACHMENT,
                EPHEMERAL_DATA,
                writes = true,
            ) &&
            touchesField(
                method,
                ATTACHMENT,
                VIDEO_DATA,
                writes = true,
            )
    }.singleOrNull()
}

internal fun findPlayerBridgeHook(classes: Iterable<ClassDef>): Method? =
    classes.flatMap { it.methods }.filter { method ->
        method.parameterTypes.map { it.toString() } == listOf(SESSION, MESSAGE) &&
            method.returnType == VIDEO_ATTACHMENT_DATA &&
            touchesField(
                method,
                ATTACHMENT,
                VIDEO_DATA,
                writes = false,
            )
    }.singleOrNull()

internal fun MutableMethod.validateDataBuiltHook() {
    if (
        name != "<init>" ||
        parameterTypes.size != 1 ||
        parameterTypes[0].toString() == PARCEL
    ) {
        fail("the attachment assembly constructor is not the one the finder described")
    }

    val registers = implementation?.registerCount
        ?: fail("the attachment assembly constructor has no code")

    if (registers < 3) {
        fail("the attachment assembly constructor has no register to spare for the gate")
    }
}

internal fun MutableMethod.validatePlayerBridgeHook() {
    if (
        parameterTypes.map { it.toString() } != listOf(SESSION, MESSAGE) ||
        returnType != VIDEO_ATTACHMENT_DATA
    ) {
        fail("the player bridge method is not the one the finder described")
    }

    val registers = implementation?.registerCount
        ?: fail("the player bridge method has no code")

    if (registers < 4) {
        fail("the player bridge method has no register to spare for the gate")
    }
}

internal fun MutableMethod.injectDataBuilt() {
    addInstructionsWithLabels(
        0,
        """
        invoke-static {}, $SETTINGS->mediaDiagnosticsWanted()Z
        move-result v0
        if-eqz v0, :media_diag_skip
        move-object v0, p1
        invoke-static {v0}, $SETTINGS->observeDataBuilt(Ljava/lang/Object;)V
        :media_diag_skip
        nop
        """.trimIndent(),
    )
}

internal fun MutableMethod.injectPlayerBridge() {
    addInstructionsWithLabels(
        0,
        """
        invoke-static {}, $SETTINGS->mediaDiagnosticsWanted()Z
        move-result v0
        if-eqz v0, :media_diag_skip
        move-object/from16 v0, p2
        invoke-static {v0}, $SETTINGS->observePlayerBridge(Ljava/lang/Object;)V
        :media_diag_skip
        nop
        """.trimIndent(),
    )
}

val mediaDiagnosticsPatch = patch(
    name = MEDIA_DIAGNOSTICS_PATCH,
    description = "Test builds only. Adds the two read-only observation hooks the Media diagnostics switch " +
        "reads at the points where an attachment is assembled and where a video's data is built for the " +
        "player, so a test log can say what media a message carries at those moments. Each hook asks the " +
        "switch first and does nothing while it's off; neither hook reads, keeps or changes any media. " +
        "Pick it in Morphe Manager's Expert mode.",
    default = false,
) {
    category("Fixes")
    compatibleWith(MessengerTarget.COMPATIBILITY)
    dependsOn(settingsExtension)

    execute {
        val classes = messengerClasses()

        val built = findDataBuiltHook(classes)
            ?: fail(
                "no constructor assembles an attachment from its builder " +
                    "with both the ephemeral marker and the video"
            )

        val bridge = findPlayerBridgeHook(classes)
            ?: fail(
                "no method builds a video's attachment data " +
                    "from a session and a message"
            )

        val builtMethod = mutableClassDefBy(built.definingClass).methods
            .firstOrNull { it.hookId() == built.hookId() }
            ?: fail(
                "the attachment assembly constructor could not be reopened for editing"
            )

        builtMethod.validateDataBuiltHook()
        builtMethod.injectDataBuilt()

        val bridgeMethod = mutableClassDefBy(bridge.definingClass).methods
            .firstOrNull { it.hookId() == bridge.hookId() }
            ?: fail(
                "the player bridge method could not be reopened for editing"
            )

        bridgeMethod.validatePlayerBridgeHook()
        bridgeMethod.injectPlayerBridge()
    }
}
