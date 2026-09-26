package com.eclip.rup.leb.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.eclip.rup.leb.ExportActivity
import com.eclip.rup.leb.MainActivity
import com.eclip.rup.leb.R
import com.eclip.rup.leb.utils.AppCachePolicyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dedicated ForegroundService for running final video export renders.
 * Guarantees that long-running renders (5-15+ minutes) survive activity backgrounding,
 * screen-off states, and low-memory conditions without being killed by the Android OS.
 */
class ExportService : Service() {

    sealed class ExportStatus {
        object Idle : ExportStatus()
        object Starting : ExportStatus()
        data class Progress(val progress: Int) : ExportStatus()
        data class Success(val savedUri: Uri) : ExportStatus()
        data class Failure(val error: String) : ExportStatus()
        object Cancelled : ExportStatus()
    }

    companion object {
        private const val TAG = "ExportService"
        private const val CHANNEL_ID = "VideoExportChannel"
        private const val NOTIFICATION_ID = 2001
        private const val COMPLETION_NOTIFICATION_ID = 2002

        const val EXTRA_SOURCE_URI = "extra_source_uri"
        const val EXTRA_PROJECT_NAME = "extra_project_name"
        const val EXTRA_RESOLUTION = "extra_resolution"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_IS_AUDIO_ONLY = "extra_is_audio_only"
        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_TEMP_OUTPUT_PATH = "extra_temp_output_path"
        const val EXTRA_CONCAT_FILE_PATH = "extra_concat_file_path"
        const val EXTRA_TOTAL_DURATION_SECS = "extra_total_duration_secs"

        private val _exportStatus = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
        val exportStatus: StateFlow<ExportStatus> = _exportStatus.asStateFlow()

        @Volatile
        private var activeJob: Job? = null

        /**
         * Cancels the currently running export job and native FFmpeg sessions.
         */
        fun cancelExport() {
            activeJob?.cancel()
            activeJob = null
            try {
                FFmpegKit.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling FFmpegKit: ${e.message}")
            }
            _exportStatus.value = ExportStatus.Cancelled
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    override fun onCreate() {
        super.onCreate()
        ffmpegEngine = FFmpegRenderEngine(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val command = intent.getStringExtra(EXTRA_COMMAND)
        val tempOutputPath = intent.getStringExtra(EXTRA_TEMP_OUTPUT_PATH)
        val concatFilePath = intent.getStringExtra(EXTRA_CONCAT_FILE_PATH)
        val durationSecs = intent.getDoubleExtra(EXTRA_TOTAL_DURATION_SECS, 0.0)
        val isAudioOnly = intent.getBooleanExtra(EXTRA_IS_AUDIO_ONLY, false)
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: "Video"
        val resolution = intent.getIntExtra(EXTRA_RESOLUTION, 1080)
        if (resolution > 1080) {
            _exportStatus.value = ExportStatus.Failure("Resolution above 1080p is not supported.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (command == null || tempOutputPath == null || durationSecs <= 0.0) {
            _exportStatus.value = ExportStatus.Failure("Missing export parameters or invalid duration.")
            stopSelf()
            return START_NOT_STICKY
        }

        // 1. Promote to Foreground Service with ongoing notification
        val initialNotification = buildNotification(0, "Exporting $projectName...")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initialNotification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        _exportStatus.value = ExportStatus.Starting
        AppCachePolicyManager.setHeavyLoadActive(true)

        // 2. Launch export execution on IO dispatcher
        val job = serviceScope.launch {
            var lastProgressTime = 0L
            var lastReportedProgress = -1

            val tempFile = File(tempOutputPath)
            val concatFile = concatFilePath?.let { File(it) }

            try {
                val result = ffmpegEngine.exportFinal(
                    ffmpegCommand = command,
                    totalDurationSecs = durationSecs,
                    onProgress = { progress ->
                        // Conflated and throttled UI dispatch (max once per 150ms)
                        val now = SystemClock.uptimeMillis()
                        if (progress != lastReportedProgress && (now - lastProgressTime >= 150L || progress == 100)) {
                            lastProgressTime = now
                            lastReportedProgress = progress
                            _exportStatus.value = ExportStatus.Progress(progress)
                            updateNotification(progress, "Exporting $projectName...")
                        }
                    }
                )

                when (result) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        // Validate output file duration and stream integrity
                        val isValidMedia = if (tempFile.exists() && tempFile.length() > 0) {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(tempFile.absolutePath)
                                val extractedDurMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                                if (isAudioOnly) {
                                    extractedDurMs > 0L
                                } else {
                                    extractedDurMs > 0L && hasVideo == "yes"
                                }
                            } catch (e: Exception) {
                                tempFile.length() > 1024
                            } finally {
                                try { retriever.release() } catch (_: Exception) {}
                            }
                        } else false

                        if (!isValidMedia) {
                            if (tempFile.exists()) tempFile.delete()
                            if (concatFile?.exists() == true) concatFile.delete()
                            _exportStatus.value = ExportStatus.Failure("Export generated zero-duration video.")
                            showCompletionNotification("Export Failed", "Export generated zero-duration video.", null)
                            return@launch
                        }

                        // Atomic save: temp file -> final gallery destination
                        val savedUri = saveVideoToGallery(tempFile, isAudioOnly)

                        if (tempFile.exists()) tempFile.delete()
                        if (concatFile?.exists() == true) concatFile.delete()

                        if (savedUri != null) {
                            _exportStatus.value = ExportStatus.Success(savedUri)
                            showCompletionNotification("Export Complete", "Saved to gallery", savedUri)
                            Log.d(TAG, "Export completed successfully: $savedUri")
                        } else {
                            _exportStatus.value = ExportStatus.Failure("Failed to save media to gallery.")
                            showCompletionNotification("Export Failed", "Failed to save media to gallery.", null)
                        }
                    }

                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        if (tempFile.exists()) tempFile.delete()
                        if (concatFile?.exists() == true) concatFile.delete()
                        val displayMsg = result.userMessage ?: "Export failed due to a technical error."
                        _exportStatus.value = ExportStatus.Failure(displayMsg)
                        showCompletionNotification("Export Failed", displayMsg, null)
                        Log.e(TAG, "Export failed: ${result.error}")
                    }

                    is FFmpegRenderEngine.RenderResult.Cancelled -> {
                        if (tempFile.exists()) tempFile.delete()
                        if (concatFile?.exists() == true) concatFile.delete()
                        _exportStatus.value = ExportStatus.Cancelled
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    _exportStatus.value = ExportStatus.Cancelled
                } else {
                    Log.e(TAG, "Export exception", e)
                    _exportStatus.value = ExportStatus.Failure(e.message ?: "Unknown error")
                    showCompletionNotification("Export Failed", e.message ?: "Unknown error", null)
                }
            } finally {
                withContext(NonCancellable) {
                    if (tempFile.exists() && _exportStatus.value !is ExportStatus.Success) {
                        tempFile.delete()
                    }
                    if (concatFile?.exists() == true && _exportStatus.value !is ExportStatus.Success) {
                        concatFile.delete()
                    }
                }
                AppCachePolicyManager.setHeavyLoadActive(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        activeJob = job

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Export",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of exporting videos and media files"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: Int, title: String): Notification {
        val intent = Intent(this, ExportActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (progress > 0) "$progress%" else "Preparing...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(progress: Int, title: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(progress, title))
    }

    private fun showCompletionNotification(title: String, message: String, videoUri: Uri?) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (videoUri != null) {
                setDataAndType(videoUri, if (message.contains("Audio", true)) "audio/*" else "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                setClass(this@ExportService, MainActivity::class.java)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

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

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
