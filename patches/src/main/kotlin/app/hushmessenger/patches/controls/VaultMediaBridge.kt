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

private const val VAULT_GATE = "$SETTINGS->vaultWanted()Z"
private const val VAULT_RESOLVED = "$SETTINGS->vaultMediaResolved(Ljava/lang/String;Ljava/lang/Object;)V"
private const val VAULT_RESOLVED_PHOTOS = "$SETTINGS->vaultResolvedPhotos(Ljava/lang/String;Ljava/lang/Object;)V"
private const val VAULT_RESOLVED_VIDEO = "$SETTINGS->vaultResolvedVideo(Ljava/lang/String;Ljava/lang/Object;)V"
private const val VAULT_RESOLVED_AUDIO = "$SETTINGS->vaultResolvedAudio(Ljava/lang/String;Ljava/lang/Object;)V"

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

/** The method is one of the bridge's three resolutions, with two local registers to work in. */
internal fun MutableMethod.validateVaultMediaBridge() {
    if (!isBridgeResolution()) {
        throw PatchException("Messenger controls: ${hookId()} isn't a media bridge resolution")
    }
    val parameterWords = parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } + 1
    if ((implementation?.registerCount ?: 0) < parameterWords + 2) {
        throw PatchException("Messenger controls: ${hookId()} has no room for the vault media hook")
    }
    if (messageLogContract == null) {
        throw PatchException("Messenger controls: the message fields don't match the tested build")
    }
}

/**
 * At the resolution's start, behind the vault's own gate, the message's id is read off it the same way the
 * notification hook reads it, and the message goes over with it. The extension does the rest off this
 * thread's way and swallows its own failures, so a resolution is never slowed or broken by the vault.
 *
 * Each resolution is also met at its returns: what the resolver produced — the photo list, the video data,
 * the audio data — goes over with the same id. A message's own objects carry references in forms only
 * Messenger's parser accepts; the resolver's output is where the plain addresses Messenger itself was
 * about to load exist, above all in encrypted chats. The hand-off reads one local register the return
 * doesn't hold its value in, and the extension ignores a null result, so a resolution that produced
 * nothing hands over nothing.
 */
internal fun MutableMethod.injectVaultMediaBridge() {
    validateVaultMediaBridge()
    val contract = messageLogContract!!
    val message = if (isBridgeAudio()) "p1" else "p2"
    addInstructionsWithLabels(0, """
        invoke-static {}, $VAULT_GATE
        move-result v0
        if-eqz v0, :stock_behavior
        move-object/from16 v0, $message
        if-eqz v0, :stock_behavior
        iget-object v1, v0, ${contract.messageIdField}
        invoke-static {v1, v0}, $VAULT_RESOLVED
    """.trimIndent(), ExternalLabel("stock_behavior", getInstruction(0)))

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
    // Last return first, so an insertion never shifts the index of a return still to be met. The two
    // lowest registers are the scratch pair: the result is copied out of harm's way first, then the
    // message, whose id replaces it in the other. Neither is a parameter register, and nothing after
    // the insertion reads them but the return itself, which keeps its own register.
    for (at in returns.asReversed()) {
        val returned = (getInstruction(at) as OneRegisterInstruction).registerA
        val label = "vault_resolved_$at"
        addInstructionsWithLabels(at, """
            move-object/from16 v1, v$returned
            move-object/from16 v0, v$messageRegister
            if-eqz v0, :$label
            iget-object v0, v0, ${contract.messageIdField}
            invoke-static {v0, v1}, $resolved
        """.trimIndent(), ExternalLabel(label, getInstruction(at)))
    }
}
