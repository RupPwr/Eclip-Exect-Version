package com.eclip.rup.leb.customviews

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.eclip.rup.leb.R
import com.eclip.rup.leb.models.TimelineClip
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
        val localFile: File? = null,
        val width: Int = 0,
        val height: Int = 0,
        val filePath: String? = null
    )

    fun MediaModel.toTimelineClip(): TimelineClip {
        val resolvedPath = filePath?.takeIf { it.isNotBlank() } ?: uri.toString()
        val effectiveDuration = when (type) {
            MediaType.IMAGE -> 3000L
            else -> durationMs
        }
        return TimelineClip(
            path = resolvedPath,
            durationMs = effectiveDuration,
            width = width,
            height = height,
            isImage = (type == MediaType.IMAGE),
            hasAudio = (type != MediaType.IMAGE)
        )
    }

    var initialMediaType: MediaType = MediaType.VIDEO
    var showCategoryTabs: Boolean = true
    var showAudioTab: Boolean = false
    var onMediaSelectedListener: ((Uri) -> Unit)? = null
    var onMultipleMediaSelectedListener: ((List<Uri>) -> Unit)? = null
    var onClipSelectedListener: ((TimelineClip) -> Unit)? = null
    var onMultipleClipsSelectedListener: ((List<TimelineClip>) -> Unit)? = null
    var onBrowseSystemFoldersRequested: (() -> Unit)? = null

    // UI Views
    private lateinit var btnCloseSheet: ImageView
    private lateinit var tabLayoutGallery: TabLayout
    private lateinit var viewPagerGallery: ViewPager2

    // Bottom Selected Tray Views
    private lateinit var layoutSelectedTray: LinearLayout
    private lateinit var btnDeleteSelected: ImageView
    private lateinit var tvSelectedSummary: TextView
    private lateinit var btnConfirmSelection: FrameLayout
    private lateinit var rvSelectedItems: RecyclerView
    private lateinit var tvSwapHint: TextView

    // Available tabs for current session
    private val availableTabs = mutableListOf<TabType>()

    // Data Cache per Tab
    private val tabDataMap = mutableMapOf<TabType, MutableList<MediaModel>>()
    private val tabLoadedMap = mutableMapOf<TabType, Boolean>()
    private val selectedItems = mutableListOf<MediaModel>()

    // Active page adapters & viewholders
    private val activeAdapters = mutableMapOf<TabType, MediaGridAdapter>()
    private val activePageHolders = mutableMapOf<TabType, GalleryPageViewHolder>()

    private var galleryPagerAdapter: GalleryPagerAdapter? = null
    private var selectedAdapter: SelectedThumbAdapter? = null
    private var tabMediator: TabLayoutMediator? = null

    // Shared RecycledViewPool across tab grids for smooth instant tab transitions
    private val sharedRecycledViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 40)
        setMaxRecycledViews(1, 15)
    }

    // Guard against duplicate concurrent tab queries
    private val tabLoadingMap = mutableMapOf<TabType, Boolean>()

    // In-memory bitmap cache with memory-size awareness for 60fps scrolling
    private val thumbnailCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(1024 * 16)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    // Raw media cache by media type to avoid duplicate MediaStore queries
    private val rawMediaCache = mutableMapOf<MediaType, List<MediaModel>>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            loadCurrentTabMedia()
        } else {
            for (tab in availableTabs) {
                activePageHolders[tab]?.showPermissionDenied()
            }
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
        setupAvailableTabs()
        setupTopNavigation()
        setupViewPagerAndTabs()
        setupSelectedTray()

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

    override fun onDestroyView() {
        tabMediator?.detach()
        tabMediator = null
        activeAdapters.clear()
        activePageHolders.clear()
        super.onDestroyView()
    }

    private fun bindViews(view: View) {
        btnCloseSheet = view.findViewById(R.id.btnCloseSheet)
        tabLayoutGallery = view.findViewById(R.id.tabLayoutGallery)
        viewPagerGallery = view.findViewById(R.id.viewPagerGallery)

        layoutSelectedTray = view.findViewById(R.id.layoutSelectedTray)
        btnDeleteSelected = view.findViewById(R.id.btnDeleteSelected)
        tvSelectedSummary = view.findViewById(R.id.tvSelectedSummary)
        btnConfirmSelection = view.findViewById(R.id.btnConfirmSelection)
        rvSelectedItems = view.findViewById(R.id.rvSelectedItems)
        tvSwapHint = view.findViewById(R.id.tvSwapHint)
    }

    private fun setupAvailableTabs() {
        availableTabs.clear()
        if (!showCategoryTabs && initialMediaType == MediaType.AUDIO) {
            availableTabs.add(TabType.AUDIO)
        } else if (showAudioTab || initialMediaType == MediaType.AUDIO) {
            availableTabs.add(TabType.VIDEO)
            availableTabs.add(TabType.PHOTO)
            availableTabs.add(TabType.ALL)
            availableTabs.add(TabType.AUDIO)
        } else {
            availableTabs.add(TabType.VIDEO)
            availableTabs.add(TabType.PHOTO)
            availableTabs.add(TabType.ALL)
        }

        for (tab in availableTabs) {
            if (!tabDataMap.containsKey(tab)) {
                tabDataMap[tab] = mutableListOf()
                tabLoadedMap[tab] = false
            }
        }
    }

    private fun setupTopNavigation() {
        btnCloseSheet.setOnClickListener {
            dismiss()
        }

        if (!showCategoryTabs && initialMediaType == MediaType.AUDIO) {
            tabLayoutGallery.visibility = View.GONE
        } else {
            tabLayoutGallery.visibility = View.VISIBLE
        }
    }

    private fun setupViewPagerAndTabs() {
        galleryPagerAdapter = GalleryPagerAdapter()
        viewPagerGallery.adapter = galleryPagerAdapter
        viewPagerGallery.offscreenPageLimit = 1

        tabMediator = TabLayoutMediator(tabLayoutGallery, viewPagerGallery) { tab, position ->
            val tabType = availableTabs[position]
            tab.text = when (tabType) {
                TabType.VIDEO -> "VIDEO"
                TabType.PHOTO -> "PHOTO"
                TabType.ALL -> "ALL"
                TabType.AUDIO -> "AUDIO"
            }
        }
        tabMediator?.attach()

        // Set initial selected tab position
        val initialIndex = when (initialMediaType) {
            MediaType.VIDEO -> availableTabs.indexOf(TabType.VIDEO)
            MediaType.IMAGE -> availableTabs.indexOf(TabType.PHOTO)
            MediaType.AUDIO -> availableTabs.indexOf(TabType.AUDIO)
        }
        if (initialIndex >= 0) {
            viewPagerGallery.setCurrentItem(initialIndex, false)
        }

        // Lazy-load tabs on demand as user swipes or selects them
        viewPagerGallery.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val tab = availableTabs.getOrNull(position) ?: return
                if (hasRequiredPermissions() && tabLoadedMap[tab] != true) {
                    loadMediaForTab(tab)
                }
            }
        })
    }

    private fun setupSelectedTray() {
        selectedAdapter = SelectedThumbAdapter(selectedItems) { removedItem ->
            removeItemSelection(removedItem)
        }
        rvSelectedItems.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvSelectedItems.adapter = selectedAdapter

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
                    refreshAllActivePagesSelection()
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(rvSelectedItems)

        btnDeleteSelected.setOnClickListener {
            selectedItems.clear()
            updateSelectedTrayUI()
            refreshAllActivePagesSelection()
        }

        btnConfirmSelection.setOnClickListener {
            if (selectedItems.isNotEmpty()) {
                val clips = selectedItems.map { it.toTimelineClip() }
                val uris = selectedItems.map { it.uri }
                
                if (onMultipleClipsSelectedListener != null) {
                    onMultipleClipsSelectedListener?.invoke(clips)
                } else if (onClipSelectedListener != null) {
                    onClipSelectedListener?.invoke(clips.first())
                } else if (onMultipleMediaSelectedListener != null) {
                    onMultipleMediaSelectedListener?.invoke(uris)
                } else if (onMediaSelectedListener != null) {
                    onMediaSelectedListener?.invoke(uris.first())
                }
                
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
        refreshAllActivePagesSelection()
    }

    private fun removeItemSelection(item: MediaModel) {
        val index = selectedItems.indexOfFirst { it.uri == item.uri }
        if (index >= 0) {
            selectedItems.removeAt(index)
            updateSelectedTrayUI()
            refreshAllActivePagesSelection()
        }
    }

    private fun refreshAllActivePagesSelection() {
        for (adapter in activeAdapters.values) {
            adapter.notifyDataSetChanged()
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
            loadCurrentTabMedia()
        } else {
            requestStoragePermissions()
        }
    }

    private fun loadCurrentTabMedia() {
        val currentPosition = if (::viewPagerGallery.isInitialized) viewPagerGallery.currentItem else 0
        val activeTab = availableTabs.getOrNull(currentPosition) ?: availableTabs.firstOrNull() ?: return
        loadMediaForTab(activeTab)
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

    private fun getMediaForType(type: MediaType): List<MediaModel> {
        rawMediaCache[type]?.let { return it }
        val loaded = queryMediaStoreMedia(type)
        rawMediaCache[type] = loaded
        return loaded
    }

    private fun loadMediaForTab(tabType: TabType) {
        if (tabLoadedMap[tabType] == true && tabDataMap[tabType]?.isNotEmpty() == true) {
            activePageHolders[tabType]?.updateEmptyState(tabDataMap[tabType]!!.isEmpty(), tabType)
            return
        }
        if (tabLoadingMap[tabType] == true) return
        tabLoadingMap[tabType] = true

        val holder = activePageHolders[tabType]
        holder?.showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val items = try {
                when (tabType) {
                    TabType.VIDEO -> getMediaForType(MediaType.VIDEO)
                    TabType.PHOTO -> getMediaForType(MediaType.IMAGE)
                    TabType.ALL -> {
                        val vList = getMediaForType(MediaType.VIDEO)
                        val pList = getMediaForType(MediaType.IMAGE)
                        (vList + pList).sortedByDescending { it.dateAdded }
                    }
                    TabType.AUDIO -> getMediaForType(MediaType.AUDIO)
                }
            } finally {
                tabLoadingMap[tabType] = false
            }

            withContext(Dispatchers.Main) {
                val list = tabDataMap.getOrPut(tabType) { mutableListOf() }
                list.clear()
                list.addAll(items)
                tabLoadedMap[tabType] = true

                activeAdapters[tabType]?.notifyDataSetChanged()
                activePageHolders[tabType]?.updateEmptyState(items.isEmpty(), tabType)
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
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.MediaColumns.DATA
                )
            )
            MediaType.IMAGE -> Pair(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.MediaColumns.DATA
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
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.MediaColumns.DATA
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
                val widthColumn = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightColumn = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Media"
                    val dateAdded = cursor.getLong(dateColumn)
                    val duration = if (durationColumn != -1 && !cursor.isNull(durationColumn)) cursor.getLong(durationColumn) else 0L
                    val bucketName = if (bucketColumn != -1 && !cursor.isNull(bucketColumn)) cursor.getString(bucketColumn) ?: "All" else "All"
                    val artistName = if (artistColumn != -1 && !cursor.isNull(artistColumn)) cursor.getString(artistColumn) ?: "Unknown Artist" else "Unknown Artist"
                    val width = if (widthColumn != -1 && !cursor.isNull(widthColumn)) cursor.getInt(widthColumn) else 0
                    val height = if (heightColumn != -1 && !cursor.isNull(heightColumn)) cursor.getInt(heightColumn) else 0
                    val dataPath = if (dataColumn != -1 && !cursor.isNull(dataColumn)) cursor.getString(dataColumn) else null
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
                            artist = artistName,
                            width = width,
                            height = height,
                            filePath = dataPath
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
                            localFile = file,
                            filePath = file.absolutePath
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
            durStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // -------------------------------------------------------------
    // ViewPager2 Page Adapter
    // -------------------------------------------------------------
    private inner class GalleryPagerAdapter : RecyclerView.Adapter<GalleryPageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryPageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.layout_media_picker_page, parent, false)
            return GalleryPageViewHolder(view)
        }

        override fun onBindViewHolder(holder: GalleryPageViewHolder, position: Int) {
            val tabType = availableTabs[position]
            holder.bind(tabType)
        }

        override fun onViewRecycled(holder: GalleryPageViewHolder) {
            super.onViewRecycled(holder)
            holder.currentTab?.let {
                activePageHolders.remove(it)
                activeAdapters.remove(it)
            }
        }

        override fun getItemCount(): Int = availableTabs.size
    }

    private inner class GalleryPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val rvPageMediaGrid: RecyclerView = itemView.findViewById(R.id.rvPageMediaGrid)
        val pbPageLoading: ThreeDotLoadingView = itemView.findViewById(R.id.pbPageLoading)
        val layoutPageEmptyContainer: View = itemView.findViewById(R.id.layoutPageEmptyContainer)
        val tvPageEmptyState: TextView = itemView.findViewById(R.id.tvPageEmptyState)
        val btnPageGrantPermission: View = itemView.findViewById(R.id.btnPageGrantPermission)
        val btnPageSystemPickerFallback: View = itemView.findViewById(R.id.btnPageSystemPickerFallback)

        var currentTab: TabType? = null

        fun bind(tabType: TabType) {
            currentTab = tabType
            activePageHolders[tabType] = this

            rvPageMediaGrid.setRecycledViewPool(sharedRecycledViewPool)
            rvPageMediaGrid.setHasFixedSize(true)
            rvPageMediaGrid.itemAnimator = null

            rvPageMediaGrid.layoutManager = if (tabType == TabType.AUDIO) {
                GridLayoutManager(itemView.context, 1)
            } else {
                GridLayoutManager(itemView.context, 4)
            }

            val items = tabDataMap.getOrPut(tabType) { mutableListOf() }
            val gridAdapter = MediaGridAdapter(items) { clickedItem ->
                toggleItemSelection(clickedItem)
            }
            activeAdapters[tabType] = gridAdapter
            rvPageMediaGrid.adapter = gridAdapter

            btnPageGrantPermission.setOnClickListener {
                requestStoragePermissions()
            }

            btnPageSystemPickerFallback.setOnClickListener {
                dismiss()
                onBrowseSystemFoldersRequested?.invoke()
            }

            val isLoaded = tabLoadedMap[tabType] ?: false
            if (isLoaded) {
                pbPageLoading.visibility = View.GONE
                updateEmptyState(items.isEmpty(), tabType)
            } else {
                if (hasRequiredPermissions()) {
                    val currentPos = if (::viewPagerGallery.isInitialized) viewPagerGallery.currentItem else 0
                    val currentTab = availableTabs.getOrNull(currentPos)
                    if (tabType == currentTab) {
                        loadMediaForTab(tabType)
                    }
                } else {
                    showPermissionDenied()
                }
            }
        }

        fun showLoading() {
            pbPageLoading.visibility = View.VISIBLE
            layoutPageEmptyContainer.visibility = View.GONE
        }

        fun updateEmptyState(isEmpty: Boolean, tabType: TabType) {
            pbPageLoading.visibility = View.GONE
            btnPageGrantPermission.visibility = View.GONE
            if (isEmpty) {
                layoutPageEmptyContainer.visibility = View.VISIBLE
                tvPageEmptyState.text = when (tabType) {
                    TabType.VIDEO -> "No videos found on device."
                    TabType.PHOTO -> "No photos found on device."
                    TabType.ALL -> "No media files found on device."
                    TabType.AUDIO -> "No audio files found on device."
                }
            } else {
                layoutPageEmptyContainer.visibility = View.GONE
            }
        }

        fun showPermissionDenied() {
            pbPageLoading.visibility = View.GONE
            layoutPageEmptyContainer.visibility = View.VISIBLE
            tvPageEmptyState.text = "Permission denied. Grant access or browse folders."
            btnPageGrantPermission.visibility = View.VISIBLE
        }
    }

    // -------------------------------------------------------------
    // Main Media Adapter supporting lightweight Grid and Audio rows
    // -------------------------------------------------------------
    private inner class MediaGridAdapter(
        private val items: List<MediaModel>,
        private val onItemClick: (MediaModel) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return if (items[position].type == MediaType.AUDIO) 1 else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) {
                val view = inflater.inflate(R.layout.item_media_picker_audio, parent, false)
                AudioItemViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.item_media_picker_thumbnail, parent, false)
                MediaThumbnailViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val selectionIndex = selectedItems.indexOfFirst { it.uri == item.uri }
            when (holder) {
                is MediaThumbnailViewHolder -> holder.bind(item, selectionIndex, onItemClick)
                is AudioItemViewHolder -> holder.bind(item, selectionIndex, onItemClick)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            super.onViewRecycled(holder)
            when (holder) {
                is MediaThumbnailViewHolder -> holder.cancelPendingJobs()
                is AudioItemViewHolder -> holder.cancelPendingJobs()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class MediaThumbnailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val vSelectedOverlay: View = itemView.findViewById(R.id.vSelectedOverlay)
        val layoutSelectionBadge: FrameLayout = itemView.findViewById(R.id.layoutSelectionBadge)
        val tvSelectionNumber: TextView = itemView.findViewById(R.id.tvSelectionNumber)
        val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)

        private var thumbnailJob: kotlinx.coroutines.Job? = null

        fun cancelPendingJobs() {
            thumbnailJob?.cancel()
            thumbnailJob = null
        }

        fun bind(item: MediaModel, selectionIndex: Int, onItemClick: (MediaModel) -> Unit) {
            cancelPendingJobs()
            itemView.tag = item.id

            val isSelected = selectionIndex >= 0
            vSelectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE

            if (isSelected) {
                layoutSelectionBadge.setBackgroundResource(R.drawable.bg_gallery_selected_badge)
                tvSelectionNumber.visibility = View.VISIBLE
                tvSelectionNumber.text = (selectionIndex + 1).toString()
            } else {
                layoutSelectionBadge.setBackgroundResource(R.drawable.bg_gallery_unselected_badge)
                tvSelectionNumber.visibility = View.GONE
            }

            if (item.type == MediaType.VIDEO) {
                tvDuration.visibility = View.VISIBLE
                tvDuration.text = formatDuration(item.durationMs)
            } else {
                tvDuration.visibility = View.GONE
            }

            loadThumbnailAsync(item, ivThumbnail)

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

            thumbnailJob = lifecycleScope.launch(Dispatchers.IO) {
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

        private fun loadThumbnail(item: MediaModel): Bitmap? {
            val context = itemView.context ?: return null
            return try {
                val size = Size(270, 270)
                if (item.localFile != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (item.type == MediaType.VIDEO) {
                            ThumbnailUtils.createVideoThumbnail(item.localFile, size, null)
                        } else {
                            ThumbnailUtils.createImageThumbnail(item.localFile, size, null)
                        }
                    } else {
                        var retriever: MediaMetadataRetriever? = null
                        try {
                            retriever = MediaMetadataRetriever()
                            retriever.setDataSource(item.localFile.absolutePath)
                            retriever.frameAtTime
                        } finally {
                            try { retriever?.release() } catch (_: Exception) {}
                        }
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(item.uri, size, null)
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

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private inner class AudioItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAudioCover: com.google.android.material.imageview.ShapeableImageView = itemView.findViewById(R.id.ivAudioCover)
        val tvAudioTitle: TextView = itemView.findViewById(R.id.tvAudioTitle)
        val tvAudioSubTitle: TextView = itemView.findViewById(R.id.tvAudioSubTitle)
        val tvAudioDuration: TextView = itemView.findViewById(R.id.tvAudioDuration)

        private var audioCoverJob: kotlinx.coroutines.Job? = null

        fun cancelPendingJobs() {
            audioCoverJob?.cancel()
            audioCoverJob = null
        }

        fun bind(item: MediaModel, selectionIndex: Int, onItemClick: (MediaModel) -> Unit) {
            cancelPendingJobs()
            itemView.tag = item.id

            tvAudioTitle.text = item.displayName
            val subText = if (item.artist.isNotBlank() && item.artist != "<unknown>") item.artist else item.bucketName
            tvAudioSubTitle.text = subText
            tvAudioDuration.text = formatDuration(item.durationMs)

            val isSelected = selectionIndex >= 0
            itemView.setBackgroundColor(if (isSelected) Color.parseColor("#2A1118") else Color.TRANSPARENT)

            loadAudioAlbumArtAsync(item, ivAudioCover)

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun loadAudioAlbumArtAsync(item: MediaModel, targetView: ImageView) {
            audioCoverJob = lifecycleScope.launch(Dispatchers.IO) {
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

        private fun loadAudioAlbumArt(item: MediaModel): Bitmap? {
            val context = itemView.context ?: return null
            var retriever: MediaMetadataRetriever? = null
            return try {
                retriever = MediaMetadataRetriever()
                if (item.localFile != null) {
                    retriever.setDataSource(item.localFile.absolutePath)
                } else {
                    retriever.setDataSource(context, item.uri)
                }
                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                } else null
            } catch (e: Exception) {
                null
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
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
        val btnRemoveSelected: FrameLayout = itemView.findViewById(R.id.btnRemoveSelected)

        fun bind(item: MediaModel, onRemoveClick: (MediaModel) -> Unit) {
            itemView.tag = item.id

            if (item.type == MediaType.VIDEO) {
                tvSelectedDuration.visibility = View.VISIBLE
                tvSelectedDuration.text = formatPreciseDuration(item.durationMs)
            } else {
                tvSelectedDuration.visibility = View.GONE
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
                val size = Size(200, 200)
                if (item.localFile != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (item.type == MediaType.VIDEO) {
                            ThumbnailUtils.createVideoThumbnail(item.localFile, size, null)
                        } else {
                            ThumbnailUtils.createImageThumbnail(item.localFile, size, null)
                        }
                    } else {
                        var retriever: MediaMetadataRetriever? = null
                        try {
                            retriever = MediaMetadataRetriever()
                            retriever.setDataSource(item.localFile.absolutePath)
                            retriever.frameAtTime
                        } finally {
                            try { retriever?.release() } catch (_: Exception) {}
                        }
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(item.uri, size, null)
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

        private fun formatPreciseDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val millisTenth = (durationMs % 1000) / 100
            return String.format(Locale.getDefault(), "%02d:%02d.%d", minutes, seconds, millisTenth)
        }
    }
}
