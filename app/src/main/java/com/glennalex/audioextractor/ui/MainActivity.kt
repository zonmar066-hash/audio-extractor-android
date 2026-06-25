package com.glennalex.audioextractor.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glennalex.audioextractor.R
import com.glennalex.audioextractor.model.AudioFile
import com.glennalex.audioextractor.model.ConvertOptions
import com.glennalex.audioextractor.model.ProcessStatus
import com.glennalex.audioextractor.model.SampleRateMode
import com.glennalex.audioextractor.util.AudioProcessor
import com.glennalex.audioextractor.util.FileAdapter
import com.glennalex.audioextractor.util.UriUtils

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectFiles: Button
    private lateinit var btnConvert: Button
    private lateinit var btnClear: Button
    private lateinit var tvSummary: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var spinnerSampleRate: Spinner
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var layoutOptions: LinearLayout

    private val fileList = mutableListOf<AudioFile>()
    private val fileAdapter = FileAdapter(fileList)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isConverting = false
    private var convertOptions = ConvertOptions()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            data?.data?.let { uris.add(it) }
            data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            }
            if (uris.isNotEmpty()) {
                addFiles(uris)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "需要存储权限才能读取文件", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupSpinner()
        setupRecyclerView()
    }

    private fun initViews() {
        btnSelectFiles = findViewById(R.id.btnSelectFiles)
        btnConvert = findViewById(R.id.btnConvert)
        btnClear = findViewById(R.id.btnClear)
        tvSummary = findViewById(R.id.tvSummary)
        recyclerView = findViewById(R.id.recyclerView)
        spinnerSampleRate = findViewById(R.id.spinnerSampleRate)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        layoutOptions = findViewById(R.id.layoutOptions)

        btnSelectFiles.setOnClickListener {
            checkPermissionAndOpenPicker()
        }

        btnConvert.setOnClickListener {
            startConversion()
        }

        btnClear.setOnClickListener {
            fileAdapter.clear()
            updateSummary()
            layoutOptions.visibility = View.GONE
            btnConvert.visibility = View.GONE
            btnClear.visibility = View.GONE
        }

        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            SampleRateMode.entries.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSampleRate.adapter = adapter

        spinnerSampleRate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = SampleRateMode.fromIndex(position)
                convertOptions = convertOptions.copy(
                    sampleRateMode = mode,
                    fixedSampleRate = when (mode) {
                        SampleRateMode.FIXED_44100 -> 44100
                        SampleRateMode.FIXED_48000 -> 48000
                        SampleRateMode.FIXED_96000 -> 96000
                        else -> 44100
                    }
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = fileAdapter
    }

    private fun checkPermissionAndOpenPicker() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            openFilePicker()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        }
        filePickerLauncher.launch(intent)
    }

    private fun addFiles(uris: List<Uri>) {
        val newFiles = uris.map { uri ->
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                it.moveToFirst()
                val name = if (nameIndex >= 0) it.getString(nameIndex) else "unknown"
                val size = if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L
                AudioFile(uri, name, size, contentResolver.getType(uri) ?: "*/*")
            } ?: AudioFile(uri, uri.lastPathSegment ?: "unknown", 0L, "*/*")
        }

        fileAdapter.addAll(newFiles)
        updateSummary()

        layoutOptions.visibility = View.VISIBLE
        btnConvert.visibility = View.VISIBLE
        btnClear.visibility = View.VISIBLE
    }

    private fun updateSummary() {
        val total = fileList.size
        val pending = fileList.count { it.status == ProcessStatus.PENDING }
        val done = fileList.count { it.status == ProcessStatus.DONE }
        val error = fileList.count { it.status == ProcessStatus.ERROR }
        val noAudio = fileList.count { it.status == ProcessStatus.NO_AUDIO_TRACK }

        tvSummary.text = buildString {
            append("共 $total 个文件")
            if (done > 0) append(" | 完成 $done")
            if (error > 0) append(" | 错误 $error")
            if (noAudio > 0) append(" | 无音频 $noAudio")
            if (pending > 0) append(" | 待处理 $pending")
        }
    }

    /**
     * 开始批量转换
     *
     * 纯 Android API，所有 UI 更新通过 mainHandler.post 切回主线程
     */
    private fun startConversion() {
        val pendingFiles = fileList.mapIndexedNotNull { index, file ->
            if (file.status == ProcessStatus.PENDING || file.status == ProcessStatus.ERROR) {
                index to file
            } else null
        }

        if (pendingFiles.isEmpty()) {
            Toast.makeText(this, "没有待处理的文件", Toast.LENGTH_SHORT).show()
            return
        }

        btnSelectFiles.isEnabled = false
        btnConvert.isEnabled = false
        btnClear.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvProgress.visibility = View.VISIBLE
        isConverting = true

        processFilesSequentially(pendingFiles, 0)
    }

    private fun processFilesSequentially(
        files: List<Pair<Int, AudioFile>>,
        currentIndex: Int
    ) {
        if (currentIndex >= files.size) {
            mainHandler.post {
                btnSelectFiles.isEnabled = true
                btnConvert.isEnabled = true
                btnClear.isEnabled = true
                progressBar.visibility = View.GONE
                tvProgress.visibility = View.GONE
                isConverting = false
                updateSummary()
                Toast.makeText(this, "批量处理完成", Toast.LENGTH_LONG).show()
            }
            return
        }

        val (originalIndex, file) = files[currentIndex]
        val total = files.size
        val current = currentIndex + 1

        mainHandler.post {
            tvProgress.text = "($current/$total) ${file.displayName}"
            progressBar.progress = (currentIndex.toFloat() / total * 100).toInt()
        }

        // Step 1: 检测音频轨道
        mainHandler.post {
            fileAdapter.updateItem(originalIndex, file.copy(status = ProcessStatus.CHECKING))
        }

        Thread {
            val hasAudio = AudioProcessor.hasAudioTrack(this, file.uri)

            if (!hasAudio) {
                mainHandler.post {
                    fileAdapter.updateItem(originalIndex, file.copy(
                        status = ProcessStatus.NO_AUDIO_TRACK,
                        errorMessage = "该文件没有音频轨道"
                    ))
                    updateSummary()
                    processFilesSequentially(files, currentIndex + 1)
                }
                return@Thread
            }

            // 获取输出路径
            val outputPath = AudioProcessor.getOutputPath(this, file.displayName)

            // 更新状态为转换中
            mainHandler.post {
                fileAdapter.updateItem(originalIndex, file.copy(status = ProcessStatus.PROCESSING))
            }

            // Step 2: 执行转换（纯 Android API）
            AudioProcessor.convert(
                context = this,
                inputUri = file.uri,
                outputPath = outputPath,
                options = convertOptions,
                onComplete = { success, message ->
                    // 此回调在后台线程
                    mainHandler.post {
                        if (success) {
                            fileAdapter.updateItem(originalIndex, file.copy(
                                status = ProcessStatus.DONE,
                                outputPath = outputPath
                            ))
                        } else {
                            fileAdapter.updateItem(originalIndex, file.copy(
                                status = ProcessStatus.ERROR,
                                errorMessage = message
                            ))
                        }
                        updateSummary()
                        processFilesSequentially(files, currentIndex + 1)
                    }
                },
                onProgress = { progress ->
                    // 此回调在后台线程
                    mainHandler.post {
                        val percent = ((currentIndex + progress) / total * 100).toInt()
                            .coerceIn(0, 100)
                        progressBar.progress = percent
                    }
                }
            )
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 纯 Android API，无需取消 session
    }
}
