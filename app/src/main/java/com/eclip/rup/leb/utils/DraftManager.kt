package com.eclip.rup.leb.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.eclip.rup.leb.models.EditRecipe
import com.eclip.rup.leb.models.VideoProject
import java.io.File
import java.io.FileOutputStream

data class DraftItem(
    val id: String,
    val title: String,
    val sourceUri: Uri,
    val projectFile: File,
    val thumbnailFile: File?,
    val durationMs: Long,
    val lastModified: Long
) {
    fun getFormattedDuration(): String {
        if (durationMs <= 0L) return "0:00"
        val totalSecs = (durationMs / 1000).toInt()
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        val tenths = ((durationMs % 1000) / 100).toInt()
        return if (mins > 0) {
            String.format("%d:%02d.%d", mins, secs, tenths)
        } else {
            String.format("0:%02d.%d", secs, tenths)
        }
    }
}

object DraftManager {
    private const val TAG = "DraftManager"
    private const val DRAFTS_DIR_NAME = "drafts"

    fun getDraftsDirectory(context: Context): File {
        val dir = File(context.filesDir, DRAFTS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveDraft(
        context: Context,
        draftId: String,
        project: VideoProject,
        durationMs: Long,
        title: String? = null
    ): DraftItem? {
        return try {
            val dir = getDraftsDirectory(context)
            val cleanId = if (draftId.isNotBlank()) draftId else "draft_${System.currentTimeMillis()}"
            val projectFile = File(dir, "$cleanId.json")
            val thumbFile = File(dir, "${cleanId}_thumb.jpg")

            val projectName = title ?: project.sourceName.ifBlank { "Draft ${cleanId.takeLast(4)}" }
            val recipe = EditRecipe(
                projectName = projectName,
                sourceUri = project.sourceUri,
                sourceName = project.sourceName,
                scrubProxyUri = project.scrubProxyUri,
                operations = project.operations,
                createdAt = project.createdAt,
                lastModifiedAt = System.currentTimeMillis()
            )

            val json = ProjectSerializer.serialize(recipe)
            projectFile.writeText(json)

            // Extract and save thumbnail if not existing
            if (!thumbFile.exists() || thumbFile.length() == 0L) {
                generateThumbnail(context, project.sourceUri, thumbFile)
            }

            DraftItem(
                id = cleanId,
                title = projectName,
                sourceUri = project.sourceUri,
                projectFile = projectFile,
                thumbnailFile = if (thumbFile.exists() && thumbFile.length() > 0L) thumbFile else null,
                durationMs = durationMs,
                lastModified = recipe.lastModifiedAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save draft $draftId", e)
            null
        }
    }

    private fun generateThumbnail(context: Context, uri: Uri, outputFile: File) {
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            if (bitmap != null) {
                FileOutputStream(outputFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                }
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not generate thumbnail for $uri: ${e.message}")
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {}
        }
    }

    fun getAllDrafts(context: Context): List<DraftItem> {
        val dir = getDraftsDirectory(context)
        val jsonFiles = dir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        val list = mutableListOf<DraftItem>()

        for (file in jsonFiles) {
            try {
                val json = file.readText()
                val recipe = ProjectSerializer.deserialize(json)
                val cleanId = file.nameWithoutExtension
                val thumbFile = File(dir, "${cleanId}_thumb.jpg")

                var durationMs = 0L
                for (op in recipe.operations) {
                    if (op is com.eclip.rup.leb.models.EditOperation.Trim) {
                        durationMs = op.endMs - op.startMs
                    }
                }

                // If duration is 0, attempt to retrieve duration from video source
                if (durationMs <= 0L) {
                    var retriever: MediaMetadataRetriever? = null
                    try {
                        retriever = MediaMetadataRetriever()
                        retriever.setDataSource(context, recipe.sourceUri)
                        val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        if (durStr != null) {
                            durationMs = durStr.toLongOrNull() ?: 0L
                        }
                    } catch (e: Exception) {
                        // ignore
                    } finally {
                        try {
                            retriever?.release()
                        } catch (e: Exception) {}
                    }
                }

                list.add(
                    DraftItem(
                        id = cleanId,
                        title = recipe.projectName.ifBlank { recipe.sourceName },
                        sourceUri = recipe.sourceUri,
                        projectFile = file,
                        thumbnailFile = if (thumbFile.exists() && thumbFile.length() > 0L) thumbFile else null,
                        durationMs = durationMs,
                        lastModified = recipe.lastModifiedAt
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read draft file ${file.name}", e)
            }
        }

        return list.sortedByDescending { it.lastModified }
    }

    fun deleteDraft(context: Context, draftId: String): Boolean {
        return try {
            val dir = getDraftsDirectory(context)
            val projectFile = File(dir, "$draftId.json")
            val thumbFile = File(dir, "${draftId}_thumb.jpg")
            val pDeleted = if (projectFile.exists()) projectFile.delete() else true
            val tDeleted = if (thumbFile.exists()) thumbFile.delete() else true
            pDeleted && tDeleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete draft $draftId", e)
            false
        }
    }

    fun getDraftFileUri(context: Context, draftId: String): Uri {
        val dir = getDraftsDirectory(context)
        val file = File(dir, "$draftId.json")
        return Uri.fromFile(file)
    }
}
