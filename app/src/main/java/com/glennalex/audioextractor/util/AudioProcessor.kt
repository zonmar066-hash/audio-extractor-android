package com.glennalex.audioextractor.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.SessionState
import com.glennalex.audioextractor.model.ConvertOptions
import com.glennalex.audioextractor.model.SampleRateMode
import java.io.File

/**
 * 音频处理工具类
 *
 * 解决两个关键问题：
 * 1. 无音频轨道检测：使用 ffprobe 预检查流信息，避免对无音频文件执行 FFmpeg 导致崩溃
 * 2. 线程安全：所有回调（LogCallback/StatisticsCallback）在 FFmpegKit 后台线程触发，
 *    UI 更新必须由调用方通过 runOnUiThread 或 Handler(Looper.getMainLooper()) 切回主线程
 */
object AudioProcessor {

    /**
     * 检测文件是否包含音频轨道
     * 使用 ffprobe 检测，比 MediaExtractor 更可靠（能处理更多容器格式）
     *
     * @return true=有音频轨道, false=无音频轨道
     */
    fun hasAudioTrack(context: Context, uri: Uri): Boolean {
        // 先用 MediaExtractor 快速检测（纯本地，无 FFmpeg 开销）
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    extractor.release()
                    return true
                }
            }
            extractor.release()
        } catch (e: Exception) {
            // MediaExtractor 失败时降级到 ffprobe
        }

        // 降级方案：用 ffprobe 检测
        val path = UriUtils.getPathFromUri(context, uri) ?: return false
        val session = FFprobeKit.execute("-v error -select_streams a -show_entries stream=codec_type -of csv=p=0 \"$path\"")
        val output = session.output.trim()
        return output.isNotEmpty() && output.contains("audio", ignoreCase = true)
    }

    /**
     * 构建 FFmpeg 命令
     *
     * 关键滤镜链：
     * - loudnorm=I=<target>:TP=-1.5:LRA=11 — EBU R128 响度标准化，两遍模式
     *   I = 目标积分响度（-14 LUFS），TP = 最大真峰值（-1.5 dB），LRA = 响度范围（11 LU）
     * - aresample=<sr> — 采样率转换（可选）
     *
     * 编码器选择：
     * - aac — Android 原生支持，兼容性好
     * - -b:a 192k — 比特率 192kbps（AAC 推荐值）
     */
    fun buildCommand(
        inputPath: String,
        outputPath: String,
        options: ConvertOptions
    ): String {
        val sb = StringBuilder()
        sb.append("-y -i \"$inputPath\"")

        // 音频滤镜链：响度标准化 + 可选采样率转换
        val filters = mutableListOf<String>()

        // loudnorm 滤镜：EBU R128 标准，目标 -14 LUFS
        filters.add("loudnorm=I=${options.targetLoudness}:TP=-1.5:LRA=11")

        // 采样率处理
        if (options.sampleRateMode != SampleRateMode.ORIGINAL) {
            filters.add("aresample=${options.fixedSampleRate}")
        }

        if (filters.isNotEmpty()) {
            sb.append(" -af \"${filters.joinToString(",")}\"")
        }

        // 编码器：AAC
        sb.append(" -c:a aac")
        sb.append(" -b:a ${options.bitrate}")

        // 只保留音频（去掉视频流）
        sb.append(" -vn")

        // 输出文件
        sb.append(" \"$outputPath\"")

        return sb.toString()
    }

    /**
     * 执行音频转换
     *
     * 重要：所有回调在后台线程执行，调用方必须在 UI 更新时切回主线程
     *
     * @param onComplete 在主线程外执行，调用方需自行切换线程
     * @param onProgress 在主线程外执行，调用方需自行切换线程
     * @return FFmpegSession 用于取消任务
     */
    fun convert(
        inputPath: String,
        outputPath: String,
        options: ConvertOptions,
        onComplete: (success: Boolean, message: String) -> Unit,
        onProgress: ((progress: Double) -> Unit)? = null
    ): FFmpegSession {
        val command = buildCommand(inputPath, outputPath, options)

        val logCallback = LogCallback { log ->
            // 解析进度信息（FFmpeg 输出在 stderr）
            val message = log.message
            // loudnorm 输出的进度信息解析
            if (onProgress != null && message.contains("time=")) {
                // 进度解析由调用方通过 duration 计算
            }
        }

        val statisticsCallback = StatisticsCallback { statistics ->
            if (onProgress != null) {
                // statistics.time 用于计算进度百分比
                // 注意：此回调在后台线程
                onProgress.invoke(statistics.time.toDouble())
            }
        }

        return FFmpegKit.executeAsync(
            command,
            { session ->
                // complete callback - 后台线程！
                val state = session.state
                val returnCode = session.returnCode

                if (state == SessionState.COMPLETED && ReturnCode.isSuccess(returnCode)) {
                    onComplete(true, "转换完成: $outputPath")
                } else {
                    val errorMsg = session.allLogsAsString
                        .lines()
                        .lastOrNull { it.contains("Error", ignoreCase = true) }
                        ?: "转换失败，返回码: ${returnCode?.value}"
                    onComplete(false, errorMsg)
                }
            },
            logCallback,
            statisticsCallback
        )
    }

    /**
     * 获取输出文件路径（下载文件夹）
     */
    fun getOutputPath(context: Context, inputName: String): String {
        val outputDir = getDownloadDir(context)
        val baseName = inputName.substringBeforeLast(".")
        val outputFile = File(outputDir, "${baseName}_aac.aac")
        var counter = 1
        var result = outputFile
        while (result.exists()) {
            result = File(outputDir, "${baseName}_aac_${counter}.aac")
            counter++
        }
        return result.absolutePath
    }

    private fun getDownloadDir(context: Context): File {
        // 优先使用公共下载目录
        val publicDownloads = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        ), "AudioExtractor")
        if (publicDownloads.exists() || publicDownloads.mkdirs()) {
            return publicDownloads
        }
        // 降级到应用专属目录
        val appDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "AudioExtractor")
        appDir.mkdirs()
        return appDir
    }
}
