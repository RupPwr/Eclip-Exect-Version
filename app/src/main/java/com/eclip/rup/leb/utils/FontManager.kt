package com.eclip.rup.leb.utils

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class FontItem(
    val name: String,
    val path: String,
    val isCustom: Boolean = false,
    val typeface: Typeface? = null,
    val category: String = FontManager.CATEGORY_ALL,
    val sampleText: String = "Aa",
    val assetFileName: String? = null
)

object FontManager {
    private const val TAG = "FontManager"
    private const val CUSTOM_FONTS_DIR = "custom_fonts"
    private const val BUNDLED_FONTS_DIR = "bundled_fonts"

    // Categories (English / Latin Styles + Custom)
    const val CATEGORY_ALL = "All"
    const val CATEGORY_SANS = "Sans Serif"
    const val CATEGORY_SERIF = "Serif"
    const val CATEGORY_SCRIPT = "Script"
    const val CATEGORY_DISPLAY = "Display"
    const val CATEGORY_CUSTOM = "Custom"

    val CATEGORIES = listOf(
        CATEGORY_ALL,
        CATEGORY_SANS,
        CATEGORY_SERIF,
        CATEGORY_SCRIPT,
        CATEGORY_DISPLAY,
        CATEGORY_CUSTOM
    )

    private val typefaceCache = ConcurrentHashMap<String, Typeface>()

    // Bundled font metadata definitions
    private data class BundledFontMeta(
        val assetFile: String,
        val displayName: String,
        val category: String,
        val sampleText: String
    )

    private val BUNDLED_FONT_CATALOG = listOf(
        // Sans Serif (Clean & Modern)
        BundledFontMeta("Roboto-Regular.ttf", "Roboto", CATEGORY_SANS, "Roboto"),
        BundledFontMeta("Montserrat-Regular.ttf", "Montserrat", CATEGORY_SANS, "Montserrat"),
        BundledFontMeta("Oswald-Regular.ttf", "Oswald", CATEGORY_SANS, "OSWALD"),

        // Display (Bold, Impact & Punchy)
        BundledFontMeta("BebasNeue-Regular.ttf", "Bebas Neue", CATEGORY_DISPLAY, "BEBAS"),
        BundledFontMeta("Anton-Regular.ttf", "Anton", CATEGORY_DISPLAY, "ANTON"),
        BundledFontMeta("Righteous-Regular.ttf", "Righteous", CATEGORY_DISPLAY, "Righteous"),
        BundledFontMeta("Bangers-Regular.ttf", "Bangers", CATEGORY_DISPLAY, "BANGERS!"),
        BundledFontMeta("AlfaSlabOne-Regular.ttf", "Alfa Slab One", CATEGORY_DISPLAY, "ALFA SLAB"),

        // Script & Handwriting (Cursive & Elegant)
        BundledFontMeta("Pacifico-Regular.ttf", "Pacifico", CATEGORY_SCRIPT, "Pacifico"),
        BundledFontMeta("DancingScript-Regular.ttf", "Dancing Script", CATEGORY_SCRIPT, "Dancing"),
        BundledFontMeta("Lobster-Regular.ttf", "Lobster", CATEGORY_SCRIPT, "Lobster"),
        BundledFontMeta("Caveat-Regular.ttf", "Caveat", CATEGORY_SCRIPT, "Caveat"),
        BundledFontMeta("GreatVibes-Regular.ttf", "Great Vibes", CATEGORY_SCRIPT, "Great Vibes"),
        BundledFontMeta("Courgette-Regular.ttf", "Courgette", CATEGORY_SCRIPT, "Courgette"),
        BundledFontMeta("Sacramento-Regular.ttf", "Sacramento", CATEGORY_SCRIPT, "Sacramento"),

        // Serif (Classic & Editorial)
        BundledFontMeta("PlayfairDisplay-Regular.ttf", "Playfair Display", CATEGORY_SERIF, "Playfair"),
        BundledFontMeta("Cinzel-Regular.ttf", "Cinzel", CATEGORY_SERIF, "CINZEL"),
        BundledFontMeta("AbrilFatface-Regular.ttf", "Abril Fatface", CATEGORY_SERIF, "Abril Fatface")
    )

    fun getCustomFontsDir(context: Context): File {
        val dir = File(context.filesDir, CUSTOM_FONTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getBundledFontsDir(context: Context): File {
        val dir = File(context.filesDir, BUNDLED_FONTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Copies an asset font file to internal storage and returns its absolute path.
     */
    fun ensureAssetFontExtracted(context: Context, assetFileName: String): String? {
        return try {
            val destDir = getBundledFontsDir(context)
            val destFile = File(destDir, assetFileName)
            if (!destFile.exists() || destFile.length() == 0L) {
                context.assets.open("fonts/$assetFileName").use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting asset font $assetFileName", e)
            null
        }
    }

    fun getTypeface(path: String?): Typeface {
        if (path.isNullOrBlank()) return Typeface.DEFAULT
        return typefaceCache.getOrPut(path) {
            try {
                Typeface.createFromFile(path) ?: Typeface.DEFAULT
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create typeface from $path", e)
                Typeface.DEFAULT
            }
        }
    }

    fun importFontFromUri(context: Context, uri: Uri): FontItem? {
        return try {
            val fileName = getFileNameFromUri(context, uri) ?: "Font_${System.currentTimeMillis()}.ttf"
            val sanitizedName = sanitizeFileName(fileName)
            val destinationDir = getCustomFontsDir(context)
            val destFile = File(destinationDir, sanitizedName)

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            val typeface = try {
                Typeface.createFromFile(destFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load typeface from imported font: ${destFile.absolutePath}", e)
                destFile.delete()
                return null
            }

            typefaceCache[destFile.absolutePath] = typeface

            val fontName = destFile.nameWithoutExtension
                .replace("_", " ")
                .replace("-", " ")

            FontItem(
                name = fontName,
                path = destFile.absolutePath,
                isCustom = true,
                typeface = typeface,
                category = CATEGORY_CUSTOM,
                sampleText = "Aa"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import font from URI: $uri", e)
            null
        }
    }

    fun getCustomFonts(context: Context): List<FontItem> {
        val dir = getCustomFontsDir(context)
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { file ->
            file.isFile && file.extension.lowercase(Locale.US) in setOf("ttf", "otf")
        }.sortedBy { it.nameWithoutExtension.lowercase(Locale.US) }
        .mapNotNull { file ->
            val tf = getTypeface(file.absolutePath)
            FontItem(
                name = file.nameWithoutExtension.replace("_", " ").replace("-", " "),
                path = file.absolutePath,
                isCustom = true,
                typeface = tf,
                category = CATEGORY_CUSTOM,
                sampleText = "Aa"
            )
        }
    }

    /**
     * Get all available fonts: bundled fonts + custom imported fonts + system fonts (if any).
     */
    fun getAllFonts(context: Context, defaultFontPath: String? = null): List<FontItem> {
        val fonts = mutableListOf<FontItem>()
        val pathsSeen = mutableSetOf<String>()

        // 1. Bundled fonts
        for (meta in BUNDLED_FONT_CATALOG) {
            val extractedPath = ensureAssetFontExtracted(context, meta.assetFile)
            if (extractedPath != null) {
                val tf = getTypeface(extractedPath)
                fonts.add(
                    FontItem(
                        name = meta.displayName,
                        path = extractedPath,
                        isCustom = false,
                        typeface = tf,
                        category = meta.category,
                        sampleText = meta.sampleText,
                        assetFileName = meta.assetFile
                    )
                )
                pathsSeen.add(extractedPath)
            }
        }

        // 2. Custom fonts
        getCustomFonts(context).forEach { item ->
            if (!pathsSeen.contains(item.path)) {
                fonts.add(item)
                pathsSeen.add(item.path)
            }
        }

        // Register bundled fonts directory with FFmpeg
        try {
            val bundledDir = getBundledFontsDir(context)
            val fontMap = mutableMapOf<String, String>()
            bundledDir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    fontMap[f.nameWithoutExtension] = f.name
                }
            }
            FFmpegKitConfig.setFontDirectory(context, bundledDir.absolutePath, fontMap)
        } catch (e: Exception) {
            Log.w(TAG, "FFmpeg font dir setup warning: ${e.message}")
        }

        return fonts
    }

    fun getFontsByCategory(context: Context, category: String, defaultFontPath: String? = null): List<FontItem> {
        val all = getAllFonts(context, defaultFontPath)
        if (category == CATEGORY_ALL) return all
        if (category == CATEGORY_CUSTOM) return all.filter { it.isCustom }
        return all.filter { it.category == category }
    }

    fun deleteCustomFont(context: Context, fontPath: String): Boolean {
        return try {
            val file = File(fontPath)
            if (file.exists() && file.parentFile?.name == CUSTOM_FONTS_DIR) {
                val deleted = file.delete()
                typefaceCache.remove(fontPath)
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting custom font: $fontPath", e)
            false
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = cursor.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.let { File(it).name }
        }
        return name
    }

    private fun sanitizeFileName(name: String): String {
        val ext = if (name.endsWith(".otf", ignoreCase = true)) ".otf" else ".ttf"
        val baseName = name.substringBeforeLast(".").replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$baseName$ext"
    }
}
