package com.glennalex.audioextractor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.glennalex.audioextractor.model.AudioFile
import com.glennalex.audioextractor.model.ConvertOptions
import com.glennalex.audioextractor.model.ProcessStatus
import com.glennalex.audioextractor.ui.MainActivity
import com.glennalex.audioextractor.util.AudioProcessor

class ConvertService : Service() {

    companion object {
        const val CHANNEL_ID = "convert_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_FILES = "files"
        const val EXTRA_OPTIONS = "options"

        // UI 回调（MainActivity 在 onResume 注册）
        var onFileStatusChange: ((index: Int, status: ProcessStatus, outputPath: String?, errorMessage: String?) -> Unit)? = null
        var onProgress: ((current: Int, total: Int, fileName: String, percent: Int) -> Unit)? = null
        var onComplete: ((success: Boolean, message: String) -> Unit)? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false

    // 保存原始文件列表的索引映射
    private var fileIndices: List<Int> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val files = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(EXTRA_FILES, AudioFile::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<AudioFile>(EXTRA_FILES)
                }
                val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_OPTIONS, ConvertOptions::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<ConvertOptions>(EXTRA_OPTIONS)
                } ?: ConvertOptions()

                if (files.isNullOrEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, createNotification("准备转换...", 0, 0))

                Thread {
                    processFiles(files, options)
                }.start()
            }
        }

        return START_STICKY
    }

    private fun processFiles(files: List<AudioFile>, options: ConvertOptions) {
        isRunning = true
        val total = files.size
        var done = 0
        var error = 0

        for ((index, file) in files.withIndex()) {
            if (!isRunning) break

            val current = index + 1

            // 更新进度
            mainHandler.post {
                onProgress?.invoke(current, total, file.displayName, 0)
            }
            updateNotification("($current/$total) ${file.displayName}", index, total)

            // 检测音频轨道
            mainHandler.post {
                onFileStatusChange?.invoke(index, ProcessStatus.CHECKING, null, null)
            }

            val hasAudio = AudioProcessor.hasAudioTrack(this, file.uri)
            if (!hasAudio) {
                error++
                mainHandler.post {
                    onFileStatusChange?.invoke(index, ProcessStatus.NO_AUDIO_TRACK, null, "无音频轨道")
                }
                continue
            }

            // 获取输出路径
            val outputPath = AudioProcessor.getOutputPath(this, file.displayName)

            // 更新状态为转换中
            mainHandler.post {
                onFileStatusChange?.invoke(index, ProcessStatus.PROCESSING, null, null)
            }

            // 同步转换
            var success = false
            var message = "未知错误"
            val latch = java.util.concurrent.CountDownLatch(1)

            AudioProcessor.convert(
                context = this,
                inputUri = file.uri,
                outputPath = outputPath,
                options = options,
                onComplete = { s, msg ->
                    success = s
                    message = msg
                    latch.countDown()
                },
                onProgress = { progress ->
                    val percent = ((index + progress) / total * 100).toInt().coerceIn(0, 100)
                    mainHandler.post {
                        onProgress?.invoke(current, total, file.displayName, percent)
                    }
                    updateNotificationProgress("($current/$total) ${file.displayName}", percent)
                }
            )

            latch.await()

            if (success) {
                done++
                mainHandler.post {
                    onFileStatusChange?.invoke(index, ProcessStatus.DONE, outputPath, null)
                }
            } else {
                error++
                mainHandler.post {
                    onFileStatusChange?.invoke(index, ProcessStatus.ERROR, null, message)
                }
            }
        }

        // 完成
        val allSuccess = error == 0
        val resultMessage = if (allSuccess) {
            "全部 $total 个文件转换完成"
        } else {
            "完成 $done 个，失败 $error 个（共 $total 个）"
        }

        updateNotification("转换完成: $resultMessage", total, total)

        mainHandler.post {
            onComplete?.invoke(allSuccess, resultMessage)
        }

        // 延迟停止 Service
        Thread {
            Thread.sleep(2000)
            stopSelf()
        }.start()
    }

    private fun createNotification(text: String, current: Int, total: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频转换",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音频转换进度通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("音频提取器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)

        if (total > 0) {
            builder.setProgress(total, current, false)
        }

        return builder.build()
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        val notification = createNotification(text, current, total)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationProgress(text: String, percent: Int) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("音频提取器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, percent, false)

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
