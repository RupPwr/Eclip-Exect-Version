package com.eclip.rup.leb

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eclip.rup.leb.services.ExportService
import com.eclip.rup.leb.services.ExportService.ExportStatus
import com.eclip.rup.leb.services.FFmpegRenderEngine
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ExportActivity : AppCompatActivity() {

    private val TAG = "ExportActivity"

    // ── Export State Machine ──────────────────────────────────────────────────
    enum class ExportState {
        Idle,
        Exporting,
        Cancelling,
        Completed,
        Failed,
        Interrupted
    }

    private var exportState = ExportState.Idle

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var layoutExporting: View
    private lateinit var layoutExportSuccess: View
    private lateinit var layoutExportFailure: View

    private lateinit var btnCloseExport: ImageButton
    private lateinit var tvExportHeaderTitle: TextView

    private lateinit var ivExportThumbnail: ImageView
    private lateinit var tvExportProjectName: TextView
    private lateinit var tvBadgeResolution: TextView
    private lateinit var tvBadgeFps: TextView
    private lateinit var tvBadgeFormat: TextView

    private lateinit var exportProgressBar: ProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var tvExportStatusTitle: TextView
    private lateinit var tvExportStatusMessage: TextView
    private lateinit var btnCancelExport: MaterialButton

    private lateinit var tvSavedFilePath: TextView
    private lateinit var btnPlayVideo: MaterialButton
    private lateinit var btnShareVideo: MaterialButton
    private lateinit var btnDoneExporting: MaterialButton

    private lateinit var tvErrorMessage: TextView
    private lateinit var btnBackToEditorError: MaterialButton

    // ── Export Data ───────────────────────────────────────────────────────────
    private var savedVideoUri: Uri? = null
    private var isAudioOnly = false

    // FFmpeg engine instance (runs in this activity, not a service)
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    // The coroutine job for the export — cancel this to stop export
    private var exportJob: Job? = null

    // Temp file paths for cleanup
    private var tempOutputPath: String? = null
    private var concatFilePath: String? = null

    // ── Intent Extra Keys ────────────────────────────────────────────────────
    companion object {
        const val EXTRA_SOURCE_URI = "extra_source_uri"
        const val EXTRA_PROJECT_NAME = "extra_project_name"
        const val EXTRA_RESOLUTION = "extra_resolution"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_IS_AUDIO_ONLY = "extra_is_audio_only"

        // Export execution parameters (previously sent to ExportService)
        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_TEMP_OUTPUT_PATH = "extra_temp_output_path"
        const val EXTRA_CONCAT_FILE_PATH = "extra_concat_file_path"
        const val EXTRA_TOTAL_DURATION_SECS = "extra_total_duration_secs"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_export)

        ffmpegEngine = FFmpegRenderEngine(this)

        initViews()
        setupMetadata()
        setupListeners()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleExitRequest()
            }
        })

        // Start export immediately
        startExport()
    }

    override fun onStop() {
        super.onStop()
        // Do not cancel export on onStop - allow export to continue when screen dims, locks, or notification shade is pulled down
        Log.d(TAG, "onStop: Activity stopped; export continues in background if running")
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        exportJob?.cancel()
        exportJob = null
        // If user explicitly requested to cancel export, cancel the background service
        if (exportState == ExportState.Cancelling) {
            cancelExportInternal()
        }
    }

    // ── View Init ─────────────────────────────────────────────────────────────

    private fun initViews() {
        layoutExporting = findViewById(R.id.layoutExporting)
        layoutExportSuccess = findViewById(R.id.layoutExportSuccess)
        layoutExportFailure = findViewById(R.id.layoutExportFailure)

        btnCloseExport = findViewById(R.id.btnCloseExport)
        tvExportHeaderTitle = findViewById(R.id.tvExportHeaderTitle)

        ivExportThumbnail = findViewById(R.id.ivExportThumbnail)
        tvExportProjectName = findViewById(R.id.tvExportProjectName)
        tvBadgeResolution = findViewById(R.id.tvBadgeResolution)
        tvBadgeFps = findViewById(R.id.tvBadgeFps)
        tvBadgeFormat = findViewById(R.id.tvBadgeFormat)

        exportProgressBar = findViewById(R.id.exportProgressBar)
        tvProgressPercent = findViewById(R.id.tvProgressPercent)
        tvExportStatusTitle = findViewById(R.id.tvExportStatusTitle)
        tvExportStatusMessage = findViewById(R.id.tvExportStatusMessage)
        btnCancelExport = findViewById(R.id.btnCancelExport)

        tvSavedFilePath = findViewById(R.id.tvSavedFilePath)
        btnPlayVideo = findViewById(R.id.btnPlayVideo)
        btnShareVideo = findViewById(R.id.btnShareVideo)
        btnDoneExporting = findViewById(R.id.btnDoneExporting)

        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnBackToEditorError = findViewById(R.id.btnBackToEditorError)
    }

    private fun setupMetadata() {
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: getString(R.string.app_name)
        val resolution = intent.getIntExtra(EXTRA_RESOLUTION, 1080)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        isAudioOnly = intent.getBooleanExtra(EXTRA_IS_AUDIO_ONLY, false)
        val sourceUriStr = intent.getStringExtra(EXTRA_SOURCE_URI)

        tvExportProjectName.text = projectName
        tvBadgeResolution.text = if (isAudioOnly) "Audio" else "${resolution}p"
        tvBadgeFps.text = if (isAudioOnly) "320 kbps" else "$fps FPS"
        tvBadgeFormat.text = if (isAudioOnly) "MP3" else "MP4"

        if (sourceUriStr != null) {
            val sourceUri = Uri.parse(sourceUriStr)
            loadThumbnail(sourceUri)
        }
    }

    private fun loadThumbnail(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            try {
                if (uri.scheme == "content" || uri.scheme == "file") {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this@ExportActivity, uri)
                        bitmap = com.eclip.rup.leb.utils.CodecInstancePool.withHardwareCodecPermit {
                            retriever.getFrameAtTime(1000000) // 1 second frame
                        }
                    } finally {
                        retriever.release()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load frame with MediaMetadataRetriever: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    if (bitmap != null) {
                        ivExportThumbnail.setImageBitmap(bitmap)
                    } else {
                        try {
                            ivExportThumbnail.setImageURI(uri)
                        } catch (e: Exception) {
                            ivExportThumbnail.setImageResource(R.drawable.ic_banner_foreground)
                        }
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        btnCloseExport.setOnClickListener {
            handleExitRequest()
        }

        btnCancelExport.setOnClickListener {
            confirmCancelExport()
        }

        btnPlayVideo.setOnClickListener {
            playExportedVideo()
        }

        btnShareVideo.setOnClickListener {
            shareExportedVideo()
        }

        btnDoneExporting.setOnClickListener {
            finish()
        }

        btnBackToEditorError.setOnClickListener {
            finish()
        }
    }

    // ── Export Execution (ForegroundService-Backed) ───────────────────────────

    private fun startExport() {
        val command = intent.getStringExtra(EXTRA_COMMAND)
        tempOutputPath = intent.getStringExtra(EXTRA_TEMP_OUTPUT_PATH)
        concatFilePath = intent.getStringExtra(EXTRA_CONCAT_FILE_PATH)
        val durationSecs = intent.getDoubleExtra(EXTRA_TOTAL_DURATION_SECS, 0.0)
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME)

        if (command == null || tempOutputPath == null) {
            showFailureState("Export parameters missing. Please try again.")
            return
        }

        if (durationSecs <= 0.0) {
            showFailureState("Invalid project duration (0s). Please check your timeline clips.")
            return
        }

        exportState = ExportState.Exporting

        // 1. Launch / promote dedicated ExportService foreground service
        val serviceIntent = Intent(this, ExportService::class.java).apply {
            putExtra(ExportService.EXTRA_COMMAND, command)
            putExtra(ExportService.EXTRA_TEMP_OUTPUT_PATH, tempOutputPath)
            putExtra(ExportService.EXTRA_CONCAT_FILE_PATH, concatFilePath)
            putExtra(ExportService.EXTRA_TOTAL_DURATION_SECS, durationSecs)
            putExtra(ExportService.EXTRA_IS_AUDIO_ONLY, isAudioOnly)
            putExtra(ExportService.EXTRA_PROJECT_NAME, projectName)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        // 2. Observe reactive StateFlow from ExportService with lifecycle safety
        exportJob?.cancel()
        exportJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ExportService.exportStatus.collect { status ->
                    when (status) {
                        is ExportStatus.Progress -> {
                            if (exportState == ExportState.Exporting) {
                                updateProgress(status.progress)
                            }
                        }
                        is ExportStatus.Success -> {
                            if (!isFinishing && !isDestroyed) {
                                exportState = ExportState.Completed
                                showSuccessState(status.savedUri)
                                Log.d(TAG, "Export successful: ${status.savedUri}")
                            }
                        }
                        is ExportStatus.Failure -> {
                            if (!isFinishing && !isDestroyed) {
                                exportState = ExportState.Failed
                                showFailureState(status.error)
                                Log.e(TAG, "Export failed: ${status.error}")
                            }
                        }
                        is ExportStatus.Cancelled -> {
                            if (!isFinishing && !isDestroyed) {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                if (exportState != ExportState.Interrupted) {
                                    exportState = ExportState.Idle
                                }
                                Toast.makeText(this@ExportActivity, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    // ── Cancel / Cleanup ──────────────────────────────────────────────────────

    private fun cancelExportInternal() {
        exportJob?.cancel()
        exportJob = null
        ExportService.cancelExport()
    }

    private fun cleanupTempFiles() {
        tempOutputPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Cleaned up temp output: $path")
            }
        }
        concatFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Cleaned up concat file: $path")
            }
        }
    }

    // ── UI Updates ────────────────────────────────────────────────────────────

    private fun updateProgress(progress: Int) {
        exportProgressBar.progress = progress
        tvProgressPercent.text = "$progress%"

        tvExportStatusTitle.text = when {
            progress < 20 -> getString(R.string.str_saving_video)
            progress < 80 -> "Rendering frames…"
            progress < 98 -> "Finalizing media…"
            else -> "Almost done…"
        }
    }

    private fun showSuccessState(uri: Uri?) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        savedVideoUri = uri

        layoutExporting.visibility = View.GONE
        layoutExportFailure.visibility = View.GONE
        layoutExportSuccess.visibility = View.VISIBLE

        tvExportHeaderTitle.text = "Export Complete"

        val sharedPreferences = getSharedPreferences("eclip_prefs", Context.MODE_PRIVATE)
        val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
        val customUriString = sharedPreferences.getString(prefKey, null)

        val folderLocationText = if (customUriString != null) {
            "Saved to Custom Directory"
        } else {
            if (isAudioOnly) "Saved to Music/Eclip" else "Saved to Movies/Eclip"
        }
        tvSavedFilePath.text = folderLocationText
    }

    private fun showFailureState(error: String) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        layoutExporting.visibility = View.GONE
        layoutExportSuccess.visibility = View.GONE
        layoutExportFailure.visibility = View.VISIBLE

        tvExportHeaderTitle.text = "Export Failed"
        tvErrorMessage.text = error
    }

    // ── Navigation Handling ───────────────────────────────────────────────────

    private fun handleExitRequest() {
        if (exportState == ExportState.Exporting) {
            confirmCancelExport()
        } else {
            finish()
        }
    }

    private fun confirmCancelExport() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Cancel Export?")
            .setMessage("Exporting is in progress. Do you want to cancel the export and go back?")
            .setPositiveButton("Yes, Cancel") { _, _ ->
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                exportState = ExportState.Cancelling
                cancelExportInternal()
                Toast.makeText(this, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Continue", null)
            .show()
    }

    // ── Save to Gallery (moved from ExportService) ────────────────────────────

    private fun saveVideoToGallery(videoFile: File, isAudioOnly: Boolean): Uri? {
        val mimeType = if (isAudioOnly) "audio/mpeg" else "video/mp4"
        val ext = if (isAudioOnly) ".mp3" else ".mp4"
        val prefix = if (isAudioOnly) "Eclip_Audio_" else "Eclip_"

        val sharedPreferences = getSharedPreferences("eclip_prefs", Context.MODE_PRIVATE)
        val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
        val customUriString = sharedPreferences.getString(prefKey, null)

        if (customUriString != null) {
            try {
                val treeUri = Uri.parse(customUriString)
                val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                )
                val newFileUri = android.provider.DocumentsContract.createDocument(
                    contentResolver,
                    parentUri,
                    mimeType,
                    "${prefix}${System.currentTimeMillis()}$ext"
                )
                if (newFileUri != null) {
                    contentResolver.openOutputStream(newFileUri)?.use { output ->
                        videoFile.inputStream().use { input -> input.copyTo(output) }
                    }
                    return newFileUri
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving to custom directory: ${e.message}, falling back to default", e)
            }
        }

        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}${System.currentTimeMillis()}$ext")
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (isAudioOnly) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Eclip")
                } else {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Eclip")
                }
            }
            val collectionUri = if (isAudioOnly) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = contentResolver.insert(collectionUri, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { output ->
                    videoFile.inputStream().use { input -> input.copyTo(output) }
                }
                it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to default gallery: ${e.message}", e)
            null
        }
    }

    // ── Play / Share ──────────────────────────────────────────────────────────

    private fun playExportedVideo() {
        val uri = savedVideoUri ?: return
        val mimeType = if (isAudioOnly) "audio/*" else "video/*"
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(viewIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open media", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareExportedVideo() {
        val uri = savedVideoUri ?: return
        val mimeType = if (isAudioOnly) "audio/*" else "video/*"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "Share exported media"))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share media", Toast.LENGTH_SHORT).show()
        }
    }
}
