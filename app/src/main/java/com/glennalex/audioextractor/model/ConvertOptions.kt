package com.glennalex.audioextractor.model

data class ConvertOptions(
    val targetLoudness: Float = -14.0f,
    val sampleRateMode: SampleRateMode = SampleRateMode.ORIGINAL,
    val fixedSampleRate: Int = 44100,
    val outputFormat: String = "aac",
    val bitrate: String = "192k"
)

enum class SampleRateMode(val displayName: String) {
    ORIGINAL("原始采样率"),
    FIXED_44100("44100 Hz"),
    FIXED_48000("48000 Hz"),
    FIXED_96000("96000 Hz");

    companion object {
        fun fromIndex(index: Int): SampleRateMode = entries[index]
    }
}
