package com.glennalex.audioextractor.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ConvertOptions(
    val targetLoudness: Float = -14.0f,
    val sampleRateMode: SampleRateMode = SampleRateMode.ORIGINAL,
    val fixedSampleRate: Int = 44100,
    val outputFormat: String = "aac",
    val bitrate: String = "192k"
) : Parcelable

enum class SampleRateMode(val displayName: String) {
    ORIGINAL("原始采样率"),
    FIXED_44100("44100 Hz"),
    FIXED_48000("48000 Hz"),
    FIXED_96000("96000 Hz");

    companion object {
        fun fromIndex(index: Int): SampleRateMode = entries[index]
    }
}

enum class BitrateMode(val displayName: String, val value: String) {
    B96("96 kbps（语音）", "96k"),
    B128("128 kbps（推荐）", "128k"),
    B192("192 kbps（高品质）", "192k"),
    B256("256 kbps（高保真）", "256k");

    companion object {
        fun fromIndex(index: Int): BitrateMode = entries[index]
    }
}
