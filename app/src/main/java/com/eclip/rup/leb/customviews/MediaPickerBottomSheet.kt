package com.eclip.rup.leb.customviews

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.eclip.rup.leb.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.Locale

class MediaPickerBottomSheet : BottomSheetDialogFragment() {

    enum class MediaType { VIDEO, IMAGE, AUDIO }
    enum class TabType { VIDEO, PHOTO, ALL, AUDIO }

    data class MediaModel(
        val id: Long,
        val uri: Uri,
        val displayName: String,
        val durationMs: Long,
        val type: MediaType,
        val dateAdded: Long,
        val bucketName: String = "All",
        val artist: String = "Unknown Artist",
        val localFile: File? = null
    )

    var initialMediaType: MediaType = MediaType.VIDEO
    var showCategoryTabs: Boolean = true
    var showAudioTab: Boolean = false
    var onMediaSelectedListener: ((Uri) -> Unit)? = null
    var onMultipleMediaSelectedListener: ((List<Uri>) -> Unit)? = null
    var onBrowseSystemFoldersRequested: (() -> Unit)? = null

    // UI Views
    private lateinit var btnCloseSheet: ImageView
    private lateinit var layoutCategoryTabs: View
    private lateinit var tabVideo: View
    private lateinit var tvTabVideo: TextView
    private lateinit var indicatorVideo: View
    private lateinit var tabPhoto: View
    private lateinit var tvTabPhoto: TextView
    private lateinit var indicatorPhoto: View
    private lateinit var tabAll: View
    private lateinit var tvTabAll: TextView
    private lateinit var indicatorAll: View
    private lateinit var tabAudio: View
    private lateinit var tvTabAudio: TextView
    private lateinit var indicatorAudio: View

    private lateinit var rvMediaGrid: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var layoutEmptyContainer: View
    private lateinit var tvEmptyState: TextView
    private lateinit var btnGrantPermission: View
    private lateinit var btnSystemPickerFallback: View

    // Bottom Selected Tray Views
    private lateinit var layoutSelectedTray: LinearLayout
    private lateinit var btnDeleteSelected: ImageView
    private lateinit var tvSelectedSummary: TextView
    private lateinit var btnConfirmSelection: FrameLayout
    private lateinit var rvSelectedItems: RecyclerView
    private lateinit var tvSwapHint: TextView

    // State & Data
    private val allFetchedItems = mutableListOf<MediaModel>()
    private val displayedItems = mutableListOf<MediaModel>()
    private val selectedItems = mutableListOf<MediaModel>()

    private var currentTab: TabType = TabType.VIDEO
    private var adapter: MediaGridAdapter? = null
    private var selectedAdapter: SelectedThumbAdapter? = null

    // In-memory bitmap cache for fast smooth scrolling
    private val thumbnailCache = object : LruCache<String, Bitmap>(100) {}

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            btnGrantPermission.visibility = View.GONE
            loadMediaForCurrentTab()
        } else {
            pbLoading.visibility = View.GONE
            layoutEmptyContainer.visibility = View.VISIBLE
            tvEmptyState.text = "Permission denied. Grant access or browse folders."
            btnGrantPermission.visibility = View.VISIBLE
        }
    }

    override fun getTheme(): Int = R.style.Editor

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_media_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupTopNavigation()
        setupCategoryTabs()
        setupRecyclerViews()
        setupSelectedTray()

        // Set initial tab state based on initialMediaType
        currentTab = when (initialMediaType) {
            MediaType.VIDEO -> TabType.VIDEO
            MediaType.IMAGE -> TabType.PHOTO
            MediaType.AUDIO -> TabType.AUDIO
        }

        if (showAudioTab || initialMediaType == MediaType.AUDIO) {
            tabAudio.visibility = View.VISIBLE
        }

        if (!showCategoryTabs && initialMediaType == MediaType.AUDIO) {
            layoutCategoryTabs.visibility = View.GONE
        }

        updateTabSelectionUI(currentTab)
        updateLayoutManagerForTab(currentTab)
        checkPermissionsAndLoadMedia()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            it.setBackgroundColor(Color.BLACK)
        }
    }

    private fun bindViews(view: View) {
        btnCloseSheet = view.findViewById(R.id.btnCloseSheet)
        layoutCategoryTabs = view.findViewById(R.id.layoutCategoryTabs)
        tabVideo = view.findViewById(R.id.tabVideo)
        tvTabVideo = view.findViewById(R.id.tvTabVideo)
        indicatorVideo = view.findViewById(R.id.indicatorVideo)
        tabPhoto = view.findViewById(R.id.tabPhoto)
        tvTabPhoto = view.findViewById(R.id.tvTabPhoto)
        indicatorPhoto = view.findViewById(R.id.indicatorPhoto)
        tabAll = view.findViewById(R.id.tabAll)
        tvTabAll = view.findViewById(R.id.tvTabAll)
        indicatorAll = view.findViewById(R.id.indicatorAll)
        tabAudio = view.findViewById(R.id.tabAudio)
        tvTabAudio = view.findViewById(R.id.tvTabAudio)
        indicatorAudio = view.findViewById(R.id.indicatorAudio)

        rvMediaGrid = view.findViewById(R.id.rvMediaGrid)
        pbLoading = view.findViewById(R.id.pbLoading)
        layoutEmptyContainer = view.findViewById(R.id.layoutEmptyContainer)
        tvEmptyState = view.findViewById(R.id.tvEmptyState)
        btnGrantPermission = view.findViewById(R.id.btnGrantPermission)
        btnSystemPickerFallback = view.findViewById(R.id.btnSystemPickerFallback)

        layoutSelectedTray = view.findViewById(R.id.layoutSelectedTray)
        btnDeleteSelected = view.findViewById(R.id.btnDeleteSelected)
        tvSelectedSummary = view.findViewById(R.id.tvSelectedSummary)
        btnConfirmSelection = view.findViewById(R.id.btnConfirmSelection)
        rvSelectedItems = view.findViewById(R.id.rvSelectedItems)
        tvSwapHint = view.findViewById(R.id.tvSwapHint)
    }

    private fun setupTopNavigation() {
        btnCloseSheet.setOnClickListener {
            dismiss()
        }

        btnGrantPermission.setOnClickListener {
            requestStoragePermissions()
        }

        btnSystemPickerFallback.setOnClickListener {
            dismiss()
            onBrowseSystemFoldersRequested?.invoke()
        }
    }

    private fun setupCategoryTabs() {
        tabVideo.setOnClickListener { switchTab(TabType.VIDEO) }
        tabPhoto.setOnClickListener { switchTab(TabType.PHOTO) }
        tabAll.setOnClickListener { switchTab(TabType.ALL) }
        tabAudio.setOnClickListener { switchTab(TabType.AUDIO) }
    }

    private fun switchTab(tab: TabType) {
        if (currentTab == tab) return
        currentTab = tab
        updateTabSelectionUI(tab)
        updateLayoutManagerForTab(tab)
        loadMediaForCurrentTab()
    }

    private fun updateTabSelectionUI(activeTab: TabType) {
        val activeColor = Color.parseColor("#FFFFFF")
        val inactiveColor = Color.parseColor("#8E8E93")

        tvTabVideo.setTextColor(if (activeTab == TabType.VIDEO) activeColor else inactiveColor)
        indicatorVideo.visibility = if (activeTab == TabType.VIDEO) View.VISIBLE else View.INVISIBLE

        tvTabPhoto.setTextColor(if (activeTab == TabType.PHOTO) activeColor else inactiveColor)
        indicatorPhoto.visibility = if (activeTab == TabType.PHOTO) View.VISIBLE else View.INVISIBLE

        tvTabAll.setTextColor(if (activeTab == TabType.ALL) activeColor else inactiveColor)
        indicatorAll.visibility = if (activeTab == TabType.ALL) View.VISIBLE else View.INVISIBLE

        tvTabAudio.setTextColor(if (activeTab == TabType.AUDIO) activeColor else inactiveColor)
        indicatorAudio.visibility = if (activeTab == TabType.AUDIO) View.VISIBLE else View.INVISIBLE
    }

    private fun updateLayoutManagerForTab(tab: TabType) {
        rvMediaGrid.layoutManager = if (tab == TabType.AUDIO) {
            GridLayoutManager(requireContext(), 1)
        } else {
            GridLayoutManager(requireContext(), 4)
        }
    }

    private fun setupRecyclerViews() {
        adapter = MediaGridAdapter(displayedItems) { clickedItem ->
            toggleItemSelection(clickedItem)
        }
        rvMediaGrid.adapter = adapter

        selectedAdapter = SelectedThumbAdapter(selectedItems) { removedItem ->
            removeItemSelection(removedItem)
        }
        rvSelectedItems.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvSelectedItems.adapter = selectedAdapter

        // Drag and drop support for swapping order in selected bottom tray
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION) {
                    Collections.swap(selectedItems, fromPos, toPos)
                    selectedAdapter?.notifyItemMoved(fromPos, toPos)
                    adapter?.notifyDataSetChanged()
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(rvSelectedItems)
    }

    private fun setupSelectedTray() {
        btnDeleteSelected.setOnClickListener {
            selectedItems.clear()
            updateSelectedTrayUI()
            adapter?.notifyDataSetChanged()
        }

        btnConfirmSelection.setOnClickListener {
            if (selectedItems.isNotEmpty()) {
                onMultipleMediaSelectedListener?.invoke(selectedItems.map { it.uri })
                onMediaSelectedListener?.invoke(selectedItems.first().uri)
                dismiss()
            }
        }
    }

    private fun toggleItemSelection(item: MediaModel) {
        val existingIndex = selectedItems.indexOfFirst { it.uri == item.uri }
        if (existingIndex >= 0) {
            selectedItems.removeAt(existingIndex)
        } else {
            selectedItems.add(item)
        }
        updateSelectedTrayUI()
        adapter?.notifyDataSetChanged()
    }

    private fun removeItemSelection(item: MediaModel) {
        val index = selectedItems.indexOfFirst { it.uri == item.uri }
        if (index >= 0) {
            selectedItems.removeAt(index)
            updateSelectedTrayUI()
            adapter?.notifyDataSetChanged()
        }
    }

    private fun updateSelectedTrayUI() {
        if (selectedItems.isEmpty()) {
            layoutSelectedTray.visibility = View.GONE
        } else {
            layoutSelectedTray.visibility = View.VISIBLE
            val videoCount = selectedItems.count { it.type == MediaType.VIDEO }
            val photoCount = selectedItems.count { it.type == MediaType.IMAGE }
            val audioCount = selectedItems.count { it.type == MediaType.AUDIO }

            tvSelectedSummary.text = when {
                audioCount > 0 -> "Selected $audioCount Audio track(s)"
                else -> "Selected $videoCount Video(s)/$photoCount Photo(s)"
            }

            selectedAdapter?.notifyDataSetChanged()
            rvSelectedItems.smoothScrollToPosition(selectedItems.size - 1)
        }
    }

    private fun checkPermissionsAndLoadMedia() {
        if (hasRequiredPermissions()) {
            btnGrantPermission.visibility = View.GONE
            loadMediaForCurrentTab()
        } else {
            requestStoragePermissions()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val context = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val videoPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            val imagePerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val audioPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            var userSelectedPerm = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                userSelectedPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            }
            videoPerm || imagePerm || audioPerm || userSelectedPerm
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun loadMediaForCurrentTab() {
        pbLoading.visibility = View.VISIBLE
        layoutEmptyContainer.visibility = View.GONE
        allFetchedItems.clear()
        displayedItems.clear()
        adapter?.notifyDataSetChanged()

        lifecycleScope.launch(Dispatchers.IO) {
            val items = when (currentTab) {
                TabType.VIDEO -> queryMediaStoreMedia(MediaType.VIDEO)
                TabType.PHOTO -> queryMediaStoreMedia(MediaType.IMAGE)
                TabType.ALL -> {
                    val vList = queryMediaStoreMedia(MediaType.VIDEO)
                    val pList = queryMediaStoreMedia(MediaType.IMAGE)
                    (vList + pList).sortedByDescending { it.dateAdded }
                }
                TabType.AUDIO -> queryMediaStoreMedia(MediaType.AUDIO)
            }

            withContext(Dispatchers.Main) {
                pbLoading.visibility = View.GONE
                allFetchedItems.addAll(items)
                displayedItems.addAll(items)
                adapter?.notifyDataSetChanged()

                if (displayedItems.isEmpty()) {
                    layoutEmptyContainer.visibility = View.VISIBLE
                    tvEmptyState.text = when (currentTab) {
                        TabType.VIDEO -> "No videos found on device."
                        TabType.PHOTO -> "No photos found on device."
                        TabType.ALL -> "No media files found on device."
                        TabType.AUDIO -> "No audio files found on device."
                    }
                } else {
                    layoutEmptyContainer.visibility = View.GONE
                }
            }
        }
    }

    private fun queryMediaStoreMedia(type: MediaType): List<MediaModel> {
        val context = context ?: return emptyList()
        val result = mutableListOf<MediaModel>()
        val contentResolver = context.contentResolver

        val (contentUri, projection) = when (type) {
            MediaType.VIDEO -> Pair(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                )
            )
            MediaType.IMAGE -> Pair(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                )
            )
            MediaType.AUDIO -> Pair(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ARTIST
                )
            )
        }

        try {
            contentResolver.query(contentUri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val durationColumn = if (type != MediaType.IMAGE) cursor.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
                val bucketColumn = if (type != MediaType.AUDIO) cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME) else cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val artistColumn = if (type == MediaType.AUDIO) cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Media"
                    val dateAdded = cursor.getLong(dateColumn)
                    val duration = if (durationColumn != -1 && !cursor.isNull(durationColumn)) cursor.getLong(durationColumn) else 0L
                    val bucketName = if (bucketColumn != -1 && !cursor.isNull(bucketColumn)) cursor.getString(bucketColumn) ?: "All" else "All"
                    val artistName = if (artistColumn != -1 && !cursor.isNull(artistColumn)) cursor.getString(artistColumn) ?: "Unknown Artist" else "Unknown Artist"
                    val uri = ContentUris.withAppendedId(contentUri, id)

                    result.add(
                        MediaModel(
                            id = id,
                            uri = uri,
                            displayName = name,
                            durationMs = duration,
                            type = type,
                            dateAdded = dateAdded,
                            bucketName = bucketName,
                            artist = artistName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaPicker", "Error querying MediaStore for $type", e)
        }

        if (result.isEmpty()) {
            scanPublicFolderFallback(type, result)
        }

        return result
    }

    private fun scanPublicFolderFallback(type: MediaType, result: MutableList<MediaModel>) {
        try {
            val targetDirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            )

            for (dir in targetDirs) {
                if (dir.exists() && dir.isDirectory) {
                    scanDirFiles(dir, type, result, maxDepth = 2)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaPicker", "Error in public folder fallback scan", e)
        }
    }

    private fun scanDirFiles(dir: File, type: MediaType, result: MutableList<MediaModel>, maxDepth: Int) {
        if (maxDepth <= 0) return
        val files = try { dir.listFiles() } catch (e: Exception) { null } ?: return

        for (file in files) {
            if (file.name.startsWith(".")) continue
            if (file.isDirectory) {
                scanDirFiles(file, type, result, maxDepth - 1)
            } else {
                val ext = file.extension.lowercase(Locale.getDefault())
                val isVideo = ext in listOf("mp4", "mkv", "mov", "avi", "3gp", "webm")
                val isImage = ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
                val isAudio = ext in listOf("mp3", "wav", "m4a", "aac", "ogg", "flac")

                val matches = when (type) {
                    MediaType.VIDEO -> isVideo
                    MediaType.IMAGE -> isImage
                    MediaType.AUDIO -> isAudio
                }

                if (matches) {
                    var durationMs = 0L
                    if (isVideo || isAudio) {
                        durationMs = extractMediaDuration(file)
                    }

                    result.add(
                        MediaModel(
                            id = file.hashCode().toLong(),
                            uri = Uri.fromFile(file),
                            displayName = file.name,
                            durationMs = durationMs,
                            type = type,
                            dateAdded = file.lastModified() / 1000,
                            bucketName = dir.name,
                            localFile = file
                        )
                    )
                }
            }
        }
    }

    private fun extractMediaDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            try { retriever.release() } catch (_: Exception) {}
            0L
        }
    }

    // -------------------------------------------------------------
    // Main 4-Column Media Grid Adapter
    // -------------------------------------------------------------
    private inner class MediaGridAdapter(
        private val items: List<MediaModel>,
        private val onItemClick: (MediaModel) -> Unit
    ) : RecyclerView.Adapter<MediaGridViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaGridViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_picker_thumbnail, parent, false)
            return MediaGridViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaGridViewHolder, position: Int) {
            val item = items[position]
            val selectionIndex = selectedItems.indexOfFirst { it.uri == item.uri }
            holder.bind(item, selectionIndex, onItemClick)
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class MediaGridViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val layoutGridContainer: View = itemView.findViewById(R.id.layoutGridContainer)
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val vSelectedOverlay: View = itemView.findViewById(R.id.vSelectedOverlay)
        val layoutSelectionBadge: FrameLayout = itemView.findViewById(R.id.layoutSelectionBadge)
        val tvSelectionNumber: TextView = itemView.findViewById(R.id.tvSelectionNumber)
        val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        val ivScissors: ImageView = itemView.findViewById(R.id.ivScissors)

        // Audio Views
        val layoutAudioContainer: View = itemView.findViewById(R.id.layoutAudioContainer)
        val ivAudioCover: com.google.android.material.imageview.ShapeableImageView = itemView.findViewById(R.id.ivAudioCover)
        val tvAudioTitle: TextView = itemView.findViewById(R.id.tvAudioTitle)
        val tvAudioSubTitle: TextView = itemView.findViewById(R.id.tvAudioSubTitle)
        val tvAudioDuration: TextView = itemView.findViewById(R.id.tvAudioDuration)

        fun bind(item: MediaModel, selectionIndex: Int, onItemClick: (MediaModel) -> Unit) {
            itemView.tag = item.id

            if (item.type == MediaType.AUDIO) {
                layoutGridContainer.visibility = View.GONE
                layoutAudioContainer.visibility = View.VISIBLE

                tvAudioTitle.text = item.displayName
                val subText = if (item.artist.isNotBlank() && item.artist != "<unknown>") item.artist else item.bucketName
                tvAudioSubTitle.text = subText
                tvAudioDuration.text = formatDuration(item.durationMs)

                val isSelected = selectionIndex >= 0
                itemView.setBackgroundColor(if (isSelected) Color.parseColor("#2A1118") else Color.TRANSPARENT)

                loadAudioAlbumArtAsync(item, ivAudioCover)
            } else {
                layoutGridContainer.visibility = View.VISIBLE
                layoutAudioContainer.visibility = View.GONE

                val isSelected = selectionIndex >= 0

                // Selection Visuals
                vSelectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE

                if (isSelected) {
                    layoutSelectionBadge.setBackgroundResource(R.drawable.bg_gallery_selected_badge)
                    tvSelectionNumber.visibility = View.VISIBLE
                    tvSelectionNumber.text = (selectionIndex + 1).toString()
                } else {
                    layoutSelectionBadge.setBackgroundResource(R.drawable.bg_gallery_unselected_badge)
                    tvSelectionNumber.visibility = View.GONE
                }

                // Video Badges
                if (item.type == MediaType.VIDEO) {
                    tvDuration.visibility = View.VISIBLE
                    tvDuration.text = formatDuration(item.durationMs)
                    ivScissors.visibility = View.VISIBLE
                } else {
                    tvDuration.visibility = View.GONE
                    ivScissors.visibility = View.GONE
                }

                loadThumbnailAsync(item, ivThumbnail)
            }

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun loadThumbnailAsync(item: MediaModel, targetView: ImageView) {
            val cacheKey = item.uri.toString()
            val cachedBitmap = thumbnailCache.get(cacheKey)
            if (cachedBitmap != null) {
                targetView.setImageBitmap(cachedBitmap)
                return
            }

            targetView.setImageBitmap(null)

            lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = loadThumbnail(item)
                if (bitmap != null) {
                    thumbnailCache.put(cacheKey, bitmap)
                }
                withContext(Dispatchers.Main) {
                    if (itemView.tag == item.id) {
                        if (bitmap != null) {
                            targetView.setImageBitmap(bitmap)
                        } else {
                            val placeholder = if (item.type == MediaType.VIDEO) R.drawable.ic_play_24 else R.drawable.ic_image_24
                            targetView.setImageResource(placeholder)
                        }
                    }
                }
            }
        }

        private fun loadAudioAlbumArtAsync(item: MediaModel, targetView: ImageView) {
            lifecycleScope.launch(Dispatchers.IO) {
                val albumArt = loadAudioAlbumArt(item)
                withContext(Dispatchers.Main) {
                    if (itemView.tag == item.id) {
                        if (albumArt != null) {
                            targetView.setImageBitmap(albumArt)
                            targetView.setPadding(0, 0, 0, 0)
                            targetView.clearColorFilter()
                        } else {
                            targetView.setImageResource(R.drawable.ic_audio_24)
                            val density = itemView.context.resources.displayMetrics.density
                            val pad = (10 * density).toInt()
                            targetView.setPadding(pad, pad, pad, pad)
                            targetView.setColorFilter(Color.WHITE)
                        }
                    }
                }
            }
        }

        private fun loadThumbnail(item: MediaModel): Bitmap? {
            val context = itemView.context ?: return null
            return try {
                if (item.localFile != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(item.uri, Size(300, 300), null)
                    } else {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(item.localFile.absolutePath)
                        val frame = retriever.frameAtTime
                        retriever.release()
                        frame
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(item.uri, Size(300, 300), null)
                    } else {
                        @Suppress("DEPRECATION")
                        if (item.type == MediaType.VIDEO) {
                            val id = ContentUris.parseId(item.uri)
                            MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                        } else {
                            val id = ContentUris.parseId(item.uri)
                            MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun loadAudioAlbumArt(item: MediaModel): Bitmap? {
            val context = itemView.context ?: return null
            return try {
                val retriever = MediaMetadataRetriever()
                if (item.localFile != null) {
                    retriever.setDataSource(item.localFile.absolutePath)
                } else {
                    retriever.setDataSource(context, item.uri)
                }
                val artBytes = retriever.embeddedPicture
                retriever.release()
                if (artBytes != null) {
                    BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                } else null
            } catch (e: Exception) {
                null
            }
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    // -------------------------------------------------------------
    // Bottom Selected Horizontal Preview Tray Adapter
    // -------------------------------------------------------------
    private inner class SelectedThumbAdapter(
        private val items: List<MediaModel>,
        private val onRemoveClick: (MediaModel) -> Unit
    ) : RecyclerView.Adapter<SelectedThumbViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectedThumbViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_picker_selected_thumb, parent, false)
            return SelectedThumbViewHolder(view)
        }

        override fun onBindViewHolder(holder: SelectedThumbViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item, onRemoveClick)
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class SelectedThumbViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivSelectedThumb: ImageView = itemView.findViewById(R.id.ivSelectedThumb)
        val tvSelectedDuration: TextView = itemView.findViewById(R.id.tvSelectedDuration)
        val ivSelectedScissors: ImageView = itemView.findViewById(R.id.ivSelectedScissors)
        val btnRemoveSelected: FrameLayout = itemView.findViewById(R.id.btnRemoveSelected)

        fun bind(item: MediaModel, onRemoveClick: (MediaModel) -> Unit) {
            itemView.tag = item.id

            if (item.type == MediaType.VIDEO) {
                tvSelectedDuration.visibility = View.VISIBLE
                tvSelectedDuration.text = formatPreciseDuration(item.durationMs)
                ivSelectedScissors.visibility = View.VISIBLE
            } else {
                tvSelectedDuration.visibility = View.GONE
                ivSelectedScissors.visibility = View.GONE
            }

            val cacheKey = item.uri.toString()
            val cachedBitmap = thumbnailCache.get(cacheKey)
            if (cachedBitmap != null) {
                ivSelectedThumb.setImageBitmap(cachedBitmap)
            } else {
                ivSelectedThumb.setImageBitmap(null)
                lifecycleScope.launch(Dispatchers.IO) {
                    val bitmap = loadThumbnail(item)
                    if (bitmap != null) {
                        thumbnailCache.put(cacheKey, bitmap)
                    }
                    withContext(Dispatchers.Main) {
                        if (itemView.tag == item.id && bitmap != null) {
                            ivSelectedThumb.setImageBitmap(bitmap)
                        }
                    }
                }
            }

            btnRemoveSelected.setOnClickListener {
                onRemoveClick(item)
            }
        }

        private fun loadThumbnail(item: MediaModel): Bitmap? {
            val context = itemView.context ?: return null
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(item.uri, Size(200, 200), null)
                } else {
                    @Suppress("DEPRECATION")
                    if (item.type == MediaType.VIDEO) {
                        val id = ContentUris.parseId(item.uri)
                        MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                    } else {
                        val id = ContentUris.parseId(item.uri)
                        MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun formatPreciseDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val millisTenth = (durationMs % 1000) / 100
            return String.format(Locale.getDefault(), "%02d:%02d.%d", minutes, seconds, millisTenth)
        }
    }
}
