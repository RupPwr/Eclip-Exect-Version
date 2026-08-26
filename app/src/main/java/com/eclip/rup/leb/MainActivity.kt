package com.eclip.rup.leb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Parcelable
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.eclip.rup.leb.databinding.ActivityMainBinding
import com.eclip.rup.leb.utils.ErrorCode
import com.eclip.rup.leb.utils.setBounceClickListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.e("VideoSelection", "Could not take persistable permission", e)
                }
                Log.d("VideoSelection", "Video selected: $uri")
                navigateToEditingScreen(uri)
            } else {
                Log.e("VideoSelectionError", "No video selected")
            }
        }

    private val openProjectLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.e("ProjectSelection", "Could not take persistable permission for project URI", e)
                }
                Log.d("ProjectSelection", "Project selected: $uri")
                val intent = Intent(this, ProjectImportActivity::class.java).apply {
                    putExtra("PROJECT_URI", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(intent)
            } else {
                Log.e("ProjectSelectionError", "No project selected")
            }
        }

    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences("eclip_prefs", MODE_PRIVATE)
                    prefs.edit().putString("export_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentExportFolder, R.string.str_default_movies_eclip)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    private val selectAudioFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences("eclip_prefs", MODE_PRIVATE)
                    prefs.edit().putString("export_audio_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentAudioExportFolder, R.string.str_default_music_eclip)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    private val selectSnapshotFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences("eclip_prefs", MODE_PRIVATE)
                    prefs.edit().putString("export_snapshot_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_eclip)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    private lateinit var draftsAdapter: DraftsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle Back press to navigate from Selection Mode to Normal, or from Settings to Home
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (draftsAdapter.isSelectionMode) {
                    exitDraftSelectionMode()
                } else if (binding.layoutSettingsContent.visibility == View.VISIBLE) {
                    showHomeScreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Header Settings Button
        binding.btnOpenSettings.setBounceClickListener {
            showSettingsScreen()
        }

        // Settings Screen Back Button
        binding.btnBackFromSettings.setBounceClickListener {
            showHomeScreen()
        }

        // Hero Card: New Project
        binding.btnImport.setBounceClickListener {
            Log.d("ButtonClick", "Launching video selection.")
            selectVideo()
        }

        // Drafts Section Setup
        binding.btnDraftsInfo.setBounceClickListener {
            showDraftsInfoDialog()
        }

        draftsAdapter = DraftsAdapter(
            drafts = emptyList(),
            onDraftClick = { draft -> openDraft(draft) },
            onDraftDelete = { draft -> confirmDeleteDraft(draft) }
        )
        draftsAdapter.onSelectionChangedListener = { selectedCount, totalCount ->
            updateDraftSelectionUI(selectedCount, totalCount)
        }
        binding.rvDrafts.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)
        binding.rvDrafts.adapter = draftsAdapter
        loadDrafts()

        // Draft Selection Mode Toggle Button (Three-line icon in Drafts header)
        binding.btnToggleDraftSelection.setBounceClickListener {
            if (draftsAdapter.isSelectionMode) {
                exitDraftSelectionMode()
            } else {
                enterDraftSelectionMode()
            }
        }

        // Draft Selection Actions: Select All & Delete
        binding.btnSelectAllDrafts.setBounceClickListener {
            if (draftsAdapter.isAllSelected()) {
                draftsAdapter.deselectAll()
            } else {
                draftsAdapter.selectAll()
            }
        }

        binding.btnDeleteSelectedDrafts.setBounceClickListener {
            val selected = draftsAdapter.getSelectedDrafts()
            if (selected.isEmpty()) {
                showToast("No draft selected")
            } else {
                confirmDeleteMultipleDrafts(selected)
            }
        }
        
        // Setup Settings Actions
        binding.btnChangeExportFolder.setBounceClickListener {
            selectFolderLauncher.launch(null)
        }
        binding.btnChangeAudioExportFolder.setBounceClickListener {
            selectAudioFolderLauncher.launch(null)
        }
        binding.btnChangeSnapshotExportFolder.setBounceClickListener {
            selectSnapshotFolderLauncher.launch(null)
        }
        binding.btnChangeLanguage.setBounceClickListener {
            showLanguageDialog()
        }
        
        binding.btnAboutAppInfo.setBounceClickListener {
            showAboutAppDialog()
        }

        binding.btnCheckForUpdates.setBounceClickListener {
            checkForUpdates()
        }
        
        binding.btnOpenSourceLicenses.setBounceClickListener {
            com.mikepenz.aboutlibraries.LibsBuilder()
                .withActivityTitle(getString(R.string.str_open_source_licenses))
                .withSearchEnabled(true)
                .start(this)
        }
        
        // Initialize Settings UI
        val prefs = getSharedPreferences("eclip_prefs", MODE_PRIVATE)
        val savedUriString = prefs.getString("export_directory_uri", null)
        if (savedUriString != null) {
            updateExportFolderUI(Uri.parse(savedUriString), binding.tvCurrentExportFolder, R.string.str_default_movies_eclip)
        } else {
            updateExportFolderUI(null, binding.tvCurrentExportFolder, R.string.str_default_movies_eclip)
        }

        val savedAudioUriString = prefs.getString("export_audio_directory_uri", null)
        if (savedAudioUriString != null) {
            updateExportFolderUI(Uri.parse(savedAudioUriString), binding.tvCurrentAudioExportFolder, R.string.str_default_music_eclip)
        } else {
            updateExportFolderUI(null, binding.tvCurrentAudioExportFolder, R.string.str_default_music_eclip)
        }

        val savedSnapshotUriString = prefs.getString("export_snapshot_directory_uri", null)
        if (savedSnapshotUriString != null) {
            updateExportFolderUI(Uri.parse(savedSnapshotUriString), binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_eclip)
        } else {
            updateExportFolderUI(null, binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_eclip)
        }

        updateLanguageUI()

        // Initialize Haptic Feedback preference
        updateHapticFeedbackUI()

        binding.btnToggleHapticFeedback.setBounceClickListener {
            val current = prefs.getBoolean("haptic_feedback", true)
            prefs.edit().putBoolean("haptic_feedback", !current).apply()
            updateHapticFeedbackUI()
        }

        // Initialize Fullscreen Editor preference
        updateFullscreenEditorUI()

        binding.btnToggleFullscreenEditor.setBounceClickListener {
            val current = prefs.getBoolean("fullscreen_editor", true)
            prefs.edit().putBoolean("fullscreen_editor", !current).apply()
            updateFullscreenEditorUI()
        }

        // Initialize Default Encoder preference
        updateEncoderUI()

        binding.btnChangeDefaultEncoder.setBounceClickListener {
            showEncoderDialog()
        }

        // Set dynamic About version tag
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvAboutVersion.text = "v${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvAboutVersion.text = "v1.0-beta5"
        }

        // Onboarding / Welcome Dialog
        val isFirstLaunch = prefs.getBoolean("first_launch_v1", true)
        if (isFirstLaunch) {
            showOnboardingDialog(prefs)
        }

        // Handle shared/intent videos
        handleIntent(intent)
    }

    private fun updateExportFolderUI(uri: Uri?, textView: TextView, defaultStringResId: Int) {
        if (uri == null) {
            textView.text = getString(defaultStringResId)
        } else {
            try {
                val path = uri.lastPathSegment?.split(":")?.lastOrNull()
                if (!path.isNullOrEmpty()) {
                    textView.text = path
                } else {
                    textView.text = getString(R.string.str_custom_directory)
                }
            } catch (e: Exception) {
                textView.text = getString(R.string.str_custom_directory)
            }
        }
    }

    private data class LanguageItem(
        val tag: String,
        val displayName: String
    )

    private fun getAvailableLanguages(): List<LanguageItem> {
        val result = mutableListOf<LanguageItem>()
        result.add(LanguageItem("", getString(R.string.str_system_default)))

        val tags = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeConfig = android.app.LocaleConfig(this)
                val locales = localeConfig.supportedLocales
                if (locales != null) {
                    for (i in 0 until locales.size()) {
                        val locale = locales.get(i)
                        if (locale != null) {
                            tags.add(locale.toLanguageTag())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "LocaleConfig error", e)
            }
        }

        if (tags.isEmpty()) {
            try {
                val resId = resources.getIdentifier("_generated_res_locale_config", "xml", packageName)
                if (resId != 0) {
                    val parser = resources.getXml(resId)
                    var eventType = parser.eventType
                    while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale") {
                            val name = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                            if (!name.isNullOrEmpty()) {
                                tags.add(name)
                            }
                        }
                        eventType = parser.next()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "XmlParser _generated_res_locale_config error", e)
            }
        }

        if (tags.isEmpty()) {
            tags.addAll(listOf("en", "de", "et", "sk", "pt-BR"))
        }

        val items = tags.map { tag ->
            val locale = java.util.Locale.forLanguageTag(tag)
            val name = when (tag.lowercase()) {
                "pt-br" -> "Português (Brasil)"
                "zh-cn" -> "中文 (简体)"
                "zh-tw" -> "中文 (繁體)"
                else -> locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            }
            LanguageItem(tag, name)
        }.sortedBy { it.displayName.lowercase() }

        result.addAll(items)
        return result
    }

    private fun updateLanguageUI() {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty) {
            binding.tvCurrentLanguage.text = getString(R.string.str_system_default)
        } else {
            val locale = currentLocales.get(0)
            val tag = locale?.toLanguageTag() ?: ""
            val name = when (tag.lowercase()) {
                "pt-br" -> "Português (Brasil)"
                else -> locale?.getDisplayName(locale)?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            }
            binding.tvCurrentLanguage.text = name ?: getString(R.string.str_system_default)
        }
    }

    private fun updateHapticFeedbackUI() {
        val prefs = getSharedPreferences("eclip_prefs", MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("haptic_feedback", true)
        binding.tvCurrentHapticFeedback.text = if (isEnabled) {
            getString(R.string.str_haptic_enabled_desc)
        } else {
            getString(R.string.str_haptic_disabled_desc)
        }
    }

    private fun updateFullscreenEditorUI() {
        val prefs = getSharedPreferences("eclip_prefs", MODE_PRIVATE)
        val isFullscreen = prefs.getBoolean("fullscreen_editor", true)
        binding.tvCurrentFullscreenEditor.text = if (isFullscreen) {
            getString(R.string.str_fullscreen_enabled_desc)
        } else {
            getString(R.string.str_fullscreen_disabled_desc)
        }
    }

    private fun updateEncoderUI() {
        val prefs = getSharedPreferences("eclip_prefs", MODE_PRIVATE)
        val defaultEncoder = prefs.getString("default_encoder", "hardware") ?: "hardware"
        if (defaultEncoder == "software") {
            binding.tvCurrentDefaultEncoder.text = getString(R.string.str_encoder_software)
        } else {
            binding.tvCurrentDefaultEncoder.text = getString(R.string.str_encoder_hardware)
        }
    }

    private fun showEncoderDialog() {
        val prefs = getSharedPreferences("eclip_prefs", MODE_PRIVATE)
        val currentEncoder = prefs.getString("default_encoder", "hardware") ?: "hardware"
        val options = arrayOf(
            getString(R.string.str_encoder_hardware),
            getString(R.string.str_encoder_software)
        )
        val selectedIndex = if (currentEncoder == "software") 1 else 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.str_default_encoder)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val chosenEncoder = if (which == 1) "software" else "hardware"
                prefs.edit().putString("default_encoder", chosenEncoder).apply()
                updateEncoderUI()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.language_bottom_sheet_dialog, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.btnCloseSheet)?.setBounceClickListener {
            dialog.dismiss()
        }

        val container = view.findViewById<android.widget.LinearLayout>(R.id.layoutLanguageContainer)
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentLocales.isEmpty) "" else (currentLocales.get(0)?.toLanguageTag() ?: "")

        val availableLanguages = getAvailableLanguages()

        for (item in availableLanguages) {
            val itemView = layoutInflater.inflate(R.layout.item_language_selection, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvLanguageName)
            val ivCheck = itemView.findViewById<android.widget.ImageView>(R.id.ivCheckLanguage)

            tvName.text = item.displayName

            val isSelected = if (item.tag.isEmpty()) {
                currentTag.isEmpty()
            } else {
                currentTag.equals(item.tag, ignoreCase = true) ||
                (item.tag.length == 2 && currentTag.startsWith(item.tag, ignoreCase = true))
            }

            ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            itemView.setBounceClickListener {
                val appLocale = if (item.tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(item.tag)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
                updateLanguageUI()
                dialog.dismiss()
            }

            container?.addView(itemView)
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        loadDrafts()
    }

    private fun loadDrafts() {
        val drafts = com.eclip.rup.leb.utils.DraftManager.getAllDrafts(this)
        if (drafts.isEmpty()) {
            binding.rvDrafts.visibility = View.GONE
            binding.layoutEmptyDrafts.visibility = View.VISIBLE
            exitDraftSelectionMode()
        } else {
            binding.layoutEmptyDrafts.visibility = View.GONE
            binding.rvDrafts.visibility = View.VISIBLE
            draftsAdapter.updateData(drafts)
        }
    }

    private fun enterDraftSelectionMode() {
        val drafts = com.eclip.rup.leb.utils.DraftManager.getAllDrafts(this)
        if (drafts.isEmpty()) {
            showToast("No drafts to select")
            return
        }
        draftsAdapter.toggleSelectionMode(true)
        binding.layoutDraftSelectionBar.visibility = View.VISIBLE
        binding.btnToggleDraftSelection.setColorFilter(Color.parseColor("#FF2A6D"))
        updateDraftSelectionUI(draftsAdapter.selectedDraftIds.size, drafts.size)
    }

    private fun exitDraftSelectionMode() {
        draftsAdapter.toggleSelectionMode(false)
        binding.layoutDraftSelectionBar.visibility = View.GONE
        binding.btnToggleDraftSelection.clearColorFilter()
    }

    private fun updateDraftSelectionUI(selectedCount: Int, totalCount: Int) {
        if (draftsAdapter.isAllSelected()) {
            binding.btnSelectAllDrafts.text = getString(R.string.str_deselect_all)
            binding.btnSelectAllDrafts.setIconResource(R.drawable.ic_close_24)
        } else {
            binding.btnSelectAllDrafts.text = getString(R.string.str_select_all)
            binding.btnSelectAllDrafts.setIconResource(R.drawable.ic_check_24)
        }

        binding.btnDeleteSelectedDrafts.text = if (selectedCount > 0) {
            "${getString(R.string.str_delete)} ($selectedCount)"
        } else {
            getString(R.string.str_delete)
        }
        binding.btnDeleteSelectedDrafts.alpha = if (selectedCount > 0) 1.0f else 0.5f
    }

    private fun confirmDeleteMultipleDrafts(selectedDrafts: List<com.eclip.rup.leb.utils.DraftItem>) {
        val count = selectedDrafts.size
        val message = getString(R.string.str_delete_selected_drafts_confirm, count)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.str_delete_draft)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                for (draft in selectedDrafts) {
                    com.eclip.rup.leb.utils.DraftManager.deleteDraft(this, draft.id)
                }
                exitDraftSelectionMode()
                loadDrafts()
                showToast("$count draft(s) deleted")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDraft(draft: com.eclip.rup.leb.utils.DraftItem) {
        val intent = Intent(this, VideoEditingActivity::class.java).apply {
            val fileUri = com.eclip.rup.leb.utils.DraftManager.getDraftFileUri(this@MainActivity, draft.id)
            putExtra("PROJECT_URI", fileUri)
            putExtra("DRAFT_ID", draft.id)
            putExtra("VIDEO_URI", draft.sourceUri)
            data = fileUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun confirmDeleteDraft(draft: com.eclip.rup.leb.utils.DraftItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.str_delete_draft)
            .setMessage(R.string.str_delete_draft_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
            com.eclip.rup.leb.utils.DraftManager.deleteDraft(this, draft.id)
            loadDrafts()
            Toast.makeText(this, "Draft deleted", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
    }

    private fun showHomeScreen() {
        binding.layoutTopHeader.visibility = View.VISIBLE
        binding.layoutHomeContent.visibility = View.VISIBLE
        binding.layoutSettingsContent.visibility = View.GONE
        binding.btnOpenSettings.visibility = View.VISIBLE
    }

    private fun showSettingsScreen() {
        if (draftsAdapter.isSelectionMode) {
            exitDraftSelectionMode()
        }
        binding.layoutTopHeader.visibility = View.GONE
        binding.layoutHomeContent.visibility = View.GONE
        binding.layoutSettingsContent.visibility = View.VISIBLE
        binding.btnOpenSettings.visibility = View.GONE
    }

    private fun showDraftsInfoDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.str_drafts)
            .setMessage(R.string.str_drafts_info_desc)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAboutAppDialog() {
        val prefs = getSharedPreferences("eclip_prefs", MODE_PRIVATE)
        showOnboardingDialog(prefs)
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            showToast("Unable to open link")
        }
    }





    private fun selectVideo() {
        Log.d("VideoSelection", "Launching video picker.")
        val picker = com.eclip.rup.leb.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.eclip.rup.leb.customviews.MediaPickerBottomSheet.MediaType.VIDEO
            showCategoryTabs = true
            showAudioTab = false
            onMediaSelectedListener = { uri ->
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.d("VideoSelection", "Could not take persistable permission: ${e.message}")
                }
                navigateToEditingScreen(uri)
            }
            onBrowseSystemFoldersRequested = {
                selectVideoLauncher.launch(arrayOf("video/*", "image/*"))
            }
        }
        picker.show(supportFragmentManager, "MediaPickerBottomSheet")
    }

    private fun navigateToEditingScreen(videoUri: Uri) {
        Log.d("Navigation", "Navigating to editing screen with URI: $videoUri")
        val intent = Intent(this, VideoEditingActivity::class.java).apply {
            putExtra("VIDEO_URI", videoUri)
            data = videoUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("video/") || type.startsWith("image/")) {
                androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                    Log.d("SharedVideo", "Received SEND intent with media URI: $uri")
                    navigateToEditingScreen(uri)
                }
            }
        } else if ((Intent.ACTION_VIEW == action || Intent.ACTION_EDIT == action) && type != null) {
            if (type.startsWith("video/") || type.startsWith("image/")) {
                intent.data?.let { uri ->
                    Log.d("SharedVideo", "Received VIEW/EDIT intent with media URI: $uri")
                    navigateToEditingScreen(uri)
                }
            }
        }
    }

    private fun showOnboardingDialog(prefs: android.content.SharedPreferences) {
        val dialog = android.app.Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_welcome_onboarding, null)
        dialog.setContentView(view)
        dialog.setCancelable(false)

        dialog.window?.let { window ->
            // Make dialog window background transparent so our custom layout's background card and shape render perfectly
            window.setBackgroundDrawableResource(android.R.color.transparent)
            
            // Set size parameters
            val lp = window.attributes
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            window.attributes = lp
        }

        // Set version dynamically
        val tvVersion = view.findViewById<TextView>(R.id.tvOnboardingVersion)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "Version ${pInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = "Version 1.0-beta4"
        }

        view.findViewById<View>(R.id.btnOnboardingGetStarted)?.setBounceClickListener {
            prefs.edit().putBoolean("first_launch_v1", false).apply()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showToast(message: String) {
        Log.d("ToastMessage", "Showing toast: $message")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun checkForUpdates() {
        showToast("You are using the latest version of Eclip.")
    }
}