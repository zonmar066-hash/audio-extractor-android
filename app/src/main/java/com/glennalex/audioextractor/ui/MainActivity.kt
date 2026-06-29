package com.glennalex.audioextractor.ui

import android.app.Activity
import android.app.AlertDialog
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
import com.glennalex.audioextractor.model.BitrateMode
import com.glennalex.audioextractor.model.ConvertOptions
import com.glennalex.audioextractor.model.ProcessStatus
import com.glennalex.audioextractor.model.SampleRateMode
import com.glennalex.audioextractor.service.ConvertService
import com.glennalex.audioextractor.util.FileAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectFiles: Button
    private lateinit var btnConvert: Button
    private lateinit var btnClear: Button
    private lateinit var tvSummary: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var spinnerSampleRate: Spinner
    private lateinit var spinnerBitrate: Spinner
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var layoutOptions: LinearLayout

    private val fileList = mutableListOf<AudioFile>()
    private val fileAdapter = FileAdapter(fileList)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isConverting = false
    private var convertOptions = ConvertOptions()

    // 当前转换的文件在 fileList 中的索引列表
    private var convertingIndices: List<Int> = emptyList()

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
        setupSpinners()
        setupRecyclerView()

        // 请求通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerServiceCallbacks()
    }

    private fun registerServiceCallbacks() {
        ConvertService.onFileStatusChange = { index, status, outputPath, errorMessage ->
            // index 是在 convertingIndices 中的序号，映射回 fileList
            if (index < convertingIndices.size) {
                val realIndex = convertingIndices[index]
                fileList[realIndex].status = status
                fileList[realIndex].outputPath = outputPath
                fileList[realIndex].errorMessage = errorMessage
                fileAdapter.notifyItemChanged(realIndex)
                updateSummary()
            }
        }

        ConvertService.onProgress = { current, total, fileName, percent ->
            tvProgress.text = "($current/$total) $fileName"
            progressBar.progress = percent
        }

        ConvertService.onComplete = { success, message ->
            isConverting = false
            btnSelectFiles.isEnabled = true
            btnConvert.isEnabled = true
            btnClear.isEnabled = true
            progressBar.visibility = View.GONE
            tvProgress.visibility = View.GONE
            fileAdapter.notifyDataSetChanged()
            updateSummary()

            // 完成/报错弹窗
            AlertDialog.Builder(this)
                .setTitle(if (success) "转换完成" else "转换结束")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun initViews() {
        btnSelectFiles = findViewById(R.id.btnSelectFiles)
        btnConvert = findViewById(R.id.btnConvert)
        btnClear = findViewById(R.id.btnClear)
        tvSummary = findViewById(R.id.tvSummary)
        recyclerView = findViewById(R.id.recyclerView)
        spinnerSampleRate = findViewById(R.id.spinnerSampleRate)
        spinnerBitrate = findViewById(R.id.spinnerBitrate)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        layoutOptions = findViewById(R.id.layoutOptions)

        btnSelectFiles.setOnClickListener {
            if (!isConverting) checkPermissionAndOpenPicker()
        }

        btnConvert.setOnClickListener {
            startConversion()
        }

        btnClear.setOnClickListener {
            if (!isConverting) {
                fileAdapter.clear()
                updateSummary()
                layoutOptions.visibility = View.GONE
                btnConvert.visibility = View.GONE
                btnClear.visibility = View.GONE
            }
        }

        progressBar.isIndeterminate = false
        progressBar.max = 100
        progressBar.progress = 0
    }

    private fun setupSpinners() {
        // 采样率
        val srAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            SampleRateMode.entries.map { it.displayName }
        )
        srAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSampleRate.adapter = srAdapter

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

        // 比特率
        val brAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            BitrateMode.entries.map { it.displayName }
        )
        brAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBitrate.adapter = brAdapter

        // 默认选 192k（index 2）
        spinnerBitrate.setSelection(2)

        spinnerBitrate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = BitrateMode.fromIndex(position)
                convertOptions = convertOptions.copy(bitrate = mode.value)
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
     * 开始批量转换 — 通过 Foreground Service 后台运行
     */
    private fun startConversion() {
        // 收集待处理文件的索引和对象
        val pendingIndexed = fileList.mapIndexedNotNull { index, file ->
            if (file.status == ProcessStatus.PENDING || file.status == ProcessStatus.ERROR) {
                index to file
            } else null
        }

        if (pendingIndexed.isEmpty()) {
            Toast.makeText(this, "没有待处理的文件", Toast.LENGTH_SHORT).show()
            return
        }

        // 保存索引映射
        convertingIndices = pendingIndexed.map { it.first }

        // 重置错误文件状态
        pendingIndexed.forEach { (index, _) ->
            fileList[index].status = ProcessStatus.PENDING
            fileList[index].errorMessage = null
        }
        fileAdapter.notifyDataSetChanged()

        isConverting = true
        btnSelectFiles.isEnabled = false
        btnConvert.isEnabled = false
        btnClear.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvProgress.visibility = View.VISIBLE
        tvProgress.text = "启动后台转换..."

        // 收集文件列表
        val pendingFiles = ArrayList(pendingIndexed.map { it.second })

        // 启动 Foreground Service
        val intent = Intent(this, ConvertService::class.java).apply {
            action = ConvertService.ACTION_START
            putParcelableArrayListExtra(ConvertService.EXTRA_FILES, pendingFiles)
            putExtra(ConvertService.EXTRA_OPTIONS, convertOptions)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
