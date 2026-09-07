package com.mobius.momo.viewmodel

enum class ComposerInputMode {
    Text,
    Voice,
}

enum class AttachmentStatus {
    Uploading,
    Done,
    Error,
}

data class PendingAttachment(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val status: AttachmentStatus,
    val path: String = "",
    val error: String = "",
    val previewBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAttachment) return false
        return id == other.id &&
            name == other.name &&
            size == other.size &&
            mimeType == other.mimeType &&
            status == other.status &&
            path == other.path &&
            error == other.error &&
            previewBytes.contentEquals(other.previewBytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + error.hashCode()
        result = 31 * result + (previewBytes?.contentHashCode() ?: 0)
        return result
    }
}

fun UiState.withToggledComposerMode(): UiState = copy(
    composerInputMode = if (composerInputMode == ComposerInputMode.Text) {
        ComposerInputMode.Voice
    } else {
        ComposerInputMode.Text
    },
)

fun UiState.canSendComposerMessage(): Boolean {
    if (attachments.any { it.status == AttachmentStatus.Uploading }) return false
    return input.isNotBlank() || attachments.any { it.status == AttachmentStatus.Done && it.path.isNotBlank() }
}

fun ComposerUiState.canSendComposerMessage(): Boolean {
    if (attachments.any { it.status == AttachmentStatus.Uploading }) return false
    return input.isNotBlank() || attachments.any { it.status == AttachmentStatus.Done && it.path.isNotBlank() }
}
