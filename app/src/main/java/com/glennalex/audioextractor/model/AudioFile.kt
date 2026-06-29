package com.glennalex.audioextractor.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AudioFile(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val mimeType: String,
    var status: ProcessStatus = ProcessStatus.PENDING,
    var errorMessage: String? = null,
    var outputPath: String? = null
) : Parcelable

enum class ProcessStatus {
    PENDING,
    CHECKING,
    NO_AUDIO_TRACK,
    PROCESSING,
    DONE,
    ERROR
}
