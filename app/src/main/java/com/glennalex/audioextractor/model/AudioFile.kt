package com.glennalex.audioextractor.model

import android.net.Uri

data class AudioFile(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val mimeType: String,
    var status: ProcessStatus = ProcessStatus.PENDING,
    var errorMessage: String? = null,
    var outputPath: String? = null
)

enum class ProcessStatus {
    PENDING,
    CHECKING,
    NO_AUDIO_TRACK,
    PROCESSING,
    DONE,
    ERROR
}
