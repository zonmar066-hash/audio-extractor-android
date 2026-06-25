package com.glennalex.audioextractor.util

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.glennalex.audioextractor.R
import com.glennalex.audioextractor.model.AudioFile
import com.glennalex.audioextractor.model.ProcessStatus

/**
 * 文件列表适配器
 *
 * 注意：所有 UI 更新必须通过 [notifyDataSetChanged] 等方法在主线程调用
 * FFmpegKit 回调在后台线程，调用方需用 runOnUiThread 包装后调用更新方法
 */
class FileAdapter(
    private val files: MutableList<AudioFile>
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.tvFileName)
        val statusText: TextView = view.findViewById(R.id.tvStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.nameText.text = file.displayName

        holder.statusText.text = when (file.status) {
            ProcessStatus.PENDING -> "等待中"
            ProcessStatus.CHECKING -> "检测音频轨道..."
            ProcessStatus.NO_AUDIO_TRACK -> "⚠ 无音频轨道，已跳过"
            ProcessStatus.PROCESSING -> "转换中..."
            ProcessStatus.DONE -> "✓ 完成: ${file.outputPath?.substringAfterLast("/")}"
            ProcessStatus.ERROR -> "✗ 错误: ${file.errorMessage}"
        }

        holder.statusText.setTextColor(when (file.status) {
            ProcessStatus.DONE -> 0xFF4CAF50.toInt()
            ProcessStatus.ERROR, ProcessStatus.NO_AUDIO_TRACK -> 0xFFF44336.toInt()
            ProcessStatus.PROCESSING, ProcessStatus.CHECKING -> 0xFFFF9800.toInt()
            else -> 0xFF757575.toInt()
        })
    }

    override fun getItemCount() = files.size

    fun updateItem(index: Int, file: AudioFile) {
        if (index in files.indices) {
            files[index] = file
            notifyItemChanged(index)
        }
    }

    fun clear() {
        files.clear()
        notifyDataSetChanged()
    }

    fun addAll(items: List<AudioFile>) {
        files.addAll(items)
        notifyItemRangeInserted(files.size - items.size, items.size)
    }
}
