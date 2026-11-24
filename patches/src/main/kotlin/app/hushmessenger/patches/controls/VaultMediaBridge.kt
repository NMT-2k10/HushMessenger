package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

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
internal const val VAULT_NATIVE_PLAYBACK_CLASS = "LX/5X2;"
internal const val VAULT_NATIVE_NOTIFICATION_CLASS = "LX/ABi;"
internal const val VAULT_VISUAL_RENDER_CLASS = "LX/5mK;"

private const val VIDEO_ATTACHMENT_DATA = "Lcom/facebook/messaging/attachments/VideoAttachmentData;"
private const val AUDIO_ATTACHMENT_DATA = "Lcom/facebook/messaging/attachments/AudioAttachmentData;"

private const val VAULT_RESOLVED_PHOTOS = "$SETTINGS->vaultResolvedPhotos(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
private const val VAULT_RESOLVED_VIDEO = "$SETTINGS->vaultResolvedVideo(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
private const val VAULT_RESOLVED_AUDIO = "$SETTINGS->vaultResolvedAudio(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
private const val NATIVE_MEDIA_ID = "LX/FU2;->A08:Ljava/lang/String;"
private const val VAULT_NATIVE_PLAYBACK = "$SETTINGS->vaultNativePlayback(Ljava/lang/Object;)V"
private const val VAULT_NOTIFICATION_IDENTITY = "$SETTINGS->vaultNotificationIdentity(Ljava/lang/Object;)V"
private const val NATIVE_MODEL = "LX/FU2;"
private const val NATIVE_ATTACHMENTS = "LX/5SV;"
private const val NATIVE_MODEL_PARAMETERS = "$FB_USER_SESSION"+"LX/6fH;LX/6c5;LX/D4c;LX/50v;${NATIVE_MODEL}II"
private const val NATIVE_MODEL_CALL = "$VAULT_NATIVE_PLAYBACK_CLASS->A00($NATIVE_MODEL_PARAMETERS)V"
private const val VISUAL_HELPER = "hushVaultVisual"
private const val VAULT_NATIVE_VISUAL = "$SETTINGS->vaultNativeVisual(Ljava/lang/Object;Ljava/lang/Object;)V"

/** The 587 renderer holds the attachment rows immediately before the nine-register builder invocation. */
internal fun Method.vaultVisualCallIndex(): Int? {
    if (definingClass != VAULT_VISUAL_RENDER_CLASS || name != "A5b" || returnType != "Ljava/lang/Object;" ||
        parameterTypes != listOf("Ljava/lang/Object;", "Ljava/lang/Object;")) return null
    val implementation = implementation ?: return null
    if (implementation.registerCount != 53) return null
    val code = implementation.instructions.toList()
    val at = code.indices.singleOrNull { index ->
        val call = code[index] as? RegisterRangeInstruction ?: return@singleOrNull false
        call.opcode == Opcode.INVOKE_VIRTUAL_RANGE && call.startRegister == 35 && call.registerCount == 9 &&
            (call as? ReferenceInstruction)?.reference.toString() == NATIVE_MODEL_CALL
    } ?: return null
    val prepare = code.getOrNull(at - 3) as? TwoRegisterInstruction ?: return null
    if (prepare.opcode != Opcode.MOVE_OBJECT_FROM16 || prepare.registerA != 41 || prepare.registerB != 13) return null
    val attachmentCast = code.subList((at - 50).coerceAtLeast(0), at).lastOrNull {
        it.opcode == Opcode.CHECK_CAST && (it as? OneRegisterInstruction)?.registerA == 34
    } as? ReferenceInstruction ?: return null
    return at.takeIf { attachmentCast.reference.toString() == NATIVE_ATTACHMENTS }
}

internal fun MutableClass.injectVaultVisualRendering(method: MutableMethod, at: Int) {
    if (methods.any { it.name == VISUAL_HELPER }) throw PatchException("Vault visual helper already exists")
    val params = listOf(NATIVE_ATTACHMENTS, VAULT_NATIVE_PLAYBACK_CLASS, FB_USER_SESSION, "LX/6fH;", "LX/6c5;",
        "LX/D4c;", "LX/50v;", NATIVE_MODEL, "I", "I")
    val helper = MutableMethod(ImmutableMethod(type, VISUAL_HELPER, params.map { ImmutableMethodParameter(it, null, null) },
        "V", AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(10, emptyList(), null, null))).apply {
        addInstructions(0, """
            invoke-virtual/range {p1 .. p9}, $NATIVE_MODEL_CALL
            invoke-static {p0, p7}, $VAULT_NATIVE_VISUAL
            return-void
        """.trimIndent())
    }
    methods.add(helper)
    directMethods.add(helper)
    method.replaceInstruction(at, "invoke-static/range {v34 .. v43}, $type->$VISUAL_HELPER(${params.joinToString("")})V")
}

// Messenger 587 assigns the server message ID to its thread-view model after resolving the attachment.
internal fun Method.isNativePlaybackBuilder(): Boolean =
    definingClass == VAULT_NATIVE_PLAYBACK_CLASS && !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
        parameterTypes.map(CharSequence::toString) ==
            listOf(FB_USER_SESSION, "LX/6fH;", "LX/6c5;", "LX/D4c;", "LX/50v;", "LX/FU2;", "I", "I") &&
        implementation?.instructions?.any {
            it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference.toString() == NATIVE_MEDIA_ID
        } == true

internal fun Method.isNativeNotificationBuilder(): Boolean =
    definingClass == VAULT_NATIVE_NOTIFICATION_CLASS && returnType == MESSAGE &&
        parameterTypes.map(CharSequence::toString) ==
            listOf("Lcom/facebook/messaging/model/messages/ParticipantInfo;", "LX/ABZ;") &&
        implementation?.registerCount == 44 &&
        implementation?.instructions?.any {
            it.opcode == Opcode.IGET_OBJECT &&
                (it as ReferenceInstruction).reference.toString() == "LX/ABZ;->A0D:Ljava/lang/String;"
        } == true

internal fun MutableMethod.injectVaultNotificationIdentity() {
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    for (at in returns.asReversed()) {
        // p2 is the unchanged payload the stock builder used for the Message's identity.
        addInstruction(at, "invoke-static/range {p2 .. p2}, $VAULT_NOTIFICATION_IDENTITY")
    }
}

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
    if (isNativePlaybackBuilder()) {
        if (implementation!!.registerCount != 34 || implementation!!.instructions.count {
            it.opcode == Opcode.RETURN_VOID
        } != 1) throw PatchException("Messenger controls: the playback builder registers changed")
        return
    }
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
    if (isNativePlaybackBuilder()) {
        val at = implementation!!.instructions.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
        // p6 is the untouched FU2 model parameter. A range invoke reads it directly.
        addInstruction(at, "invoke-static/range {p6 .. p6}, $VAULT_NATIVE_PLAYBACK")
        return
    }
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
