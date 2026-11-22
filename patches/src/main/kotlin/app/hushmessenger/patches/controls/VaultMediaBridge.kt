package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * The Temporary Vault's second set of eyes on a message's media. A message's notification-time object
 * carries no usable media address in an encrypted chat — the addresses exist only when Messenger's thread
 * view resolves the message's media for display, in the one bridge class that turns a message into its
 * video data, its photo list and its audio data. The vault's hook sits at those three resolutions and hands
 * the message, with its own id, to the extension, which scans it again and attaches what it finds to the
 * entry the message's arrival already archived. Regular media only: the extension refuses a message
 * carrying view-once data on this surface exactly as at arrival.
 */
internal const val VAULT_MEDIA_BRIDGE = "vault_media_bridge"

private const val VIDEO_ATTACHMENT_DATA = "Lcom/facebook/messaging/attachments/VideoAttachmentData;"
private const val AUDIO_ATTACHMENT_DATA = "Lcom/facebook/messaging/attachments/AudioAttachmentData;"

private const val VAULT_RESOLVED_PHOTOS = "$SETTINGS->vaultResolvedPhotos(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
private const val VAULT_RESOLVED_VIDEO = "$SETTINGS->vaultResolvedVideo(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
private const val VAULT_RESOLVED_AUDIO = "$SETTINGS->vaultResolvedAudio(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"

/** The bridge's video resolution: (user session, message) to the message's video data. */
private fun Method.isBridgeVideo(): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) && returnType == VIDEO_ATTACHMENT_DATA &&
        parameterTypes.map(CharSequence::toString) == listOf(FB_USER_SESSION, MESSAGE)

/** The bridge's photo resolution: (user session, message) to the message's photo data list. */
private fun Method.isBridgePhotos(): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) && returnType == IMMUTABLE_LIST &&
        parameterTypes.map(CharSequence::toString) == listOf(FB_USER_SESSION, MESSAGE)

/** The bridge's audio resolution: (message, a name) to the message's audio data. */
private fun Method.isBridgeAudio(): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) && returnType == AUDIO_ATTACHMENT_DATA &&
        parameterTypes.map(CharSequence::toString) == listOf(MESSAGE, "Ljava/lang/String;")

private fun Method.isBridgeResolution(): Boolean = isBridgeVideo() || isBridgePhotos() || isBridgeAudio()

/**
 * The one class that resolves all three kinds from a message. A class resolving only one or two is some
 * other feature's helper and is left alone, so the three resolutions always come from the same bridge.
 */
internal fun findVaultMediaBridge(classes: Iterable<ClassDef>): List<Method> {
    val bridge = classes.firstOrNull { cls ->
        cls.methods.any { it.isBridgeVideo() } && cls.methods.any { it.isBridgePhotos() } &&
            cls.methods.any { it.isBridgeAudio() }
    } ?: return emptyList()
    return bridge.methods.filter { it.isBridgeResolution() }
}

/** The method is one of the bridge's three resolutions, with four local registers to work in. */
internal fun MutableMethod.validateVaultMediaBridge() {
    if (!isBridgeResolution()) {
        throw PatchException("Messenger controls: ${hookId()} isn't a media bridge resolution")
    }
    val parameterWords = parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } + 1
    if ((implementation?.registerCount ?: 0) < parameterWords + 4) {
        throw PatchException("Messenger controls: ${hookId()} has no room for the vault media hook")
    }
    if (messageLogContract == null) {
        throw PatchException("Messenger controls: the message fields don't match the tested build")
    }
}

/** Copies the message and result into dead locals at each return, leaving the stock return register intact. */
internal fun MutableMethod.injectVaultMediaBridge() {
    validateVaultMediaBridge()
    val contract = messageLogContract!!
    val resolved = when {
        isBridgeVideo() -> VAULT_RESOLVED_VIDEO
        isBridgeAudio() -> VAULT_RESOLVED_AUDIO
        else -> VAULT_RESOLVED_PHOTOS
    }
    val registers = implementation!!.registerCount
    // The message parameter: last for the video and photo resolutions, second-to-last for audio, whose
    // last parameter is a name. Parameter registers sit at the end of the register range.
    val messageRegister = registers - if (isBridgeAudio()) 2 else 1
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    val parameterWords = parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } + 1
    val localLimit = (registers - parameterWords).coerceAtMost(16)
    for (at in returns.asReversed()) {
        val returned = (getInstruction(at) as OneRegisterInstruction).registerA
        val scratch = (0 until localLimit).filter { it != returned }.take(4)
        if (scratch.size != 4) {
            throw PatchException("Messenger controls: ${hookId()} has no four scratch locals at return $at")
        }
        val (result, holder, id, thread) = scratch
        val label = "vault_resolved_$at"
        addInstructionsWithLabels(at, """
            move-object/from16 v$result, v$returned
            move-object/from16 v$holder, v$messageRegister
            if-eqz v$holder, :$label
            iget-object v$id, v$holder, ${contract.messageIdField}
            iget-object v$thread, v$holder, ${contract.threadKeyField}
            invoke-static {v$id, v$thread, v$holder, v$result}, $resolved
        """.trimIndent(), ExternalLabel(label, getInstruction(at)))
    }
}
