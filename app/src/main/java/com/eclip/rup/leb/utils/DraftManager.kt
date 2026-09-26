package com.eclip.rup.leb.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.eclip.rup.leb.models.EditRecipe
import com.eclip.rup.leb.models.VideoProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class DraftItem(
    val id: String,
    val title: String,
    val sourceUri: Uri?,
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

    private val draftScope = CoroutineScope(SupervisorJob() + AppDispatchers.DiskIo)

    fun saveDraftAsync(
        context: Context,
        draftId: String,
        project: VideoProject,
        durationMs: Long,
        title: String? = null,
        onComplete: ((DraftItem?) -> Unit)? = null
    ): Job {
        val appContext = context.applicationContext
        return draftScope.launch {
            val item = saveDraft(appContext, draftId, project, durationMs, title)
            if (onComplete != null) {
                withContext(AppDispatchers.Main) {
                    onComplete(item)
                }
            }
        }
    }

    fun generateUniqueDraftId(): String {
        val randomPart = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        return "draft_${System.currentTimeMillis()}_$randomPart"
    }

    fun getDraftsDirectory(context: Context): File {
        val dir = File(context.filesDir, DRAFTS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun extractFileNameFromUri(uri: Uri?): String {
        if (uri == null) return ""
        val segment = try {
            uri.lastPathSegment?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        if (segment != null) return segment

        val uriStr = try {
            uri.toString()
        } catch (_: Throwable) {
            ""
        }
        return extractFileNameFromUriString(uriStr)
    }

    fun extractFileNameFromUriString(uriStr: String?): String {
        if (uriStr.isNullOrBlank()) return ""
        val cleanStr = uriStr.substringBefore('?').substringBefore('#').trimEnd('/')
        val lastSlash = cleanStr.lastIndexOf('/')
        return if (lastSlash >= 0 && lastSlash < cleanStr.length - 1) {
            cleanStr.substring(lastSlash + 1)
        } else {
            cleanStr
        }
    }

    suspend fun saveDraft(
        context: Context,
        draftId: String,
        project: VideoProject,
        durationMs: Long,
        title: String? = null
    ): DraftItem? {
        return try {
            val dir = getDraftsDirectory(context)
            val cleanId = if (draftId.isNotBlank()) draftId else generateUniqueDraftId()
            val projectFile = File(dir, "$cleanId.json")
            val thumbFile = File(dir, "${cleanId}_thumb.jpg")

            val rawSourceName = project.sourceName ?: ""
            val sourceName = rawSourceName.ifBlank {
                extractFileNameFromUri(project.sourceUri)
            }
            val projectName = (title ?: "").ifBlank {
                sourceName.ifBlank { "Draft ${cleanId.takeLast(4)}" }
            }
            val recipe = EditRecipe(
                projectName = projectName,
                sourceUri = project.sourceUri ?: Uri.EMPTY,
                sourceName = sourceName,
                scrubProxyUri = project.scrubProxyUri,
                operations = project.operations ?: emptyList(),
                durationMs = durationMs,
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

    suspend fun generateThumbnail(context: Context, uri: Uri?, outputFile: File) {
        if (uri == null || uri.toString().isBlank()) return
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val bitmap = com.eclip.rup.leb.utils.CodecInstancePool.withHardwareCodecPermit {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
            }
            if (bitmap != null) {
                val maxDim = 360
                val width = bitmap.width
                val height = bitmap.height
                val scaled = if (width > maxDim || height > maxDim) {
                    val ratio = width.toFloat() / height.toFloat()
                    val targetW = if (ratio >= 1.0f) maxDim else (maxDim * ratio).toInt()
                    val targetH = if (ratio >= 1.0f) (maxDim / ratio).toInt() else maxDim
                    Bitmap.createScaledBitmap(bitmap, targetW.coerceAtLeast(1), targetH.coerceAtLeast(1), true)
                } else {
                    bitmap
                }
                FileOutputStream(outputFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                }
                if (scaled != bitmap) {
                    scaled.recycle()
                }
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not generate thumbnail for $uri: ${e.message}")
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
    }

    fun getAllDrafts(context: Context): List<DraftItem> {
        val dir = getDraftsDirectory(context)
        val jsonFiles = dir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        val list = mutableListOf<DraftItem>()

        for (file in jsonFiles) {
            try {
                val json = file.readText()
                val cleanId = file.nameWithoutExtension
                val thumbFile = File(dir, "${cleanId}_thumb.jpg")

                var title = "Draft ${cleanId.takeLast(4)}"
                var sourceUri = Uri.EMPTY
                var durationMs = 0L
                var lastModified = file.lastModified()

                try {
                    val recipe = ProjectSerializer.deserialize(json)
                    sourceUri = recipe.sourceUri ?: Uri.EMPTY
                    var uriFallback = extractFileNameFromUri(sourceUri)
                    if (uriFallback.isBlank()) {
                        try {
                            val jsonObj = com.google.gson.JsonParser.parseString(json).asJsonObject
                            if (jsonObj.has("sourceUri") && !jsonObj.get("sourceUri").isJsonNull) {
                                uriFallback = extractFileNameFromUriString(jsonObj.get("sourceUri").asString)
                            }
                        } catch (_: Exception) {}
                    }
                    val pName = recipe.projectName ?: ""
                    val sName = recipe.sourceName ?: ""
                    title = pName.ifBlank { sName.ifBlank { uriFallback.ifBlank { title } } }
                    durationMs = recipe.durationMs
                    lastModified = if (recipe.lastModifiedAt > 0L) recipe.lastModifiedAt else lastModified

                    if (durationMs <= 0L && recipe.operations != null) {
                        for (op in recipe.operations) {
                            if (op is com.eclip.rup.leb.models.EditOperation.Trim) {
                                durationMs = op.endMs - op.startMs
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Standard deserialize failed for ${file.name}, trying fallback: ${e.message}")
                    try {
                        val jsonObj = com.google.gson.JsonParser.parseString(json).asJsonObject
                        var uriStr = ""
                        if (jsonObj.has("sourceUri") && !jsonObj.get("sourceUri").isJsonNull) {
                            uriStr = jsonObj.get("sourceUri").asString
                            sourceUri = try { Uri.parse(uriStr) ?: Uri.EMPTY } catch (_: Exception) { Uri.EMPTY }
                        }
                        val uriFallback = extractFileNameFromUriString(uriStr)
                        val pName = if (jsonObj.has("projectName") && !jsonObj.get("projectName").isJsonNull) jsonObj.get("projectName").asString else ""
                        val sName = if (jsonObj.has("sourceName") && !jsonObj.get("sourceName").isJsonNull) jsonObj.get("sourceName").asString else ""
                        title = pName.ifBlank { sName.ifBlank { uriFallback.ifBlank { title } } }

                        if (jsonObj.has("durationMs") && !jsonObj.get("durationMs").isJsonNull) {
                            durationMs = jsonObj.get("durationMs").asLong
                        }
                        if (jsonObj.has("lastModifiedAt") && !jsonObj.get("lastModifiedAt").isJsonNull) {
                            lastModified = jsonObj.get("lastModifiedAt").asLong
                        }
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback parse failed for ${file.name}", e2)
                    }
                }

                list.add(
                    DraftItem(
                        id = cleanId,
                        title = title,
                        sourceUri = sourceUri,
                        projectFile = file,
                        thumbnailFile = if (thumbFile.exists() && thumbFile.length() > 0L) thumbFile else null,
                        durationMs = durationMs,
                        lastModified = lastModified
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read draft file ${file.name}", e)
            }
        }

        return list.sortedByDescending { it.lastModified }
    }

    private const val PROJECT_ASSETS_DIR_NAME = "project_assets"

    fun getProjectAssetsDirectory(context: Context): File {
        val dir = File(context.filesDir, PROJECT_ASSETS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun deleteDraft(context: Context, draftId: String): Boolean {
        return try {
            val dir = getDraftsDirectory(context)
            val projectFile = File(dir, "$draftId.json")
            val thumbFile = File(dir, "${draftId}_thumb.jpg")

            // Clean up any referenced assets in project_assets directory
            if (projectFile.exists()) {
                try {
                    val json = projectFile.readText()
                    val recipe = ProjectSerializer.deserialize(json)
                    val assetsDir = getProjectAssetsDirectory(context)
                    val assetsCanonical = assetsDir.canonicalPath

                    val candidateAssetPaths = mutableSetOf<String>()
                    recipe.operations?.forEach { op ->
                        when (op) {
                            is com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio -> {
                                op.audioUri.path?.let { candidateAssetPaths.add(it) }
                            }
                            is com.eclip.rup.leb.models.EditOperation.AddImageOverlay -> {
                                op.imageUri.path?.let { candidateAssetPaths.add(it) }
                            }
                            is com.eclip.rup.leb.models.EditOperation.CanvasBackground -> {
                                op.imageUri?.path?.let { candidateAssetPaths.add(it) }
                            }
                            is com.eclip.rup.leb.models.EditOperation.Merge -> {
                                op.items.forEach { it.uri.path?.let { p -> candidateAssetPaths.add(p) } }
                            }
                            else -> {}
                        }
                    }

                    for (p in candidateAssetPaths) {
                        try {
                            val f = File(p)
                            if (f.exists() && f.canonicalPath.startsWith(assetsCanonical)) {
                                f.delete()
                            }
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not delete associated assets for draft $draftId: ${e.message}")
                }
            }

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
