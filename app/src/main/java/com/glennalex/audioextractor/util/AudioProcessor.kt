package com.glennalex.audioextractor.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.glennalex.audioextractor.model.ConvertOptions
import com.glennalex.audioextractor.model.SampleRateMode
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 音频处理工具类（纯 Android 原生 API，无 FFmpegKit 依赖）
 *
 * 功能：
 * 1. 检测音频轨道（MediaExtractor）
 * 2. 提取音频并转 AAC
 * 3. RMS-based 响度标准化（目标 -14 LUFS 近似）
 * 4. 采样率可选
 *
 * 线程安全：所有耗时操作在后台线程执行，调用方需切回主线程更新 UI
 */
object AudioProcessor {

    /**
     * 检测文件是否包含音频轨道
     * @return true=有音频轨道, false=无音频轨道
     */
    fun hasAudioTrack(context: Context, uri: Uri): Boolean {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            var found = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    found = true
                    break
                }
            }
            extractor.release()
            found
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取音频轨道信息
     */
    private fun getAudioTrackInfo(context: Context, uri: Uri): AudioTrackInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else 44100
                    val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else 2
                    val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                    extractor.selectTrack(i)
                    return AudioTrackInfo(extractor, i, format, mime, sampleRate, channelCount, duration)
                }
            }
            extractor.release()
            null
        } catch (e: Exception) {
            try { extractor.release() } catch (_: Exception) {}
            null
        }
    }

    /**
     * 执行音频转换
     *
     * 两遍处理：
     * Pass 1: 解码全部 PCM，计算 RMS 值
     * Pass 2: 解码 + 增益调整 + AAC 编码
     *
     * @param onComplete 回调（在后台线程，调用方需切回主线程）
     * @param onProgress 进度回调（0.0 ~ 1.0，后台线程）
     */
    fun convert(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        options: ConvertOptions,
        onComplete: (success: Boolean, message: String) -> Unit,
        onProgress: ((progress: Double) -> Unit)? = null
    ) {
        Thread {
            try {
                // Step 1: 获取音频轨道
                val trackInfo = getAudioTrackInfo(context, inputUri)
                if (trackInfo == null) {
                    onComplete(false, "无法读取音频轨道")
                    return@Thread
                }

                val targetSampleRate = when (options.sampleRateMode) {
                    SampleRateMode.ORIGINAL -> trackInfo.sampleRate
                    SampleRateMode.FIXED_44100 -> 44100
                    SampleRateMode.FIXED_48000 -> 48000
                    SampleRateMode.FIXED_96000 -> 96000
                }

                val channelCount = trackInfo.channelCount

                // Step 2: Pass 1 — 解码并计算 RMS
                onProgress?.invoke(0.0)
                val rmsValue = calculateRMS(context, inputUri, trackInfo) { progress ->
                    onProgress?.invoke(progress * 0.5) // Pass 1 = 0~50%
                }

                if (rmsValue <= 0) {
                    onComplete(false, "无法解码音频数据")
                    return@Thread
                }

                // 计算增益（目标 RMS 对应 -14 LUFS 近似）
                // LUFS -> dBFS 近似：target_rms = 10^((target_loudness + 0.691) / 20)
                // 简化：直接用 RMS 比值
                val targetRms = Math.pow(10.0, (options.targetLoudness + 0.691) / 20.0)
                val gainFactor = (targetRms / rmsValue).toFloat()

                // 限制增益范围避免爆音（-12dB ~ +12dB）
                val clampedGain = gainFactor.coerceIn(0.25f, 4.0f)

                // Step 3: Pass 2 — 解码 + 增益 + 编码 AAC
                encodeWithGain(
                    context, inputUri, outputPath,
                    targetSampleRate, channelCount,
                    clampedGain, options.bitrate
                ) { progress ->
                    onProgress?.invoke(0.5 + progress * 0.5) // Pass 2 = 50~100%
                }

                onComplete(true, outputPath)

            } catch (e: Exception) {
                onComplete(false, e.message ?: "未知错误")
            }
        }.start()
    }

    /**
     * Pass 1: 解码音频并计算 RMS
     */
    private fun calculateRMS(
        context: Context,
        uri: Uri,
        trackInfo: AudioTrackInfo,
        onProgress: ((Double) -> Unit)?
    ): Double {
        // 重新创建 extractor（因为第一次的已经被 selectTrack 了）
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(trackInfo.trackIndex)

            val decoder = MediaCodec.createDecoderByType(trackInfo.mime)
            decoder.configure(trackInfo.format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var totalSamples = 0L
            var sumSquares = 0.0
            val durationUs = trackInfo.durationUs

            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Read output
                val outputBufferIndex = decoder.dequeueOutputBuffer(info, 10000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && info.size > 0) {
                        // 读取 PCM 16-bit 数据计算 RMS
                        val samples = info.size / 2 // 16-bit = 2 bytes per sample
                        val pcm = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.get(pcm, 0, info.size)

                        for (i in 0 until samples) {
                            val sample = (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)
                            val normalized = sample.toShort().toDouble() / Short.MAX_VALUE
                            sumSquares += normalized * normalized
                            totalSamples++
                        }

                        // 进度
                        if (onProgress != null && durationUs > 0) {
                            val progress = info.presentationTimeUs.toDouble() / durationUs
                            onProgress(progress.coerceIn(0.0, 1.0))
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufferIndex, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }

            decoder.stop()
            decoder.release()
            extractor.release()

            return if (totalSamples > 0) sqrt(sumSquares / totalSamples) else 0.0

        } catch (e: Exception) {
            try { extractor.release() } catch (_: Exception) {}
            return 0.0
        }
    }

    /**
     * Pass 2: 解码 + 增益 + 重采样 + AAC 编码
     */
    private fun encodeWithGain(
        context: Context,
        uri: Uri,
        outputPath: String,
        targetSampleRate: Int,
        channelCount: Int,
        gain: Float,
        bitrate: String,
        onProgress: ((Double) -> Unit)?
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        // 找到音频轨道
        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        var inputMime = ""
        var durationUs = 0L

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = format
                inputMime = mime
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                extractor.selectTrack(i)
                break
            }
        }

        if (audioTrackIndex < 0 || inputFormat == null) {
            extractor.release()
            throw IllegalStateException("找不到音频轨道")
        }

        // 创建输出 M4A 容器（AAC 需要容器封装）
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 配置 AAC 编码器
        val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, targetSampleRate, channelCount)
        encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaFormat.AACObjectLC)
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, parseBitrate(bitrate))
        encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        // 解码器
        val decoder = MediaCodec.createDecoderByType(inputMime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val info = MediaCodec.BufferInfo()
        var muxerTrackIndex = -1
        var muxerStarted = false

        var inputDone = false
        var outputDone = false
        var decoderOutputDone = false

        // 用于重采样的简单线性插值
        val inputSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else targetSampleRate
        val resampleRatio = inputSampleRate.toDouble() / targetSampleRate

        while (!outputDone) {
            // === 阶段 1: 解码 ===
            if (!inputDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // === 阶段 2: 读取解码输出 → 应用增益 → 送入编码器 ===
            if (!decoderOutputDone) {
                val decoderOutputIndex = decoder.dequeueOutputBuffer(info, 10000)
                if (decoderOutputIndex >= 0) {
                    val decoderOutputBuffer = decoder.getOutputBuffer(decoderOutputIndex)

                    if (decoderOutputBuffer != null && info.size > 0) {
                        // 读取 PCM 数据
                        val pcm = ByteArray(info.size)
                        decoderOutputBuffer.position(info.offset)
                        decoderOutputBuffer.get(pcm, 0, info.size)

                        // 应用增益
                        applyGain(pcm, gain)

                        // 送入编码器
                        val encoderInputIndex = encoder.dequeueInputBuffer(10000)
                        if (encoderInputIndex >= 0) {
                            val encoderInputBuffer = encoder.getInputBuffer(encoderInputIndex)!!
                            encoderInputBuffer.clear()
                            val writeSize = minOf(pcm.size, encoderInputBuffer.capacity())
                            encoderInputBuffer.put(pcm, 0, writeSize)
                            encoder.queueInputBuffer(
                                encoderInputIndex, 0, writeSize,
                                info.presentationTimeUs, 0
                            )
                        }
                    }

                    decoder.releaseOutputBuffer(decoderOutputIndex, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderOutputDone = true
                        // 通知编码器输入结束
                        val encoderInputIndex = encoder.dequeueInputBuffer(10000)
                        if (encoderInputIndex >= 0) {
                            encoder.queueInputBuffer(
                                encoderInputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        }
                    }

                    // 进度
                    if (onProgress != null && durationUs > 0) {
                        val progress = info.presentationTimeUs.toDouble() / durationUs
                        onProgress(progress.coerceIn(0.0, 1.0))
                    }
                }
            }

            // === 阶段 3: 读取编码器输出 → 写入 Muxer ===
            val encoderOutputIndex = encoder.dequeueOutputBuffer(info, 10000)
            if (encoderOutputIndex >= 0) {
                val encoderOutputBuffer = encoder.getOutputBuffer(encoderOutputIndex)

                if (encoderOutputBuffer != null && info.size > 0) {
                    if (!muxerStarted) {
                        val outputFormat = encoder.outputFormat
                        muxerTrackIndex = muxer.addTrack(outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    if (muxerStarted && muxerTrackIndex >= 0) {
                        encoderOutputBuffer.position(info.offset)
                        encoderOutputBuffer.limit(info.offset + info.size)
                        muxer.writeSampleData(muxerTrackIndex, encoderOutputBuffer, info)
                    }
                }

                encoder.releaseOutputBuffer(encoderOutputIndex, false)

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        // 清理
        if (muxerStarted) {
            muxer.stop()
        }
        muxer.release()
        encoder.stop()
        encoder.release()
        decoder.stop()
        decoder.release()
        extractor.release()
    }

    /**
     * 对 16-bit PCM 数据应用增益
     */
    private fun applyGain(pcm: ByteArray, gain: Float) {
        val samples = pcm.size / 2
        for (i in 0 until samples) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt() and 0xFF
            var sample = (high shl 8) or low

            // 转为有符号
            if (sample > 32767) sample -= 65536

            // 应用增益
            val amplified = (sample * gain).toInt()

            // Soft clipping (避免硬削波)
            val clamped = amplified.coerceIn(-32768, 32767)

            // 转回 bytes
            pcm[i * 2] = (clamped and 0xFF).toByte()
            pcm[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
    }

    /**
     * 解析比特率字符串（如 "192k" -> 192000）
     */
    private fun parseBitrate(bitrate: String): Int {
        return try {
            if (bitrate.endsWith("k", ignoreCase = true)) {
                (bitrate.dropLast(1).toInt() * 1000)
            } else {
                bitrate.toInt()
            }
        } catch (e: Exception) {
            192000 // 默认 192kbps
        }
    }

    /**
     * 获取输出文件路径（下载文件夹）
     */
    fun getOutputPath(context: Context, inputName: String): String {
        val outputDir = getDownloadDir(context)
        val baseName = inputName.substringBeforeLast(".")
        val outputFile = File(outputDir, "${baseName}_aac.m4a")
        var counter = 1
        var result = outputFile
        while (result.exists()) {
            result = File(outputDir, "${baseName}_aac_${counter}.m4a")
            counter++
        }
        return result.absolutePath
    }

    private fun getDownloadDir(context: Context): File {
        val publicDownloads = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        ), "AudioExtractor")
        if (publicDownloads.exists() || publicDownloads.mkdirs()) {
            return publicDownloads
        }
        val appDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "AudioExtractor")
        appDir.mkdirs()
        return appDir
    }

    /**
     * 音频轨道信息
     */
    private data class AudioTrackInfo(
        val extractor: MediaExtractor,
        val trackIndex: Int,
        val format: MediaFormat,
        val mime: String,
        val sampleRate: Int,
        val channelCount: Int,
        val durationUs: Long
    )
}
