 @file:Suppress("DEPRECATION")
package com.eclip.rup.leb

import android.widget.ImageView
import android.widget.EditText
import kotlinx.coroutines.isActive
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.LayoutInflater
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.eclip.rup.leb.models.TimelineClip
import com.eclip.rup.leb.utils.setBounceClickListener
import com.eclip.rup.leb.utils.performHapticLight
import com.eclip.rup.leb.utils.performHapticClick
import com.eclip.rup.leb.utils.performAppHapticFeedback
import com.eclip.rup.leb.utils.PrecomputedTimelineMetadata
import com.eclip.rup.leb.utils.TimelineClipFlags
import com.eclip.rup.leb.utils.ThumbnailPriorityScheduler
import com.eclip.rup.leb.utils.TimelineScrollState
import com.eclip.rup.leb.utils.PrecomputedWaveformManager
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.eclip.rup.leb.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.google.android.material.textfield.TextInputEditText
import android.graphics.BitmapFactory
import android.graphics.Color
import android.widget.SeekBar
import com.eclip.rup.leb.customviews.HandwritingCanvasView
import com.eclip.rup.leb.customviews.BrushSizeDotView
import com.google.android.material.slider.Slider
import com.eclip.rup.leb.customviews.CustomVideoSeeker
import com.eclip.rup.leb.customviews.DraggableTextOverlayView
import com.eclip.rup.leb.customviews.DraggableImageOverlayView
import com.eclip.rup.leb.customviews.ImageOverlayView
import com.eclip.rup.leb.models.EditOperation
import com.eclip.rup.leb.models.VideoProject
import com.eclip.rup.leb.models.TextPosition
import com.eclip.rup.leb.models.id
import kotlinx.coroutines.Job
import com.eclip.rup.leb.services.FFmpegRenderEngine
import com.eclip.rup.leb.utils.AdaptiveTimelineController
import com.eclip.rup.leb.utils.AdaptiveTimelineListener
import com.eclip.rup.leb.utils.TimelineRenderMode
import com.eclip.rup.leb.utils.BitmapPool
import com.eclip.rup.leb.utils.TimelineSpatialIndex
import com.eclip.rup.leb.utils.TimelinePrefetchPipeline
import com.eclip.rup.leb.engine.thumbnails.ScalableThumbnailEngine
import com.eclip.rup.leb.engine.thumbnails.ThumbnailPriority
import com.eclip.rup.leb.utils.ErrorCode
import com.eclip.rup.leb.utils.ThumbnailCacheManager
import com.eclip.rup.leb.utils.AppCachePolicyManager
import com.eclip.rup.leb.utils.ThermalProtectionManager
import com.eclip.rup.leb.utils.AudioWaveformExtractor
import com.eclip.rup.leb.utils.AppDispatchers
import com.eclip.rup.leb.utils.DraftManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive

import com.eclip.rup.leb.viewmodels.*
import com.eclip.rup.leb.viewmodels.VideoEditingViewModel
import com.eclip.rup.leb.viewmodels.ImportViewModel
import com.eclip.rup.leb.viewmodels.ImportState
import com.eclip.rup.leb.engine.background.BackgroundMediaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout


@Suppress("DEPRECATION")
class VideoEditingActivity : AppCompatActivity() {

    // UI Components
    private lateinit var importViewModel: ImportViewModel
    private lateinit var bgMediaProcessor: BackgroundMediaProcessor
    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var tvDuration: TextView
    private lateinit var sequenceTrackContainer: FrameLayout
    private lateinit var pipTrackContainer: LinearLayout
    private lateinit var audioTrackContainer: LinearLayout
    private lateinit var textTrackContainer: LinearLayout
    private lateinit var stickerTrackContainer: LinearLayout
    private lateinit var effectTrackContainer: LinearLayout
    private lateinit var mosaicTrackContainer: LinearLayout
    private lateinit var imageTrackContainer: LinearLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnMoveLayerUp: ImageButton
    private lateinit var btnMoveLayerDown: ImageButton

    private lateinit var canvasContainer: FrameLayout
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnExpandTimeline: ImageButton
    private lateinit var pipPlayerContainer: FrameLayout
    private lateinit var fullscreenOverlay: FrameLayout
    private lateinit var fullscreenCanvasHolder: FrameLayout
    private lateinit var fullscreenControls: android.widget.RelativeLayout
    private lateinit var btnFullscreenPlayPause: ImageButton
    private lateinit var btnExitFullscreen: ImageButton
    private lateinit var btnExitFullscreenTop: ImageButton
    private lateinit var sbFullscreenSeeker: SeekBar
    private lateinit var tvFullscreenCurrentTime: TextView
    private lateinit var tvFullscreenTotalTime: TextView

    private var isFullscreenMode = false
    private var isTimelineExpanded = false
    private var isUserScrubbingFullscreenSeeker = false
    private fun observeImportState() {
        lifecycleScope.launch {
            importViewModel.importState.collectLatest { state ->
                when (state) {
                    is ImportState.Idle -> { }
                    is ImportState.Loading -> {
                        showCentralizedLoading("import")
                    }
                    is ImportState.Ready -> {
                        hideCentralizedLoading("import")
                        hideCentralizedLoading("initial_loading")
                        if (state.clips.isNotEmpty()) {
                            setupTimelineVideoGuruStyle(state.clips)
                            
                            if (viewModel.project.value == null) {
                                val firstClip = state.clips.first()
                                videoUri = firstClip.uri
                                originalMainVideoDurationMs = firstClip.durationMs
                                viewModel.initializeProject(firstClip.uri, firstClip.uri.lastPathSegment ?: "video")
                                viewModel.updateMainVideoTrim(0L, firstClip.durationMs)
                                
                                if (state.clips.size > 1) {
                                    processMergeClips(state.clips.drop(1))
                                }
                            }
                            
                            transitionToReadinessState(EditorReadinessState.READY)
                            viewModel.project.value?.let { rebuildExoPlayerSource(it) }
                        }
                    }
                    is ImportState.Error -> {
                        hideCentralizedLoading("import")
                        hideCentralizedLoading("initial_loading")
                        Toast.makeText(this@VideoEditingActivity, "Import Error: ${state.message}", Toast.LENGTH_LONG).show()
                        transitionToReadinessState(EditorReadinessState.FAILED)
                    }
                }
            }
        }
    }

    private val fullscreenHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideFullscreenControlsRunnable = Runnable {
        if (::fullscreenControls.isInitialized && isFullscreenMode) {
            fullscreenControls.animate().alpha(0f).setDuration(300).withEndAction {
                fullscreenControls.visibility = View.GONE
            }.start()
        }
    }

    private var timelineMainHeaderView: View? = null
    private lateinit var timelineHorizontalScroll: android.widget.HorizontalScrollView
    private lateinit var adaptiveTimelineController: AdaptiveTimelineController
    private lateinit var timelineVerticalScroll: android.widget.ScrollView
    private lateinit var scrollableTimelineContent: LinearLayout
    private lateinit var btnTimelineAdd: View
    private lateinit var timelineContainer: FrameLayout
    private var precomputedTimelineMetadata: PrecomputedTimelineMetadata = PrecomputedTimelineMetadata.EMPTY
    private lateinit var thumbnailPriorityScheduler: ThumbnailPriorityScheduler
    private var lastSeekUptimeMs: Long = 0L
    private var pendingSeekPositionMs: Long = 0L
    private var wasPlayingBeforeDrag: Boolean = false

    @Volatile private var isUserScrollingTimeline = false
    private var isTrackDragging = false
    private var isProgrammaticScroll = false
    private var isTimelinePinching = false
    private var pixelsPerMs: Float = 0f
    private var lastSnappedTargetMs: Long = -1L
    private enum class ZoomMode { FIT, MEDIUM, PRECISION }
    private var currentZoomMode = ZoomMode.MEDIUM
    private lateinit var scaleDetector: android.view.ScaleGestureDetector

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    private lateinit var customVideoSeeker: CustomVideoSeeker
    private lateinit var timeRulerView: com.eclip.rup.leb.customviews.TimeRulerView
    private lateinit var loadingScreen: View
    private lateinit var exportScreen: View
    private lateinit var previewLoadingOverlay: View
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnPreview: ImageButton
    private lateinit var layoutSaveSplit: View
    private lateinit var btnSaveText: View
    private lateinit var btnSaveDropdown: View
    private lateinit var editingControlsWrapper: LinearLayout
    private lateinit var editingControlsScroll: HorizontalScrollView
    private lateinit var emptyProjectState: View

    private var tvPreviewBadge: TextView? = null
    private var textOverlayView: com.eclip.rup.leb.customviews.TextOverlayView? = null
    private var transitionPreviewOverlayView: com.eclip.rup.leb.customviews.TransitionPreviewOverlayView? = null
    private var activeTransitionIndex: Int = -1
    private var cachedTransitionBitmap: android.graphics.Bitmap? = null

    // ViewModel and Services
    private lateinit var viewModel: VideoEditingViewModel
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    // State
    private var videoUri: Uri? = null
    private var videoFileName: String = ""
    private lateinit var tempInputFile: File
    private var frameExtractionJob: Job? = null
    private var exportJob: Job? = null
    private val activeRenderJobs = mutableListOf<Job>()
    private val activeFrameExtractionJobs = mutableMapOf<Uri, Job>()
    private val activeWaveformJobs = mutableMapOf<String, Job>()
    private var pendingRenderRunnable: Runnable? = null
    private var isVideoLoaded = false
    private var isImportLoading = true
    enum class EditorReadinessState {
        INITIALIZING,
        PROCESSING,
        ALL_REQUIRED_CLIPS_READY,
        READY,
        FAILED
    }
    private var editorReadinessState: EditorReadinessState = EditorReadinessState.INITIALIZING
    private var isEditorLocked: Boolean = true
    private val requiredClipUris = java.util.concurrent.CopyOnWriteArraySet<String>()
    private var activeExtractionCount = 0
    private var chunkDurationsMs = listOf<Long>()
    private var chunkSpeeds = listOf<Float>()
    private var originalMainVideoDurationMs: Long = 0L
    private var primaryVideoAspectRatio: Float = 0f
    
    private var bgPreviewImageView: ImageView? = null
    private var lastActiveClipIndexForBlur: Int = 0
    private var currentBlurJob: Job? = null
    private var lastPhotoSequenceUri: Uri? = null
    
    private val activeExtractionUris = mutableSetOf<Uri>()
    private val frameExtractionSemaphore = kotlinx.coroutines.sync.Semaphore(
        com.eclip.rup.leb.utils.DeviceCapabilityManager.frameExtractionConcurrency
    )
    private val waveformExtractionSemaphore = kotlinx.coroutines.sync.Semaphore(
        com.eclip.rup.leb.utils.DeviceCapabilityManager.waveformConcurrency
    )
    private val sharedFrameViewPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 40) }

    // ── Spatial Indexing & Prefetch Pipeline (O(1) lookups & Predictive prefetching) ──
    private val videoSpatialIndex = TimelineSpatialIndex<Int>()
    private val overlaySpatialIndex = TimelineSpatialIndex<String>()
    private lateinit var timelinePrefetchPipeline: TimelinePrefetchPipeline<Int>

    // ── Timeline Performance Cache ──────────────────────────────────────────
    // Pre-computed data rebuilt only when the project changes, used by
    // syncUiWithPlayer and other hot paths to avoid per-frame recomputation.
    private data class TimelineCache(
        val sequenceItems: List<com.eclip.rup.leb.models.EditOperation.MergeItem>,
        val clipBoundaries: LongArray,   // cumulative start positions
        val totalDuration: Long,
        val colorFilterMap: Map<Int, String>,
        val adjustMap: Map<Int, com.eclip.rup.leb.models.EditOperation.Adjust>,
        val muteMap: Map<Int, Boolean>,
        val transitionMap: Map<Int, com.eclip.rup.leb.models.EditOperation.Transition>,
        val audioOps: List<com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio>,
        val pipOps: List<com.eclip.rup.leb.models.EditOperation.AddImageOverlay>,
        val textOps: List<com.eclip.rup.leb.models.EditOperation.AddText>,
        val filterOps: List<com.eclip.rup.leb.models.EditOperation.ColorFilter>,
        val mosaicOps: List<com.eclip.rup.leb.models.EditOperation.MosaicOverlay>
    )
    private var timelineCache: TimelineCache? = null
    private var lastRenderedProject: com.eclip.rup.leb.models.VideoProject? = null
    private var lastRenderedPixelsPerMs: Float = 0f
    private var lastMediaSourceFingerprint: String = ""
    private var pendingZoomRunnable: Runnable? = null

    private fun rebuildTimelineCache(project: com.eclip.rup.leb.models.VideoProject) {
        val items = getSequenceItems()
        val boundaries = LongArray(items.size + 1)
        var accum = 0L
        for (i in items.indices) {
            boundaries[i] = accum
            accum += items[i].trimmedDurationMs
        }
        boundaries[items.size] = accum

        val colorFilterMap = mutableMapOf<Int, String>()
        val adjustMap = mutableMapOf<Int, com.eclip.rup.leb.models.EditOperation.Adjust>()
        val muteMap = mutableMapOf<Int, Boolean>()
        val transitionMap = mutableMapOf<Int, com.eclip.rup.leb.models.EditOperation.Transition>()

        for (op in project.operations) {
            when (op) {
                is com.eclip.rup.leb.models.EditOperation.ColorFilter -> colorFilterMap[op.index] = op.filterName
                is com.eclip.rup.leb.models.EditOperation.Adjust -> adjustMap[op.index] = op
                is com.eclip.rup.leb.models.EditOperation.MuteClip -> muteMap[op.index] = op.isMuted
                is com.eclip.rup.leb.models.EditOperation.Transition -> transitionMap[op.index] = op
                else -> { }
            }
        }

        timelineCache = TimelineCache(
            sequenceItems = items,
            clipBoundaries = boundaries,
            totalDuration = accum,
            colorFilterMap = colorFilterMap,
            adjustMap = adjustMap,
            muteMap = muteMap,
            transitionMap = transitionMap,
            audioOps = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio>(),
            pipOps = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.AddImageOverlay>(),
            textOps = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.AddText>(),
            filterOps = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.ColorFilter>(),
            mosaicOps = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.MosaicOverlay>()
        )

        // Populate TimelineSpatialIndex for O(1) playhead and O(k) viewport spatial queries
        videoSpatialIndex.clear()
        for (i in items.indices) {
            val start = boundaries[i]
            val end = boundaries[i + 1]
            videoSpatialIndex.insert(i, start, end)
        }

        overlaySpatialIndex.clear()
        for (audio in project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio>()) {
            val start = audio.startTimeMs ?: 0L
            val end = audio.endTimeMs ?: (start + audio.originalDurationMs).coerceAtLeast(start + 1000L)
            overlaySpatialIndex.insert("audio_${audio.id}", start, end)
        }
        for (pip in project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.AddImageOverlay>()) {
            val start = pip.startTimeMs ?: 0L
            val end = pip.endTimeMs ?: (start + (pip.fileDurationMs ?: 3000L))
            overlaySpatialIndex.insert("pip_${pip.id}", start, end)
        }
        for (text in project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.AddText>()) {
            val start = text.startTimeMs ?: 0L
            val end = text.endTimeMs ?: (start + 3000L)
            overlaySpatialIndex.insert("text_${text.id}", start, end)
        }
        for (mosaic in project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.MosaicOverlay>()) {
            val start = mosaic.startTimeMs ?: 0L
            val end = mosaic.endTimeMs ?: (start + 4000L)
            overlaySpatialIndex.insert("mosaic_${mosaic.id}", start, end)
        }
    }

    /** O(1) spatial bucket query falling back to binary search if unindexed */
    private fun findActiveClipIndex(globalPosMs: Long): Int {
        val spatialHits = videoSpatialIndex.queryPoint(globalPosMs)
        if (spatialHits.isNotEmpty()) {
            return spatialHits.first()
        }
        val cache = timelineCache ?: return 0
        val bounds = cache.clipBoundaries
        if (bounds.size <= 1) return 0
        var lo = 0
        var hi = cache.sequenceItems.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (bounds[mid] <= globalPosMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Determines what kind of render is needed based on what changed between old and new project.
     * Returns a set of track categories that need re-rendering.
     */
    private enum class TrackCategory { SEQUENCE, PIP, AUDIO, TEXT, EFFECT, STICKER, MOSAIC, EXO_SOURCE, WIDTHS_ONLY }

    private fun detectChangedTracks(
        oldProject: com.eclip.rup.leb.models.VideoProject,
        newProject: com.eclip.rup.leb.models.VideoProject
    ): Set<TrackCategory> {
        val changed = mutableSetOf<TrackCategory>()

        val oldOps = oldProject.operations
        val newOps = newProject.operations

        // Check proxy changes (affects ExoPlayer source)
        if (oldProject.scrubProxyUri != newProject.scrubProxyUri) {
            changed.add(TrackCategory.SEQUENCE)
            changed.add(TrackCategory.EXO_SOURCE)
        }

        val oldMerge = oldOps.filterIsInstance<EditOperation.Merge>().firstOrNull()
        val newMerge = newOps.filterIsInstance<EditOperation.Merge>().firstOrNull()
        if (oldMerge != null && newMerge != null) {
            val oldProxies = oldMerge.items.map { it.scrubProxyUri }
            val newProxies = newMerge.items.map { it.scrubProxyUri }
            if (oldProxies != newProxies) {
                changed.add(TrackCategory.EXO_SOURCE)
                changed.add(TrackCategory.SEQUENCE)
            }
        }

        // Check sequence (Trim, Merge, SpeedMain, ReverseMain, MirrorMain, MaskMain, RotationMain, OpacityMain, ChromaMain, AnimationMain)
        val oldSequenceOps = oldOps.filter {
            it is EditOperation.Trim || it is EditOperation.Merge ||
            it is EditOperation.SpeedMain || it is EditOperation.ReverseMain ||
            it is EditOperation.MirrorMain || it is EditOperation.MaskMain ||
            it is EditOperation.RotationMain || it is EditOperation.OpacityMain ||
            it is EditOperation.ChromaMain || it is EditOperation.AnimationMain
        }
        val newSequenceOps = newOps.filter {
            it is EditOperation.Trim || it is EditOperation.Merge ||
            it is EditOperation.SpeedMain || it is EditOperation.ReverseMain ||
            it is EditOperation.MirrorMain || it is EditOperation.MaskMain ||
            it is EditOperation.RotationMain || it is EditOperation.OpacityMain ||
            it is EditOperation.ChromaMain || it is EditOperation.AnimationMain
        }
        if (oldSequenceOps != newSequenceOps) {
            changed.add(TrackCategory.SEQUENCE)
            changed.add(TrackCategory.EXO_SOURCE)
        }

        // Check mute state changes (affects ExoPlayer source)
        val oldMuteOps = oldOps.filterIsInstance<EditOperation.MuteClip>()
        val newMuteOps = newOps.filterIsInstance<EditOperation.MuteClip>()
        if (oldMuteOps != newMuteOps) {
            changed.add(TrackCategory.EXO_SOURCE)
            changed.add(TrackCategory.SEQUENCE)
        }

        val oldMuteAudio = oldOps.filterIsInstance<EditOperation.MuteAudio>()
        val newMuteAudio = newOps.filterIsInstance<EditOperation.MuteAudio>()
        if (oldMuteAudio != newMuteAudio) {
            changed.add(TrackCategory.EXO_SOURCE)
        }

        // PIP/Image overlay changes
        val oldPipOps = oldOps.filterIsInstance<EditOperation.AddImageOverlay>()
        val newPipOps = newOps.filterIsInstance<EditOperation.AddImageOverlay>()
        if (oldPipOps != newPipOps) changed.add(TrackCategory.PIP)

        // Audio changes
        val oldAudioOps = oldOps.filterIsInstance<EditOperation.AddBackgroundAudio>()
        val newAudioOps = newOps.filterIsInstance<EditOperation.AddBackgroundAudio>()
        if (oldAudioOps != newAudioOps) {
            changed.add(TrackCategory.AUDIO)
            changed.add(TrackCategory.EXO_SOURCE)
        }

        // Text changes
        val oldTextOps = oldOps.filterIsInstance<EditOperation.AddText>()
        val newTextOps = newOps.filterIsInstance<EditOperation.AddText>()
        if (oldTextOps != newTextOps) changed.add(TrackCategory.TEXT)

        // Filter/effect changes
        val oldFilterOps = oldOps.filterIsInstance<EditOperation.ColorFilter>()
        val newFilterOps = newOps.filterIsInstance<EditOperation.ColorFilter>()
        if (oldFilterOps != newFilterOps) changed.add(TrackCategory.EFFECT)

        // Adjust changes
        val oldAdjustOps = oldOps.filterIsInstance<EditOperation.Adjust>()
        val newAdjustOps = newOps.filterIsInstance<EditOperation.Adjust>()
        if (oldAdjustOps != newAdjustOps) changed.add(TrackCategory.EFFECT)

        // Mosaic changes
        val oldMosaicOps = oldOps.filterIsInstance<EditOperation.MosaicOverlay>()
        val newMosaicOps = newOps.filterIsInstance<EditOperation.MosaicOverlay>()
        if (oldMosaicOps != newMosaicOps) changed.add(TrackCategory.MOSAIC)

        // Transition changes (visual only, no ExoPlayer impact)
        val oldTransOps = oldOps.filterIsInstance<EditOperation.Transition>()
        val newTransOps = newOps.filterIsInstance<EditOperation.Transition>()
        if (oldTransOps != newTransOps) changed.add(TrackCategory.SEQUENCE)

        return changed
    }

    /**
     * Compute a fingerprint of the ExoPlayer media source structure.
     * Only rebuild ExoPlayer when this fingerprint changes.
     */
    private fun computeMediaSourceFingerprint(
        sequenceItems: List<com.eclip.rup.leb.models.EditOperation.MergeItem>,
        audioOps: List<com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio>,
        project: com.eclip.rup.leb.models.VideoProject
    ): String {
        val sb = StringBuilder()
        for (item in sequenceItems) {
            sb.append(item.uri).append('|')
            sb.append(item.trimStartMs).append('|')
            sb.append(item.trimEndMs).append('|')
            sb.append(item.speed).append('|')
            sb.append(item.proxyUri).append('|')
            sb.append(item.scrubProxyUri).append('|')
            sb.append(item.isImage).append(',')
        }
        sb.append("##")
        for (op in audioOps) {
            sb.append(op.audioUri).append('|')
            sb.append(op.startTimeMs).append('|')
            sb.append(op.endTimeMs).append('|')
            sb.append(op.internalStartMs).append('|')
            sb.append(op.internalEndMs).append(',')
        }
        sb.append("##")
        val muteClipOps = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.MuteClip>()
        for (op in muteClipOps) {
            sb.append(op.index).append(':').append(op.isMuted).append(',')
        }
        val hasMuteAudio = project.operations.any { it is com.eclip.rup.leb.models.EditOperation.MuteAudio }
        sb.append("##mute:").append(hasMuteAudio)
        return sb.toString()
    }
    
    private fun showUnsupportedResolutionDialog(message: String = getString(R.string.str_unsupported_resolution_warning)) {
        runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.str_unsupported_resolution_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(R.string.str_choose_supported_video) { _, _ ->
                    openFilePickerMain()
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    finish()
                }
                .show()
        }
    }

    private var activeDirectoryTitleView: TextView? = null
    private var activeDirectoryPathView: TextView? = null

    // Drag-to-rearrange clips state
    private var isDraggingSegment = false
    private var draggedIndex = -1
    private var draggedView: View? = null
    private var initialTouchX = 0f
    private var initialScrollX = 0
    private var segmentViews = mutableListOf<View>()
    private var originalLefts = mutableListOf<Int>()
    private var currentDragOrder = listOf<Int>()
    private var activeSegmentViews = mutableListOf<View>()
    private var initialTimelineClips: List<TimelineClip>? = null


    // ── FONT: cached absolute path to Roboto-Regular.ttf in cacheDir ──────────
    // Populated in onCreate via ffmpegEngine.copyFontToCache().
    // Passed to buildConsolidatedFFmpegCommand() so drawtext can find the font.
    private var fontFilePath: String? = null

    private val thermalListener: (ThermalProtectionManager.ThermalLevel) -> Unit = { level ->
        when (level) {
            ThermalProtectionManager.ThermalLevel.THROTTLED -> {
                Log.w(TAG, "Thermal state THROTTLED: Pausing background tasks, prioritizing visible.")
                // Cancel non-essential background import jobs to reduce memory + CPU pressure
                val mainUriStr = videoUri?.toString()
                backgroundProcessingJobs.entries.toList().forEach { (key, job) ->
                    if (key != mainUriStr && job.isActive) {
                        job.cancel()
                        backgroundProcessingJobs.remove(key)
                        Log.d(TAG, "Cancelled background processing for $key due to thermal throttle")
                    }
                }
            }
            ThermalProtectionManager.ThermalLevel.ELEVATED -> {
                Log.i(TAG, "Thermal state ELEVATED: Running background tasks with low concurrency.")
            }
            ThermalProtectionManager.ThermalLevel.NORMAL -> {
                Log.i(TAG, "Thermal state NORMAL: Resuming full background tasks.")
            }
        }
        
        // Dynamically trigger priority re-evaluation for all pending jobs
        restoreVisibleViewportThumbnails(force = true)
    }

    private var selectedFontCategory: String = com.eclip.rup.leb.utils.FontManager.CATEGORY_ALL

    private fun setupTextFontSelector(formatContainer: View?) {
        val container = formatContainer ?: return
        val btnSelectFont = container.findViewById<View>(R.id.btnSelectFont) ?: return
        val tvSelectedFontName = container.findViewById<TextView>(R.id.tvSelectedFontName)

        val allFonts = com.eclip.rup.leb.utils.FontManager.getAllFonts(this, fontFilePath)
        val currentPath = draggableTextOverlay?.currentFontPath ?: fontFilePath
        val activeFont = allFonts.find { it.path == currentPath } ?: allFonts.firstOrNull()
        if (activeFont != null && tvSelectedFontName != null) {
            tvSelectedFontName.text = activeFont.name
        }

        btnSelectFont.setBounceClickListener {
            switchTextEditingTab(TextEditingTab.FONT)
        }
    }

    private fun setupFontSelectionPanel(toolbar: View) {
        val panel = toolbar.findViewById<View>(R.id.fontSelectionContainer) ?: return
        val rvCategories = panel.findViewById<RecyclerView>(R.id.rvFontCategories) ?: return
        val rvFontCards = panel.findViewById<RecyclerView>(R.id.rvFontCards) ?: return
        val btnImport = panel.findViewById<View>(R.id.btnImportFontPanel)
        val tvEmpty = panel.findViewById<TextView>(R.id.tvEmptyCategoryFonts)

        val allFonts = com.eclip.rup.leb.utils.FontManager.getAllFonts(this, fontFilePath)
        var currentPath = draggableTextOverlay?.currentFontPath ?: fontFilePath ?: allFonts.firstOrNull()?.path

        fun updateCardList() {
            val filtered = if (selectedFontCategory == com.eclip.rup.leb.utils.FontManager.CATEGORY_ALL) {
                allFonts
            } else if (selectedFontCategory == com.eclip.rup.leb.utils.FontManager.CATEGORY_CUSTOM) {
                allFonts.filter { it.isCustom }
            } else {
                allFonts.filter { it.category == selectedFontCategory }
            }

            if (filtered.isEmpty()) {
                tvEmpty?.visibility = View.VISIBLE
                rvFontCards.visibility = View.GONE
            } else {
                tvEmpty?.visibility = View.GONE
                rvFontCards.visibility = View.VISIBLE
                rvFontCards.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
                rvFontCards.adapter = FontCardAdapter(
                    fonts = filtered,
                    selectedPath = currentPath,
                    onSelect = { fontItem ->
                        currentPath = fontItem.path
                        draggableTextOverlay?.setFontPath(fontItem.path)
                        toolbar.findViewById<View>(R.id.formatSettingsContainer)?.let { setupTextFontSelector(it) }
                        (rvFontCards.adapter as? FontCardAdapter)?.updateSelectedPath(fontItem.path)
                    },
                    onDelete = { fontItem ->
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Delete Font")
                            .setMessage("Are you sure you want to delete '${fontItem.name}'?")
                            .setPositiveButton("Delete") { _, _ ->
                                if (com.eclip.rup.leb.utils.FontManager.deleteCustomFont(this, fontItem.path)) {
                                    Toast.makeText(this, "Font deleted", Toast.LENGTH_SHORT).show()
                                    setupFontSelectionPanel(toolbar)
                                } else {
                                    Toast.makeText(this, "Failed to delete font", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                )

                val selectedIndex = filtered.indexOfFirst { it.path == currentPath }
                if (selectedIndex >= 0) {
                    rvFontCards.scrollToPosition(selectedIndex)
                }
            }
        }

        // Setup Categories
        rvCategories.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvCategories.adapter = FontCategoryAdapter(
            categories = com.eclip.rup.leb.utils.FontManager.CATEGORIES,
            selectedCategory = selectedFontCategory,
            onCategorySelected = { category ->
                selectedFontCategory = category
                (rvCategories.adapter as? FontCategoryAdapter)?.updateSelected(category)
                updateCardList()
            }
        )

        updateCardList()

        btnImport?.setBounceClickListener {
            activeFontSelectionCallback = { importedFont ->
                draggableTextOverlay?.setFontPath(importedFont.path)
                selectedFontCategory = com.eclip.rup.leb.utils.FontManager.CATEGORY_ALL
                setupFontSelectionPanel(toolbar)
            }
            importFontLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype", "*/*"))
        }
    }

    private val colorsList = listOf(
        "#FFFFFF", "#000000", "#FF3B30", "#FF9500", "#FFCC00",
        "#34C759", "#30B0C7", "#007AFF", "#5856D6", "#AF52DE", "#FF2A6D",
        "#FF6B6B", "#4ECDC4", "#FFE66D", "#1A535C", "#F7FFF7",
        "#FF1493", "#00FF7F", "#00FFFF", "#FF4500", "#FFD700",
        "#FF80BF", "#99FF99", "#66CCFF", "#CC99FF", "#FFCC99"
    )

    private enum class ColorTargetMode {
        TEXT, LABEL, BORDER, SHADOW
    }

    private var currentColorTargetMode: ColorTargetMode = ColorTargetMode.TEXT
    private var selectedTextColor = "#FFFFFF"
    private var selectedLabelColor = "NONE"
    private var selectedBorderColor = "#000000"
    private var selectedShadowColor = "NONE"
    private var selectedTextOpacity = 1.0f
    private var selectedLabelOpacity = 1.0f
    private var selectedBorderOpacity = 1.0f
    private var selectedShadowOpacity = 1.0f
    private var selectedBorderThickness = 0
    private var selectedShadowDx = 4f
    private var selectedShadowDy = 4f
    private var selectedShadowBlur = 8f
    private var selectedIsBold = false
    private var selectedIsItalic = false
    private var selectedIsUnderline = false
    private var selectedCaseMode = 0

    private fun setupColorPicker(toolbar: View) {
        val colorPickerList = toolbar.findViewById<LinearLayout>(R.id.colorPickerList)
        val tvTitle = toolbar.findViewById<TextView>(R.id.tvColorSectionTitle)
        val tvOpacityLabel = toolbar.findViewById<TextView>(R.id.tvTextColorOpacityLabel)
        val textOpacityContainer = toolbar.findViewById<LinearLayout>(R.id.textOpacityContainer)
        val seekTextColorOpacity = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.seekTextColorOpacity)
        val tvTextColorOpacityValue = toolbar.findViewById<TextView>(R.id.tvTextColorOpacityValue)

        val borderWidthContainer = toolbar.findViewById<LinearLayout>(R.id.borderWidthContainer)
        val seekBorderWidthSlider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.seekBorderWidthSlider)
        val tvBorderWidthValue = toolbar.findViewById<TextView>(R.id.tvBorderWidthValue)

        val shadowControlsContainer = toolbar.findViewById<LinearLayout>(R.id.shadowControlsContainer)
        val seekShadowOpacity = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.seekShadowOpacity)
        val tvShadowOpacityValue = toolbar.findViewById<TextView>(R.id.tvShadowOpacityValue)
        val seekShadowBlur = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.seekShadowBlur)
        val tvShadowBlurValue = toolbar.findViewById<TextView>(R.id.tvShadowBlurValue)
        val seekShadowX = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.seekShadowX)
        val tvShadowXValue = toolbar.findViewById<TextView>(R.id.tvShadowXValue)
        val seekShadowY = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.seekShadowY)
        val tvShadowYValue = toolbar.findViewById<TextView>(R.id.tvShadowYValue)

        val btnText = toolbar.findViewById<TextView>(R.id.btnColorTargetText)
        val btnLabel = toolbar.findViewById<TextView>(R.id.btnColorTargetLabel)
        val btnBorder = toolbar.findViewById<TextView>(R.id.btnColorTargetBorder)
        val btnShadow = toolbar.findViewById<TextView>(R.id.btnColorTargetShadow)

        fun updateTargetButtonsUI() {
            val targets = listOf(
                btnText to ColorTargetMode.TEXT,
                btnLabel to ColorTargetMode.LABEL,
                btnBorder to ColorTargetMode.BORDER,
                btnShadow to ColorTargetMode.SHADOW
            )

            for ((btn, mode) in targets) {
                if (btn == null) continue
                val isSelected = mode == currentColorTargetMode
                if (isSelected) {
                    btn.setBackgroundResource(R.drawable.bg_font_chip_selected)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(Color.parseColor("#8A8A93"))
                }
            }

            tvTitle?.text = when (currentColorTargetMode) {
                ColorTargetMode.TEXT -> "Text Color"
                ColorTargetMode.LABEL -> "Label Background Color"
                ColorTargetMode.BORDER -> "Border / Outline Color"
                ColorTargetMode.SHADOW -> "Shadow Color"
            }

            if (currentColorTargetMode == ColorTargetMode.SHADOW) {
                // Shadow mode: hide single-row opacity and show compact 2-row shadow container
                textOpacityContainer?.visibility = View.GONE
                borderWidthContainer?.visibility = View.GONE
                shadowControlsContainer?.visibility = View.VISIBLE

                // 1. Shadow Opacity Slider
                val shadowOpPercent = (selectedShadowOpacity * 100f).coerceIn(0f, 100f)
                seekShadowOpacity?.value = shadowOpPercent
                tvShadowOpacityValue?.text = "${shadowOpPercent.toInt()}%"
                seekShadowOpacity?.clearOnChangeListeners()
                seekShadowOpacity?.addOnChangeListener { _, value, _ ->
                    val op = value / 100f
                    selectedShadowOpacity = op
                    tvShadowOpacityValue?.text = "${value.toInt()}%"
                    draggableTextOverlay?.setShadowOpacity(op)
                }

                // 2. Shadow Blur Slider
                seekShadowBlur?.value = selectedShadowBlur.coerceIn(0f, 25f)
                tvShadowBlurValue?.text = "${selectedShadowBlur.toInt()}"
                seekShadowBlur?.clearOnChangeListeners()
                seekShadowBlur?.addOnChangeListener { _, value, _ ->
                    selectedShadowBlur = value
                    tvShadowBlurValue?.text = "${value.toInt()}"
                    draggableTextOverlay?.setShadowRadius(value)
                }

                // 3. Shadow X Slider
                seekShadowX?.value = selectedShadowDx.coerceIn(-25f, 25f)
                tvShadowXValue?.text = "${selectedShadowDx.toInt()}"
                seekShadowX?.clearOnChangeListeners()
                seekShadowX?.addOnChangeListener { _, value, _ ->
                    selectedShadowDx = value
                    tvShadowXValue?.text = "${value.toInt()}"
                    draggableTextOverlay?.setShadowDx(value)
                }

                // 4. Shadow Y Slider
                seekShadowY?.value = selectedShadowDy.coerceIn(-25f, 25f)
                tvShadowYValue?.text = "${selectedShadowDy.toInt()}"
                seekShadowY?.clearOnChangeListeners()
                seekShadowY?.addOnChangeListener { _, value, _ ->
                    selectedShadowDy = value
                    tvShadowYValue?.text = "${value.toInt()}"
                    draggableTextOverlay?.setShadowDy(value)
                }
            } else {
                // Non-Shadow modes (Text, Label, Border): show standard single-row opacity
                shadowControlsContainer?.visibility = View.GONE
                textOpacityContainer?.visibility = View.VISIBLE

                tvOpacityLabel?.text = when (currentColorTargetMode) {
                    ColorTargetMode.TEXT -> "Text Opacity"
                    ColorTargetMode.LABEL -> "Label Opacity"
                    ColorTargetMode.BORDER -> "Border Opacity"
                    else -> "Opacity"
                }

                val currentTargetOpacity = when (currentColorTargetMode) {
                    ColorTargetMode.TEXT -> selectedTextOpacity
                    ColorTargetMode.LABEL -> selectedLabelOpacity
                    ColorTargetMode.BORDER -> selectedBorderOpacity
                    else -> 1.0f
                }
                val opacityPercent = (currentTargetOpacity * 100f).coerceIn(0f, 100f)
                seekTextColorOpacity?.value = opacityPercent
                tvTextColorOpacityValue?.text = "${opacityPercent.toInt()}%"
                seekTextColorOpacity?.clearOnChangeListeners()
                seekTextColorOpacity?.addOnChangeListener { _, value, _ ->
                    val op = value / 100f
                    tvTextColorOpacityValue?.text = "${value.toInt()}%"
                    when (currentColorTargetMode) {
                        ColorTargetMode.TEXT -> {
                            selectedTextOpacity = op
                            draggableTextOverlay?.setOpacity(op)
                        }
                        ColorTargetMode.LABEL -> {
                            selectedLabelOpacity = op
                            draggableTextOverlay?.setLabelOpacity(op)
                        }
                        ColorTargetMode.BORDER -> {
                            selectedBorderOpacity = op
                            draggableTextOverlay?.setBorderOpacity(op)
                            val selectedId = viewModel.selectedOperationId.value
                            if (selectedId != null) {
                                val currentOp = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                                if (currentOp != null) {
                                    viewModel.updateOperation(currentOp.copy(borderOpacity = op))
                                }
                            }
                        }
                        else -> {}
                    }
                }

                if (currentColorTargetMode == ColorTargetMode.BORDER) {
                    borderWidthContainer?.visibility = View.VISIBLE
                    seekBorderWidthSlider?.value = selectedBorderThickness.toFloat().coerceIn(0f, 100f)
                    tvBorderWidthValue?.text = "${selectedBorderThickness}%"
                    seekBorderWidthSlider?.clearOnChangeListeners()
                    seekBorderWidthSlider?.addOnChangeListener { _, value, _ ->
                        val thickness = value.toInt()
                        selectedBorderThickness = thickness
                        tvBorderWidthValue?.text = "${thickness}%"
                        if (selectedBorderColor == "NONE" || selectedBorderColor.isEmpty()) {
                            selectedBorderColor = "#000000"
                            draggableTextOverlay?.setBorderColor(selectedBorderColor)
                        }
                        draggableTextOverlay?.setBorderThickness(thickness)

                        val selectedId = viewModel.selectedOperationId.value
                        if (selectedId != null) {
                            val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                            if (op != null) {
                                val updatedOp = op.copy(
                                    borderThickness = thickness,
                                    borderColor = selectedBorderColor,
                                    borderOpacity = selectedBorderOpacity
                                )
                                viewModel.updateOperation(updatedOp)
                            }
                        }
                    }
                } else {
                    borderWidthContainer?.visibility = View.GONE
                }
            }
        }

        btnText?.setBounceClickListener {
            currentColorTargetMode = ColorTargetMode.TEXT
            setupColorPicker(toolbar)
        }
        btnLabel?.setBounceClickListener {
            currentColorTargetMode = ColorTargetMode.LABEL
            setupColorPicker(toolbar)
        }
        btnBorder?.setBounceClickListener {
            currentColorTargetMode = ColorTargetMode.BORDER
            if (selectedBorderColor == "NONE" || selectedBorderColor.isEmpty()) {
                selectedBorderColor = "#000000"
            }
            if (selectedBorderThickness == 0) {
                selectedBorderThickness = 15
            }
            draggableTextOverlay?.setBorderColor(selectedBorderColor)
            draggableTextOverlay?.setBorderThickness(selectedBorderThickness)

            val selectedId = viewModel.selectedOperationId.value
            if (selectedId != null) {
                val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                if (op != null) {
                    val updatedOp = op.copy(
                        borderColor = selectedBorderColor,
                        borderThickness = selectedBorderThickness,
                        borderOpacity = selectedBorderOpacity
                    )
                    viewModel.updateOperation(updatedOp)
                }
            }
            setupColorPicker(toolbar)
        }
        btnShadow?.setBounceClickListener {
            currentColorTargetMode = ColorTargetMode.SHADOW
            setupColorPicker(toolbar)
        }

        updateTargetButtonsUI()

        val currentActiveColor = when (currentColorTargetMode) {
            ColorTargetMode.TEXT -> selectedTextColor
            ColorTargetMode.LABEL -> selectedLabelColor
            ColorTargetMode.BORDER -> selectedBorderColor
            ColorTargetMode.SHADOW -> selectedShadowColor
        }

        val density = resources.displayMetrics.density
        val sizePx = (36 * density).toInt()
        val marginPx = (6 * density).toInt()

        fun populateList(container: LinearLayout?, colors: List<String>, includeNoneOption: Boolean = false) {
            if (container == null) return
            container.removeAllViews()

            if (includeNoneOption && currentColorTargetMode != ColorTargetMode.TEXT) {
                // Add "None / Transparent" (No Color) option
                val isNoneSelected = currentActiveColor.equals("NONE", ignoreCase = true) || currentActiveColor.equals("#00000000", ignoreCase = true)
                val noneFrame = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        setMargins(marginPx, (2 * density).toInt(), marginPx, (2 * density).toInt())
                    }
                    val bgShape = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#1C1C24"))
                        if (isNoneSelected) {
                            setStroke((2.5f * density).toInt(), Color.parseColor("#FF2A6D"))
                        } else {
                            setStroke(1, Color.parseColor("#3A3A48"))
                        }
                    }
                    background = bgShape

                    val icon = ImageView(this@VideoEditingActivity).apply {
                        layoutParams = FrameLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                            gravity = Gravity.CENTER
                        }
                        setImageResource(R.drawable.ic_close_24)
                        setColorFilter(Color.parseColor("#FF5252"))
                    }
                    addView(icon)

                    setBounceClickListener {
                        when (currentColorTargetMode) {
                            ColorTargetMode.TEXT -> {}
                            ColorTargetMode.LABEL -> {
                                selectedLabelColor = "NONE"
                                draggableTextOverlay?.setLabelColor("NONE")
                            }
                            ColorTargetMode.BORDER -> {
                                selectedBorderColor = "NONE"
                                draggableTextOverlay?.setBorderColor("NONE")
                                draggableTextOverlay?.setBorderThickness(0)
                                val selectedId = viewModel.selectedOperationId.value
                                if (selectedId != null) {
                                    val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                                    if (op != null) {
                                        val updatedOp = op.copy(
                                            borderColor = "NONE",
                                            borderThickness = 0
                                        )
                                        viewModel.updateOperation(updatedOp)
                                    }
                                }
                            }
                            ColorTargetMode.SHADOW -> {
                                selectedShadowColor = "NONE"
                                draggableTextOverlay?.setShadowColor("NONE")
                            }
                        }
                        setupColorPicker(toolbar)
                    }
                }
                container.addView(noneFrame)
            }

            for (colorHex in colors) {
                val isSelected = colorHex.equals(currentActiveColor, ignoreCase = true)
                val colorView = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        setMargins(marginPx, (2 * density).toInt(), marginPx, (2 * density).toInt())
                    }

                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(colorHex))
                        if (isSelected) {
                            setStroke((2.5f * density).toInt(), Color.parseColor("#FF2A6D"))
                        } else {
                            if (colorHex.equals("#FFFFFF", ignoreCase = true) || colorHex.equals("#000000", ignoreCase = true)) {
                                setStroke(1, Color.parseColor("#444455"))
                            }
                        }
                    }
                    background = shape

                    setBounceClickListener {
                        when (currentColorTargetMode) {
                            ColorTargetMode.TEXT -> {
                                selectedTextColor = colorHex
                                draggableTextOverlay?.setTextColor(colorHex)
                            }
                            ColorTargetMode.LABEL -> {
                                selectedLabelColor = colorHex
                                draggableTextOverlay?.setLabelColor(colorHex)
                            }
                            ColorTargetMode.BORDER -> {
                                selectedBorderColor = colorHex
                                if (colorHex != "NONE" && selectedBorderThickness == 0) {
                                    selectedBorderThickness = 15
                                }
                                draggableTextOverlay?.setBorderColor(colorHex)
                                if (colorHex != "NONE") {
                                    draggableTextOverlay?.setBorderThickness(selectedBorderThickness)
                                } else {
                                    draggableTextOverlay?.setBorderThickness(0)
                                }

                                val selectedId = viewModel.selectedOperationId.value
                                if (selectedId != null) {
                                    val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                                    if (op != null) {
                                        val updatedOp = op.copy(
                                            borderColor = colorHex,
                                            borderThickness = if (colorHex == "NONE") 0 else selectedBorderThickness,
                                            borderOpacity = selectedBorderOpacity
                                        )
                                        viewModel.updateOperation(updatedOp)
                                    }
                                }
                            }
                            ColorTargetMode.SHADOW -> {
                                selectedShadowColor = colorHex
                                draggableTextOverlay?.setShadowColor(colorHex)
                            }
                        }
                        setupColorPicker(toolbar)
                    }
                }
                container.addView(colorView)
            }
        }

        populateList(colorPickerList, colorsList, includeNoneOption = true)
    }

    private class FontCategoryAdapter(
        private val categories: List<String>,
        private var selectedCategory: String,
        private val onCategorySelected: (String) -> Unit
    ) : RecyclerView.Adapter<FontCategoryAdapter.ViewHolder>() {

        fun updateSelected(category: String) {
            val oldPos = categories.indexOf(selectedCategory)
            val newPos = categories.indexOf(category)
            selectedCategory = category
            if (oldPos >= 0) notifyItemChanged(oldPos)
            if (newPos >= 0) notifyItemChanged(newPos)
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvChip: TextView = view.findViewById(R.id.tvCategoryChip)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_font_category_chip, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val cat = categories[position]
            holder.tvChip.text = cat
            val isSelected = cat == selectedCategory
            if (isSelected) {
                holder.tvChip.setBackgroundResource(R.drawable.bg_font_chip_selected)
                holder.tvChip.setTextColor(Color.WHITE)
            } else {
                holder.tvChip.setBackgroundResource(R.drawable.bg_font_chip_normal)
                holder.tvChip.setTextColor(Color.parseColor("#C5C7D3"))
            }

            holder.itemView.setOnClickListener {
                if (cat != selectedCategory) {
                    onCategorySelected(cat)
                }
            }
        }

        override fun getItemCount() = categories.size
    }

    private class FontCardAdapter(
        private val fonts: List<com.eclip.rup.leb.utils.FontItem>,
        private var selectedPath: String?,
        private val onSelect: (com.eclip.rup.leb.utils.FontItem) -> Unit,
        private val onDelete: (com.eclip.rup.leb.utils.FontItem) -> Unit
    ) : RecyclerView.Adapter<FontCardAdapter.ViewHolder>() {

        fun updateSelectedPath(newPath: String) {
            val oldIndex = fonts.indexOfFirst { it.path == selectedPath }
            val newIndex = fonts.indexOfFirst { it.path == newPath }
            selectedPath = newPath
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (newIndex >= 0) notifyItemChanged(newIndex)
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view.findViewById(R.id.fontCardRoot)
            val tvName: TextView = view.findViewById(R.id.tvFontName)
            val tvCategory: TextView? = view.findViewById(R.id.tvFontCategory)
            val tvSample: TextView = view.findViewById(R.id.tvFontSample)
            val ivSelected: ImageView = view.findViewById(R.id.ivFontSelected)
            val ivDelete: ImageView = view.findViewById(R.id.ivFontDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_font_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val font = fonts[position]
            holder.tvName.text = font.name
            holder.tvCategory?.text = if (font.isCustom) "Custom Font" else font.category
            holder.tvSample.text = font.sampleText
            holder.tvSample.typeface = font.typeface ?: Typeface.DEFAULT

            val isSelected = selectedPath != null && font.path == selectedPath
            if (isSelected) {
                holder.root.setBackgroundResource(R.drawable.bg_font_card_selected)
                holder.ivSelected.visibility = View.VISIBLE
                holder.tvName.setTextColor(Color.parseColor("#FF2A6D"))
                holder.tvCategory?.setTextColor(Color.parseColor("#E095B0"))
                holder.tvSample.setTextColor(Color.WHITE)
            } else {
                holder.root.setBackgroundResource(R.drawable.bg_font_card)
                holder.ivSelected.visibility = View.GONE
                holder.tvName.setTextColor(Color.WHITE)
                holder.tvCategory?.setTextColor(Color.parseColor("#8A8A93"))
                holder.tvSample.setTextColor(Color.parseColor("#D0D0DC"))
            }

            if (font.isCustom) {
                holder.ivDelete.visibility = View.VISIBLE
                holder.ivDelete.setOnClickListener {
                    onDelete(font)
                }
            } else {
                holder.ivDelete.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                onSelect(font)
            }
        }

        override fun getItemCount() = fonts.size
    }

    // Cache for frame extraction
    private val extractedFrames = mutableListOf<Bitmap>()

    // Inline text editing state
    private enum class TextEditingTab {
        KEYBOARD,
        FONT,
        COLOR,
        ANIMATION,
        ADJUSTMENT
    }
    private var currentTextEditingTab: TextEditingTab = TextEditingTab.KEYBOARD
    private var normalContentRootHeight: Int = 0
    private var savedKeyboardHeight: Int = 0
    private var currentTextInAnimation: String = "NONE"
    private var currentTextOutAnimation: String = "NONE"
    private var currentTextAnimationDurationMs: Long = 500L
    private var isPickingInAnimInline: Boolean = true

    private var draggableTextOverlay: DraggableTextOverlayView? = null
    private var textEditingToolbar: View? = null
    private var textLayerToolbar: View? = null
    private var isTextEditingActive = false
    private var isTextLayerEditingActive = false
    private var savedPlayerContainerHeight: Int = 0
    private var isKeyboardCurrentlyOpen: Boolean = false

    // Inline image overlay editing state
    private var draggableImageOverlay: DraggableImageOverlayView? = null
    private var imageOverlayView: ImageOverlayView? = null
    private var imageEditingToolbar: View? = null
    private var isImageEditingActive = false
    private var isMagnetEnabled = true

    // Video layer selection state
    private var selectedVideoIndex: Int? = null
    private var videoEditingToolbar: View? = null
    private var audioEditingToolbar: View? = null
    private var backgroundEditingToolbar: View? = null
    private var speedEditingToolbar: View? = null
    private var cropEditingToolbar: View? = null
    private var cropOverlayView: com.eclip.rup.leb.customviews.CropOverlayView? = null
    private var videoMaskOverlayView: com.eclip.rup.leb.customviews.VideoMaskOverlayView? = null
    private var mainVideoMaskContainer: com.eclip.rup.leb.customviews.MaskedFrameLayout? = null
    private var initialCropOperation: com.eclip.rup.leb.models.EditOperation.Crop? = null

    // Mosaic editing state
    private var mosaicOverlayView: com.eclip.rup.leb.customviews.MosaicOverlayView? = null
    private var mosaicEditingToolbar: View? = null
    private var isMosaicEditingActive = false
    private var activeEditingMosaicOpId: String? = null
    private var activeMosaicShape = EditOperation.MaskShape.RECTANGLE
    private var activeMosaicIntensity = 15


    // Segmented preview state
    private var previewJob: Job? = null
    private var isShowingPreview = false
    private var isRenderingPreview = false
    private var previewFile: File? = null
    private var lastPreviewFingerprint: String? = null
    
    private var audioPreviewPlayer: android.media.MediaPlayer? = null
    
    private var isInitialFitDone = false

    private var mediaRecorder: android.media.MediaRecorder? = null
    private var isRecordingVoiceOver = false
    private var voiceOverFile: File? = null
    private var voiceOverStartTimeMs = 0L

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showVoiceOverDialog()
        } else {
            Toast.makeText(this, R.string.toast_microphone_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    private var shouldQuitAfterSave = false
    private var currentDraftId: String = ""

    private fun saveDraftToStorage() {
        try {
            val proj = viewModel.project.value ?: return
            val totalDur = getTotalSequenceDuration()
            if (currentDraftId.isBlank()) {
                currentDraftId = com.eclip.rup.leb.utils.DraftManager.generateUniqueDraftId()
            }
            com.eclip.rup.leb.utils.DraftManager.saveDraftAsync(
                context = applicationContext,
                draftId = currentDraftId,
                project = proj,
                durationMs = totalDur
            )
        } catch (e: Exception) {
            Log.d(TAG, "Draft save error: ${e.message}")
        }
    }

    private val saveProjectLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val project = viewModel.project.value
                if (project != null) {
                    val recipe = com.eclip.rup.leb.models.EditRecipe.fromVideoProject(project.sourceName, project)
                    val json = com.eclip.rup.leb.utils.ProjectSerializer.serialize(recipe)
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(json.toByteArray())
                    }
                    viewModel.markProjectSaved()
                    Toast.makeText(this, "Project saved successfully", Toast.LENGTH_SHORT).show()
                    if (shouldQuitAfterSave) {
                        shouldQuitAfterSave = false
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save project", e)
                Toast.makeText(this, "Failed to save project", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var activeFontSelectionCallback: ((com.eclip.rup.leb.utils.FontItem) -> Unit)? = null

    private val importFontLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val importedFont = com.eclip.rup.leb.utils.FontManager.importFontFromUri(this, uri)
            if (importedFont != null) {
                Toast.makeText(this, "Font '${importedFont.name}' imported", Toast.LENGTH_SHORT).show()
                textEditingToolbar?.findViewById<View>(R.id.formatSettingsContainer)?.let { setupTextFontSelector(it) }
                activeFontSelectionCallback?.invoke(importedFont)
            } else {
                Toast.makeText(this, "Failed to import font file. Must be a valid .ttf or .otf", Toast.LENGTH_LONG).show()
            }
        }
    }
    private var photoSequencePreviewImageView: ImageView? = null

    /**
     * Calculates a duration-adaptive timeline scale (pixels per millisecond).
     * Prevents multi-million-pixel View/Canvas overflows on long media (e.g. 10m, 1h, 12h videos)
     * by bounding the total initial timeline width to a safe limit (~60,000px).
     */
    private fun calculateAdaptivePixelsPerMs(totalDurationMs: Long): Float {
        val density = resources.displayMetrics.density
        val defaultScale = 0.12f * density
        if (totalDurationMs <= 0L) return defaultScale

        val maxAllowedWidth = 60_000f * (density / 2.75f).coerceIn(0.8f, 1.5f)
        val maxSafeScale = maxAllowedWidth / totalDurationMs
        return minOf(defaultScale, maxSafeScale).coerceAtLeast(0.0001f * density)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_editing)
        photoSequencePreviewImageView = findViewById(R.id.photoSequencePreviewImageView)
        pixelsPerMs = 0.12f * resources.displayMetrics.density

        // Editor Screen permanently runs in standard Disabled mode (visible status & navigation bars)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        // Initialize ViewModel and engine
        viewModel = ViewModelProvider(this).get(VideoEditingViewModel::class.java)
        importViewModel = ViewModelProvider(this).get(ImportViewModel::class.java)
        bgMediaProcessor = BackgroundMediaProcessor(applicationContext)
        ffmpegEngine = FFmpegRenderEngine(this)
        
        observeImportState()
        
        // Export receiver registration removed — export runs directly in ExportActivity

        // Register back-press callback to prompt for quit confirmation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isEditorLocked) {
                    cancelAllProcessingAndExit()
                    return
                }
                if (isFullscreenMode) {
                    exitFullscreenMode()
                } else if (isTextEditingActive) {
                    draggableTextOverlay?.deactivate()
                    viewModel.selectOperation(null)
                    exitTextEditingMode()
                } else if (isTextLayerEditingActive) {
                    draggableTextOverlay?.deactivate()
                    viewModel.selectOperation(null)
                    exitTextLayerMode()
                } else if (isImageEditingActive) {
                    draggableImageOverlay?.deactivate()
                    viewModel.selectOperation(null)
                    exitImageEditingMode()
                } else if (isHandwritingActive) {
                    val canvasView = findViewById<HandwritingCanvasView>(R.id.handwritingCanvasView)
                    if (canvasView != null && !canvasView.isEmpty()) {
                        MaterialAlertDialogBuilder(this@VideoEditingActivity)
                            .setTitle("Discard Handwriting?")
                            .setMessage("Are you sure you want to discard your drawing?")
                            .setPositiveButton("Discard") { _, _ -> closeHandwritingMode() }
                            .setNegativeButton("Keep Editing", null)
                            .show()
                    } else {
                        closeHandwritingMode()
                    }
                } else {
                    showQuitConfirmationDialog()
                }
            }
        })

        // ── Copy the bundled font from assets → cacheDir asynchronously in background ──
        // Font configuration and native library loading must not block onCreate on the main thread
        lifecycleScope.launch(Dispatchers.IO) {
            val path = ffmpegEngine.copyFontToCache("fonts/Roboto-Regular.ttf")
            fontFilePath = path
            Log.d(TAG, "Font path: $path (exists=${path?.let { File(it).exists() }})")
            if (path == null) {
                Log.w(TAG, "Font copy failed — check assets/fonts/Roboto-Regular.ttf")
            }
        }

        // Initialize UI components
        initializeViews()
        setupExoPlayer()
        setupCustomSeeker()
        setupHandwritingControls()
        observeViewModelState()

        // Play/Pause button logic
        btnPlayPause.setBounceClickListener {
            if (::player.isInitialized && isVideoLoaded) {
                if (player.isPlaying || player.playWhenReady) {
                    player.pause()
                    btnPlayPause.setImageResource(R.drawable.ic_play_24)
                    stopProgressUpdater()
                } else {
                    val totalDuration = getTotalSequenceDuration()
                    val currentGlobalPos = getGlobalPosition()
                    
                    // If we reached the end of the timeline, restart from beginning
                    if (currentGlobalPos >= totalDuration - 50L || player.playbackState == Player.STATE_ENDED) {
                        seekToGlobalPosition(0L, force = true)
                        try {
                            isProgrammaticScroll = true
                            timelineHorizontalScroll.scrollTo(0, 0)
                        } finally {
                            isProgrammaticScroll = false
                        }
                        updateDurationDisplay(0, totalDuration.toInt())
                    }
                    
                    player.play()
                    btnPlayPause.setImageResource(R.drawable.ic_pause_24)
                    startProgressUpdater()
                }
            }
        }

        // Mute/Unmute button logic
        val btnMute = findViewById<ImageView>(R.id.btnMute)
        btnMute?.setBounceClickListener { toggleMuteAction() }
        findViewById<View>(R.id.btnTimelineMuteContainer)?.setBounceClickListener { toggleMuteAction() }

        val btnCaptureFrame = findViewById<ImageButton>(R.id.btnCaptureFrame)
        btnCaptureFrame?.setBounceClickListener {
            captureCurrentFrame()
        }

        val btnMagnet = findViewById<ImageButton>(R.id.btnMagnet)
        btnMagnet?.setImageResource(if (isMagnetEnabled) R.drawable.ic_magnet_24 else R.drawable.ic_magnet_off_24)
        btnMagnet?.setBounceClickListener {
            isMagnetEnabled = !isMagnetEnabled
            if (isMagnetEnabled) {
                btnMagnet.setImageResource(R.drawable.ic_magnet_24)
                Toast.makeText(this, R.string.toast_snapping_enabled, Toast.LENGTH_SHORT).show()
            } else {
                btnMagnet.setImageResource(R.drawable.ic_magnet_off_24)
                Toast.makeText(this, R.string.toast_snapping_disabled, Toast.LENGTH_SHORT).show()
            }
            draggableTextOverlay?.isSnappingEnabled = isMagnetEnabled
            draggableImageOverlay?.isSnappingEnabled = isMagnetEnabled
        }

        btnMoveLayerUp = findViewById(R.id.btnMoveLayerUp)
        btnMoveLayerDown = findViewById(R.id.btnMoveLayerDown)
        btnMoveLayerUp.setBounceClickListener {
            viewModel.selectedOperationId.value?.let { id ->
                viewModel.moveOverlayOperation(id, moveUp = true)
            }
        }
        btnMoveLayerDown.setBounceClickListener {
            viewModel.selectedOperationId.value?.let { id ->
                viewModel.moveOverlayOperation(id, moveUp = false)
            }
        }

        scaleDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var pinchInitialPixelsPerMs = 0f
            private var pinchCumulativeScale = 1.0f
            private var pinchFocalTimestampMs = 0L

            override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                val totalDuration = getTotalSequenceDuration()
                if (totalDuration <= 0L) return false

                pausePlaybackOnTimelineInteraction()
                isTimelinePinching = true
                pinchInitialPixelsPerMs = pixelsPerMs
                pinchCumulativeScale = 1.0f

                val scrollX = timelineHorizontalScroll.scrollX
                val halfPadding = timelineHorizontalScroll.paddingLeft.toFloat().takeIf { it > 0f } ?: (timelineHorizontalScroll.width / 2f)
                val focalXInTimeline = detector.focusX + scrollX - halfPadding
                pinchFocalTimestampMs = if (pixelsPerMs > 0f) (focalXInTimeline / pixelsPerMs).toLong() else 0L

                if (::scrollableTimelineContent.isInitialized) {
                    scrollableTimelineContent.pivotX = focalXInTimeline.coerceAtLeast(0f)
                    scrollableTimelineContent.pivotY = scrollableTimelineContent.height / 2f
                }
                return true
            }

            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val totalDuration = getTotalSequenceDuration()
                if (totalDuration <= 0L || pinchInitialPixelsPerMs <= 0f) return true

                val density = resources.displayMetrics.density
                val minPixelsPerMs = maxOf(0.0001f * density, (timelineHorizontalScroll.width.toFloat() - 32.dpToPx()) / totalDuration)
                val maxPixelsPerMs = minOf(1.5f * density, 120_000f / totalDuration).coerceAtLeast(minPixelsPerMs)

                pinchCumulativeScale *= detector.scaleFactor
                val minScale = minPixelsPerMs / pinchInitialPixelsPerMs
                val maxScale = maxPixelsPerMs / pinchInitialPixelsPerMs
                val clampedScale = pinchCumulativeScale.coerceIn(minScale, maxScale)

                // GPU Hardware Compositor Transform ONLY (Zero Measure, Zero Layout, Zero GC)
                if (::scrollableTimelineContent.isInitialized) {
                    scrollableTimelineContent.scaleX = clampedScale
                }
                return true
            }

            override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                super.onScaleEnd(detector)
                isTimelinePinching = false

                val totalDuration = getTotalSequenceDuration()
                if (totalDuration <= 0L || pinchInitialPixelsPerMs <= 0f) {
                    if (::scrollableTimelineContent.isInitialized) {
                        scrollableTimelineContent.scaleX = 1.0f
                    }
                    return
                }

                val density = resources.displayMetrics.density
                val minPixelsPerMs = maxOf(0.0001f * density, (timelineHorizontalScroll.width.toFloat() - 32.dpToPx()) / totalDuration)
                val maxPixelsPerMs = minOf(1.5f * density, 120_000f / totalDuration).coerceAtLeast(minPixelsPerMs)

                val newPixelsPerMs = (pinchInitialPixelsPerMs * pinchCumulativeScale).coerceIn(minPixelsPerMs, maxPixelsPerMs)
                pixelsPerMs = newPixelsPerMs

                // Reset GPU visual scale back to 1.0f before single crisp relayout pass
                if (::scrollableTimelineContent.isInitialized) {
                    scrollableTimelineContent.scaleX = 1.0f
                    scrollableTimelineContent.pivotX = 0f
                    scrollableTimelineContent.pivotY = 0f
                }

                // Single, batched crisp relayout pass at gesture end
                updateTimelineZoomLayout(totalDuration)
                viewModel.project.value?.let { renderTracks(it) }

                // Restore exact focal alignment under user's fingers
                val halfPadding = timelineHorizontalScroll.paddingLeft.toFloat().takeIf { it > 0f } ?: (timelineHorizontalScroll.width / 2f)
                val maxScroll = (totalDuration * pixelsPerMs).toInt()
                val targetScrollX = ((pinchFocalTimestampMs * pixelsPerMs) - detector.focusX + halfPadding).toInt().coerceIn(0, maxScroll)
                try {
                    isProgrammaticScroll = true
                    timelineHorizontalScroll.scrollTo(targetScrollX, 0)
                } finally {
                    isProgrammaticScroll = false
                }
            }
        })

        // Update play/pause button icon on playback state change
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    btnPlayPause.setImageResource(R.drawable.ic_pause_24)
                    if (::btnFullscreenPlayPause.isInitialized) {
                        btnFullscreenPlayPause.setImageResource(R.drawable.ic_pause_24)
                    }
                } else if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
                    btnPlayPause.setImageResource(R.drawable.ic_play_24)
                    if (::btnFullscreenPlayPause.isInitialized) {
                        btnFullscreenPlayPause.setImageResource(R.drawable.ic_play_24)
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && player.playbackState != Player.STATE_ENDED) {
                    btnPlayPause.setImageResource(R.drawable.ic_pause_24)
                    if (::btnFullscreenPlayPause.isInitialized) {
                        btnFullscreenPlayPause.setImageResource(R.drawable.ic_pause_24)
                    }
                } else if (!playWhenReady) {
                    btnPlayPause.setImageResource(R.drawable.ic_play_24)
                    if (::btnFullscreenPlayPause.isInitialized) {
                        btnFullscreenPlayPause.setImageResource(R.drawable.ic_play_24)
                    }
                }
            }
        })
    }


    private fun showProErrorDialog(errorCode: ErrorCode, technicalLog: String) {
        val intent = Intent(this, ErrorDisplayActivity::class.java).apply {
            putExtra("ERROR_CODE", errorCode.code)
            putExtra("ERROR_DESCRIPTION", errorCode.description)
            putExtra("ERROR_LOG", technicalLog)
        }
        startActivity(intent)
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)
        playerContainer = findViewById(R.id.playerContainer)
        bgPreviewImageView = findViewById(R.id.bgPreviewImageView)
        transitionPreviewOverlayView = findViewById(R.id.transitionPreviewOverlayView)
        canvasContainer = findViewById(R.id.canvasContainer)

        playerContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && !isTextEditingActive && findViewById<View>(R.id.seekerContainer)?.visibility == View.VISIBLE) {
                savedPlayerContainerHeight = h
            }
        }

        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnExpandTimeline = findViewById(R.id.btnExpandTimeline)
        pipPlayerContainer = findViewById(R.id.pipPlayerContainer)
        fullscreenOverlay = findViewById(R.id.fullscreenOverlay)
        fullscreenCanvasHolder = findViewById(R.id.fullscreenCanvasHolder)
        fullscreenControls = findViewById(R.id.fullscreenControls)
        btnFullscreenPlayPause = findViewById(R.id.btnFullscreenPlayPause)
        btnExitFullscreen = findViewById(R.id.btnExitFullscreen)
        btnExitFullscreenTop = findViewById(R.id.btnExitFullscreenTop)
        sbFullscreenSeeker = findViewById(R.id.sbFullscreenSeeker)
        tvFullscreenCurrentTime = findViewById(R.id.tvFullscreenCurrentTime)
        tvFullscreenTotalTime = findViewById(R.id.tvFullscreenTotalTime)

        setupFullscreenAndPipControls()

        val emptyAreaClickListener = View.OnClickListener {
            if (hasActiveSelectionOrToolbar()) {
                clearSelectionAndReturnToMainToolbar()
            }
        }
        playerContainer.setOnClickListener(emptyAreaClickListener)
        canvasContainer.setOnClickListener(emptyAreaClickListener)
        playerView.setOnClickListener(emptyAreaClickListener)
        findViewById<View>(R.id.playerControlBar)?.setOnClickListener(emptyAreaClickListener)
        findViewById<View>(R.id.tracksVerticalLayout)?.setOnClickListener(emptyAreaClickListener)

        val canvasLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (!isTextEditingActive) {
                triggerCanvasLayoutUpdate()
            }
        }
        playerContainer.viewTreeObserver.addOnGlobalLayoutListener(canvasLayoutListener)
        pipPlayerContainer.viewTreeObserver.addOnGlobalLayoutListener(canvasLayoutListener)
        fullscreenCanvasHolder.viewTreeObserver.addOnGlobalLayoutListener(canvasLayoutListener)

        val contentRoot = findViewById<View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { _, insets ->
            val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            val imeBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val sysBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            val netKeyboardHeight = (imeBottom - sysBottom).coerceAtLeast(0)

            val isKeyboardOpen = imeVisible || netKeyboardHeight > (80 * resources.displayMetrics.density).toInt()
            if (netKeyboardHeight > (100 * resources.displayMetrics.density).toInt()) {
                savedKeyboardHeight = netKeyboardHeight
            }
            if (isTextEditingActive) {
                updateTextEditingLayoutForKeyboard(isKeyboardOpen)
            }
            insets
        }

        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(
            contentRoot,
            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: androidx.core.view.WindowInsetsCompat,
                    runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>
                ): androidx.core.view.WindowInsetsCompat {
                    if (!isTextEditingActive) return insets
                    val imeAnim = runningAnimations.find {
                        it.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime() != 0
                    } ?: return insets

                    val imeBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                    val sysBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
                    val currentIme = (imeBottom - sysBottom).coerceAtLeast(0)

                    val stableH = getStableTextTabHeight()
                    val toolbar = textEditingToolbar ?: return insets
                    val tabContainer = toolbar.findViewById<View>(R.id.textTabContentContainer) ?: return insets

                    if (currentTextEditingTab == TextEditingTab.KEYBOARD) {
                        val remainingH = (stableH - currentIme).coerceAtLeast(0)
                        tabContainer.layoutParams.height = remainingH
                        tabContainer.visibility = if (remainingH > 0) View.VISIBLE else View.GONE
                        tabContainer.requestLayout()
                    }
                    return insets
                }

                override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    super.onEnd(animation)
                    if (!isTextEditingActive) return
                    if (animation.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime() != 0) {
                        val toolbar = textEditingToolbar ?: return
                        val tabContainer = toolbar.findViewById<View>(R.id.textTabContentContainer) ?: return
                        if (currentTextEditingTab == TextEditingTab.KEYBOARD) {
                            tabContainer.visibility = View.GONE
                            tabContainer.layoutParams.height = 0
                        } else {
                            val targetH = getStableTextTabHeight()
                            tabContainer.layoutParams.height = targetH
                            tabContainer.visibility = View.VISIBLE
                        }
                    }
                }
            }
        )

        contentRoot.viewTreeObserver.addOnGlobalLayoutListener {
            val currentH = contentRoot.height
            if (currentH > 0) {
                if (!isKeyboardCurrentlyOpen) {
                    normalContentRootHeight = currentH
                } else if (normalContentRootHeight > 0 && normalContentRootHeight > currentH) {
                    val measuredDiff = normalContentRootHeight - currentH
                    if (measuredDiff > (100 * resources.displayMetrics.density).toInt()) {
                        savedKeyboardHeight = measuredDiff
                    }
                }
            }
        }

        tvDuration = findViewById(R.id.tvDuration)
        sequenceTrackContainer = findViewById(R.id.sequenceTrackContainer)
        customVideoSeeker = findViewById(R.id.customVideoSeeker)
        timeRulerView = findViewById(R.id.timeRulerView)
        loadingScreen = findViewById(R.id.loadingScreen)
        loadingScreen.findViewById<ImageButton>(R.id.btnLoadingBack)?.setBounceClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        loadingScreen.visibility = View.GONE
        exportScreen = findViewById(R.id.exportScreen)
        exportScreen.findViewById<View>(R.id.btnCancelExport)?.setBounceClickListener {
            cancelExport()
        }
        previewLoadingOverlay = findViewById(R.id.previewLoadingOverlay)
        showCentralizedLoading("initial_loading")
        pipTrackContainer = findViewById(R.id.pipTrackContainer)
        audioTrackContainer = findViewById(R.id.audioTrackContainer)
        textTrackContainer = findViewById(R.id.textTrackContainer)
        stickerTrackContainer = findViewById(R.id.stickerTrackContainer)
        effectTrackContainer = findViewById(R.id.effectTrackContainer)
        mosaicTrackContainer = findViewById(R.id.mosaicTrackContainer)
        imageTrackContainer = pipTrackContainer

        btnPlayPause = findViewById(R.id.btnPlayPause)
        timelineHorizontalScroll = findViewById(R.id.timelineHorizontalScroll)
        scrollableTimelineContent = findViewById(R.id.scrollableTimelineContent)
        timelineContainer = findViewById(R.id.timelineContainer)
        timelineVerticalScroll = findViewById(R.id.timelineVerticalScroll)
        timelineVerticalScroll.clipChildren = false
        timelineVerticalScroll.clipToPadding = false
        btnTimelineAdd = findViewById(R.id.btnTimelineAdd)
        
        emptyProjectState = findViewById(R.id.emptyProjectState)
        emptyProjectState.setBounceClickListener {
            openFilePickerMain()
        }

        btnTimelineAdd.setBounceClickListener {
            openFilePickerMerge()
        }

        thumbnailPriorityScheduler = ThumbnailPriorityScheduler(this, lifecycleScope)
        ThermalProtectionManager.addThermalListener(thermalListener)

        adaptiveTimelineController = AdaptiveTimelineController(
            highVelocityEnterThreshold = 1000f,
            highVelocityExitThreshold = 600f,
            idleDebounceMs = 80L
        ).apply {
            addListener(object : AdaptiveTimelineListener {
                override fun onScrollStateChanged(oldState: TimelineScrollState, newState: TimelineScrollState, generation: Long) {
                    when (newState) {
                        TimelineScrollState.DRAGGING -> {
                            isUserScrollingTimeline = true
                            com.eclip.rup.leb.engine.thumbnails.ScalableThumbnailEngine.setScrollThrottling(true)
                            handleTimelineDragStart()
                            applyAdaptiveRenderMode(TimelineRenderMode.HIGH_VELOCITY)
                        }
                        TimelineScrollState.SETTLING -> {
                            applyAdaptiveRenderMode(TimelineRenderMode.HIGH_VELOCITY)
                        }
                        TimelineScrollState.IDLE -> {
                            isUserScrollingTimeline = false
                            com.eclip.rup.leb.engine.thumbnails.ScalableThumbnailEngine.setScrollThrottling(false)
                            applyAdaptiveRenderMode(TimelineRenderMode.HIGH_FIDELITY)
                        }
                    }
                }

                override fun onRenderModeChanged(newMode: TimelineRenderMode) {
                    applyAdaptiveRenderMode(newMode)
                }

                override fun onVelocityChanged(velocityPxPerSec: Float) {}

                override fun onScrollIdle(currentScrollX: Int, generation: Long) {
                    isUserScrollingTimeline = false
                    restoreVisibleViewportThumbnails(generation)
                    // Controlled single exact seek ONLY when user stopped manual scrolling
                    if (::player.isInitialized && !isProgrammaticScroll) {
                        val targetMs = (currentScrollX / pixelsPerMs).toLong()
                        pendingSeekPositionMs = targetMs
                        onTimelineDragFinished()
                    }
                }
            })
        }

        timelinePrefetchPipeline = TimelinePrefetchPipeline(lifecycleScope) { clipIndex ->
            val sequenceItems = getSequenceItems()
            val item = sequenceItems.getOrNull(clipIndex) ?: return@TimelinePrefetchPipeline
            val isPhoto = item.isImage || isImageUri(item.uri)
            val uriKey = item.uri.toString()
            if (isPhoto) {
                if (ThumbnailCacheManager.getMemoryThumbnail(uriKey) == null) {
                    ThumbnailCacheManager.getThumbnailAsync(this@VideoEditingActivity, uriKey)
                }
                return@TimelinePrefetchPipeline
            }
            // Video clip: warm the exact time-buckets that the visible ClipThumbStripView will draw,
            // derived from the zero-layout precomputed geometry so the bucket keys match decode-for-decode.
            if (!::timelineHorizontalScroll.isInitialized) return@TimelinePrefetchPipeline
            val metadata = precomputedTimelineMetadata
            if (metadata.size != sequenceItems.size || clipIndex >= metadata.size) return@TimelinePrefetchPipeline
            if (metadata.rights[clipIndex] < 0f) return@TimelinePrefetchPipeline
            if (pixelsPerMs <= 0f) return@TimelinePrefetchPipeline

            // Snapshot UI-derived values on the main thread (this action runs on a worker pool)
            val (scrollX, viewportW) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!::timelineHorizontalScroll.isInitialized) {
                    Pair(0, 0)
                } else {
                    Pair(
                        timelineHorizontalScroll.scrollX,
                        timelineHorizontalScroll.width.takeIf { it > 0 } ?: 0
                    )
                }
            }
            if (viewportW <= 0) return@TimelinePrefetchPipeline
            val visLeft = maxOf(scrollX.toFloat(), metadata.lefts[clipIndex])
            val visRight = minOf(scrollX.toFloat() + viewportW, metadata.rights[clipIndex])
            if (visRight <= visLeft) return@TimelinePrefetchPipeline

            val stretchedDurMs = (item.durationMs / item.speed).toLong()
            if (stretchedDurMs <= 0L) return@TimelinePrefetchPipeline
            val pxPerUs = pixelsPerMs / 1000f
            if (pxPerUs <= 0f) return@TimelinePrefetchPipeline

            // Mirrors ClipThumbStripView's internal bucket arithmetic for perfect cache-key parity.
            val thumbHeightPx = (48 * resources.displayMetrics.density).toInt().coerceAtLeast(32)
            val thumbWidthPx = (thumbHeightPx * 16) / 9
            val intervalUs = maxOf((thumbWidthPx / pxPerUs).toLong(), 200_000L)
            val stretchedTrimStartMs = (item.trimStartMs / item.speed).toLong()

            val firstBucket = ((visLeft - metadata.lefts[clipIndex]) / pxPerUs / intervalUs).toLong()
            val lastBucket = (((visRight - metadata.lefts[clipIndex] - 1).coerceAtLeast(0f)) / pxPerUs / intervalUs).toLong()

            var bucket = firstBucket
            var requested = 0
            while (bucket <= lastBucket && requested < 32) {
                val sourceUs = (stretchedTrimStartMs * 1000L) + (bucket * intervalUs * item.speed).toLong()
                // Decode size stays fixed (160x90) so keys match ClipThumbStripView's internals.
                ScalableThumbnailEngine.requestThumbnail(
                    this@VideoEditingActivity,
                    item.uri,
                    sourceUs,
                    intervalUs,
                    160,
                    90,
                    ThumbnailPriority.PREFETCH
                ) {}
                bucket++
                requested++
            }
        }

        timelineContainer.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val newWidth = right - left
            val oldWidth = oldRight - oldLeft
            if (newWidth > 0 && newWidth != oldWidth) {
                val halfWidth = newWidth / 2
                if (timelineHorizontalScroll.paddingLeft != halfWidth || timelineHorizontalScroll.paddingRight != halfWidth) {
                    timelineHorizontalScroll.post {
                        timelineHorizontalScroll.setPadding(halfWidth, 0, halfWidth, 0)
                        updateTimelineAddButtonPosition()
                    }
                }
            }
        }

        timelineContainer.post {
            val halfWidth = timelineContainer.width / 2
            timelineHorizontalScroll.setPadding(halfWidth, 0, halfWidth, 0)
            updateTimelineAddButtonPosition()
        }

        timelineVerticalScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY) {
                pausePlaybackOnTimelineInteraction()
            }
            updateTimelineAddButtonPosition()
        }

        timelineHorizontalScroll.setOnScrollChangeListener { _, scrollX, _, oldScrollX, _ ->
            if (!isProgrammaticScroll) {
                pausePlaybackOnTimelineInteraction()
            }
            adaptiveTimelineController.onScrollChanged(scrollX, oldScrollX, isProgrammatic = isProgrammaticScroll)
            if (::timelinePrefetchPipeline.isInitialized) {
                val msPerPixelVal = if (pixelsPerMs > 0f) 1f / pixelsPerMs else 0.05f
                timelinePrefetchPipeline.onViewportScrolled(
                    scrollX,
                    timelineContainer.width.coerceAtLeast(1),
                    msPerPixelVal,
                    adaptiveTimelineController.currentVelocity,
                    videoSpatialIndex
                )
            }
            if (adaptiveTimelineController.currentMode == TimelineRenderMode.HIGH_FIDELITY && !isUserScrollingTimeline) {
                restoreVisibleViewportThumbnails()
            }
            // Strictly gate seeks so that no seek is dispatched while programmatic scrolling or active playback
            if (!isProgrammaticScroll && (!::player.isInitialized || !player.isPlaying)) {
                var targetMs = (scrollX / pixelsPerMs).toLong()

                if (isMagnetEnabled && isUserScrollingTimeline) {
                    val snapTargets = getSnapTargetsMs(targetMs)
                    val thresholdPx = 10f
                    val thresholdMs = (thresholdPx / pixelsPerMs).toLong()

                    var snapped = false
                    for (target in snapTargets) {
                        if (Math.abs(targetMs - target) <= thresholdMs) {
                            targetMs = target
                            snapped = true
                            if (lastSnappedTargetMs != target) {
                                timelineHorizontalScroll.performAppHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                lastSnappedTargetMs = target
                            }
                            val snappedScrollX = (targetMs * pixelsPerMs).toInt()
                            if (scrollX != snappedScrollX) {
                                try {
                                    isProgrammaticScroll = true
                                    timelineHorizontalScroll.scrollTo(snappedScrollX, 0)
                                } finally {
                                    isProgrammaticScroll = false
                                }
                                adaptiveTimelineController.onScrollChanged(snappedScrollX, scrollX, isProgrammatic = false)
                            }
                            break
                        }
                    }
                    if (!snapped) {
                        lastSnappedTargetMs = -1L
                    }
                } else {
                    lastSnappedTargetMs = -1L
                }

                // Decoupled Timeline Rendering:
                // 1. Immediately update UI time displays and overlay positions at 60 FPS
                val totalDuration = timelineCache?.totalDuration?.takeIf { it > 0L } ?: getTotalSequenceDuration()
                targetMs = targetMs.coerceIn(0L, if (totalDuration > 0L) totalDuration else Long.MAX_VALUE)
                updateDurationDisplay(targetMs.toInt(), totalDuration.toInt())
                textOverlayView?.currentPositionMs = targetMs
                imageOverlayView?.currentPositionMs = targetMs
                mosaicOverlayView?.currentPositionMs = targetMs

                // 2. Update pending seek position
                pendingSeekPositionMs = targetMs

                // 3. Playback Pipeline seek: Strictly gated by shouldSeekVideoPipeline()
                // During SETTLING (fling) or HIGH_VELOCITY, video seeking is completely suppressed
                if (adaptiveTimelineController.shouldSeekVideoPipeline()) {
                    onTimelineScrubbed(targetMs, isScrubbing = isUserScrollingTimeline)
                }
            }
            if (::timeRulerView.isInitialized) {
                timeRulerView.invalidate()
            }
            updateTimelineAddButtonPosition()
        }

        timelineHorizontalScroll.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            adaptiveTimelineController.onTouchEvent(event)
            if (scaleDetector.isInProgress || isTimelinePinching) {
                true
            } else {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        isUserScrollingTimeline = true
                        com.eclip.rup.leb.engine.thumbnails.ScalableThumbnailEngine.setScrollThrottling(true)
                        handleTimelineDragStart()
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        isUserScrollingTimeline = true
                        com.eclip.rup.leb.engine.thumbnails.ScalableThumbnailEngine.setScrollThrottling(true)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        isUserScrollingTimeline = false
                        com.eclip.rup.leb.engine.thumbnails.ScalableThumbnailEngine.setScrollThrottling(false)
                        val scrollX = timelineHorizontalScroll.scrollX
                        var targetMs = (scrollX / pixelsPerMs).toLong()
                        if (isMagnetEnabled) {
                            val snapTargets = getSnapTargetsMs(targetMs)
                            val thresholdPx = 10f
                            val thresholdMs = (thresholdPx / pixelsPerMs).toLong()
                            for (target in snapTargets) {
                                if (Math.abs(targetMs - target) <= thresholdMs) {
                                    targetMs = target
                                    val snappedScrollX = (target * pixelsPerMs).toInt()
                                    if (scrollX != snappedScrollX) {
                                        try {
                                            isProgrammaticScroll = true
                                            timelineHorizontalScroll.scrollTo(snappedScrollX, 0)
                                        } finally {
                                            isProgrammaticScroll = false
                                        }
                                        adaptiveTimelineController.onScrollChanged(snappedScrollX, scrollX, isProgrammatic = false)
                                    }
                                    break
                                }
                            }
                        }
                        pendingSeekPositionMs = targetMs
                        // If velocity is low (settled tap/slow release), dispatch exact seek.
                        // If velocity is high (fling), settling state machine will handle dispatch upon IDLE.
                        if (adaptiveTimelineController.scrollState != TimelineScrollState.SETTLING) {
                            onTimelineDragFinished()
                        }
                    }
                }
                false
            }
        }

        textOverlayView = try {
            findViewById<com.eclip.rup.leb.customviews.TextOverlayView>(R.id.textOverlayView)?.also { overlay ->
                overlay.onTextClicked = { op ->
                    viewModel.selectOperation(op.id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TextOverlayView not found in layout: ${e.message}")
            null
        }

        imageOverlayView = try {
            findViewById(R.id.imageOverlayView)
        } catch (e: Exception) {
            Log.w(TAG, "ImageOverlayView not found in layout: ${e.message}")
            null
        }

        draggableTextOverlay = try {
            findViewById<DraggableTextOverlayView>(R.id.draggableTextOverlay)?.also { overlay ->
                overlay.isSnappingEnabled = isMagnetEnabled
                overlay.onPositionChanged = { _, _ -> }
                overlay.onPositionCommitted = { relX, relY, fontSize, relW ->
                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                        if (op != null) {
                            viewModel.updateOperation(op.copy(relativeX = relX, relativeY = relY, fontSize = fontSize, relativeWidth = relW))
                        }
                    }
                }
                overlay.onTextCommitted = { text, fontSize, relX, relY, relW, color, fontPath, opacity, borderThickness, borderColor, textAlign, letterSpacing, lineSpacing, labelColor, shadowColor, labelOpacity, borderOpacity, shadowOpacity, shadowRadius, shadowDx, shadowDy, isBold, isItalic, isUnderline, caseMode ->
                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                        if (op != null) {
                            val updatedOp = op.copy(
                                text = text, fontSize = fontSize, relativeX = relX, relativeY = relY, relativeWidth = relW, color = color,
                                fontPath = fontPath, opacity = opacity, borderThickness = borderThickness, borderColor = borderColor,
                                textAlign = textAlign, letterSpacing = letterSpacing, lineSpacing = lineSpacing,
                                inAnimation = currentTextInAnimation,
                                outAnimation = currentTextOutAnimation,
                                animationDurationMs = currentTextAnimationDurationMs,
                                labelColor = labelColor,
                                shadowColor = shadowColor,
                                labelOpacity = labelOpacity,
                                borderOpacity = borderOpacity,
                                shadowOpacity = shadowOpacity,
                                shadowRadius = shadowRadius,
                                shadowDx = shadowDx,
                                shadowDy = shadowDy,
                                isBold = isBold,
                                isItalic = isItalic,
                                isUnderline = isUnderline,
                                caseMode = caseMode
                            )
                            viewModel.updateOperation(updatedOp)
                        }
                    } else {
                        val start = getGlobalPosition()
                        val end = minOf(start + 3000L, getTotalSequenceDuration())
                        val newId = System.nanoTime().toString()
                        val newOp = EditOperation.AddText(
                            text = text,
                            fontSize = fontSize,
                            position = TextPosition.CENTER,
                            relativeX = relX,
                            relativeY = relY,
                            relativeWidth = relW,
                            color = color,
                            startTimeMs = start,
                            endTimeMs = end,
                            id = newId,
                            fontPath = fontPath,
                            opacity = opacity,
                            borderThickness = borderThickness,
                            borderColor = borderColor,
                            textAlign = textAlign,
                            letterSpacing = letterSpacing,
                            lineSpacing = lineSpacing,
                            inAnimation = currentTextInAnimation,
                            outAnimation = currentTextOutAnimation,
                            animationDurationMs = currentTextAnimationDurationMs,
                            labelColor = labelColor,
                            shadowColor = shadowColor,
                            labelOpacity = labelOpacity,
                            borderOpacity = borderOpacity,
                            shadowOpacity = shadowOpacity,
                            shadowRadius = shadowRadius,
                            shadowDx = shadowDx,
                            shadowDy = shadowDy,
                            isBold = isBold,
                            isItalic = isItalic,
                            isUnderline = isUnderline,
                            caseMode = caseMode
                        )
                        viewModel.addOperation(newOp)
                    }
                    draggableTextOverlay?.deactivate()
                    viewModel.selectOperation(null)
                    exitTextEditingMode()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DraggableTextOverlayView not found: ${e.message}")
            null
        }

        textEditingToolbar = try {
            findViewById<View>(R.id.textEditingToolbar)?.also { toolbar ->
                val editTextInput = toolbar.findViewById<com.eclip.rup.leb.customviews.ImeBackEditText>(R.id.editTextInputText)
                val btnCancel = toolbar.findViewById<ImageButton>(R.id.btnTextCancel)
                val btnKeyboard = toolbar.findViewById<ImageButton>(R.id.btnTextKeyboardTab)
                val btnFont = toolbar.findViewById<ImageButton>(R.id.btnTextFontTab)
                val btnPalette = toolbar.findViewById<ImageButton>(R.id.btnTextPaletteTab)
                val btnAnimation = toolbar.findViewById<ImageButton>(R.id.btnTextAnimation)
                val btnFormat = toolbar.findViewById<ImageButton>(R.id.btnTextFormatTab)
                val btnDone = toolbar.findViewById<ImageButton>(R.id.btnTextDone)
                val formatContainer = toolbar.findViewById<View>(R.id.formatSettingsContainer)

                editTextInput?.onImeBackPressedListener = {
                    if (isTextEditingActive) {
                        draggableTextOverlay?.deactivate()
                        viewModel.selectOperation(null)
                        exitTextEditingMode()
                    }
                }

                // 2-Way Real-time sync: toolbar EditText -> on-canvas DraggableTextOverlayView
                editTextInput?.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val newText = s?.toString() ?: ""
                        draggableTextOverlay?.setTextContent(newText)
                        updateTextToolbarButtonsState(newText.trim().isNotEmpty())
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })

                editTextInput?.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(editTextInput.windowToken, 0)
                        editTextInput.clearFocus()
                        val textFromInput = editTextInput.text?.toString()?.trim() ?: ""
                        if (textFromInput.isNotEmpty()) {
                            draggableTextOverlay?.setTextContent(textFromInput)
                            draggableTextOverlay?.commitText()
                        } else {
                            draggableTextOverlay?.deactivate()
                            viewModel.selectOperation(null)
                            exitTextEditingMode()
                        }
                        true
                    } else {
                        false
                    }
                }

                btnCancel?.setBounceClickListener {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(editTextInput?.windowToken ?: toolbar.windowToken, 0)
                    editTextInput?.clearFocus()
                    draggableTextOverlay?.deactivate()
                    viewModel.selectOperation(null)
                    exitTextEditingMode()
                }

                btnDone?.setBounceClickListener {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(editTextInput?.windowToken ?: toolbar.windowToken, 0)
                    editTextInput?.clearFocus()
                    val textFromInput = editTextInput?.text?.toString()?.trim() ?: ""
                    if (textFromInput.isNotEmpty()) {
                        draggableTextOverlay?.setTextContent(textFromInput)
                        draggableTextOverlay?.commitText()
                    } else {
                        draggableTextOverlay?.deactivate()
                        viewModel.selectOperation(null)
                        exitTextEditingMode()
                    }
                }

                btnKeyboard?.setBounceClickListener {
                    switchTextEditingTab(TextEditingTab.KEYBOARD)
                }

                btnFont?.setBounceClickListener {
                    switchTextEditingTab(TextEditingTab.FONT)
                }

                btnPalette?.setBounceClickListener {
                    switchTextEditingTab(TextEditingTab.COLOR)
                }

                btnAnimation?.setBounceClickListener {
                    switchTextEditingTab(TextEditingTab.ANIMATION)
                }

                btnFormat?.setBounceClickListener {
                    switchTextEditingTab(TextEditingTab.ADJUSTMENT)
                }

                draggableTextOverlay?.onEditingFocused = {
                    if (isTextEditingActive) {
                        switchTextEditingTab(TextEditingTab.KEYBOARD)
                    }
                }

                draggableTextOverlay?.onTextChanged = { text ->
                    if (editTextInput?.text?.toString() != text) {
                        editTextInput?.setText(text)
                        editTextInput?.setSelection(text.length)
                    }
                    updateTextToolbarButtonsState(text.trim().isNotEmpty())
                }

                draggableTextOverlay?.onImeBackPressed = {
                    if (isTextEditingActive) {
                        draggableTextOverlay?.deactivate()
                        viewModel.selectOperation(null)
                        exitTextEditingMode()
                    }
                }

                draggableTextOverlay?.onDeleteRequested = {
                    deleteSelectedTextLayer()
                }

                // Format UI listeners
                setupAdjustmentPanel(formatContainer)

                val seekOpacity = formatContainer?.findViewById<Slider>(R.id.seekOpacity)
                seekOpacity?.addOnChangeListener { _, value, _ ->
                    selectedTextOpacity = value / 100f
                    draggableTextOverlay?.setOpacity(selectedTextOpacity)
                }

                val seekBorderWidth = formatContainer?.findViewById<Slider>(R.id.seekBorderWidth)
                seekBorderWidth?.value = selectedBorderThickness.toFloat().coerceIn(0f, 100f)
                seekBorderWidth?.addOnChangeListener { _, value, _ ->
                    val thickness = value.toInt()
                    selectedBorderThickness = thickness
                    if (selectedBorderColor == "NONE" || selectedBorderColor.isEmpty()) {
                        selectedBorderColor = "#000000"
                        draggableTextOverlay?.setBorderColor(selectedBorderColor)
                    }
                    draggableTextOverlay?.setBorderThickness(thickness)

                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                        if (op != null) {
                            val updatedOp = op.copy(
                                borderThickness = thickness,
                                borderColor = selectedBorderColor,
                                borderOpacity = selectedBorderOpacity
                            )
                            viewModel.updateOperation(updatedOp)
                        }
                    }
                }

                val seekLetterSpacing = formatContainer?.findViewById<Slider>(R.id.seekLetterSpacing)
                seekLetterSpacing?.addOnChangeListener { _, value, _ ->
                    draggableTextOverlay?.setLetterSpacing((value - 50f) / 100f)
                }

                val seekLineSpacing = formatContainer?.findViewById<Slider>(R.id.seekLineSpacing)
                seekLineSpacing?.addOnChangeListener { _, value, _ ->
                    draggableTextOverlay?.setLineSpacing((value - 50f) * 2f)
                }

                val btnAlignL = formatContainer?.findViewById<android.widget.Button>(R.id.btnAlignLeft)
                val btnAlignC = formatContainer?.findViewById<android.widget.Button>(R.id.btnAlignCenter)
                val btnAlignR = formatContainer?.findViewById<android.widget.Button>(R.id.btnAlignRight)

                btnAlignL?.setOnClickListener {
                    draggableTextOverlay?.setTextAlign("left")
                    btnAlignL.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPrimary))
                    btnAlignC?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                    btnAlignR?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                }
                btnAlignC?.setOnClickListener {
                    draggableTextOverlay?.setTextAlign("center")
                    btnAlignL?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                    btnAlignC.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPrimary))
                    btnAlignR?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                }
                btnAlignR?.setOnClickListener {
                    draggableTextOverlay?.setTextAlign("right")
                    btnAlignL?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                    btnAlignC?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                    btnAlignR.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPrimary))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Text editing toolbar not found: ${e.message}")
            null
        }

        textLayerToolbar = try {
            findViewById<View>(R.id.textLayerToolbar)?.also { toolbar ->
                toolbar.findViewById<View>(R.id.btnTextLayerAdd)?.setBounceClickListener {
                    draggableTextOverlay?.commitText()
                    viewModel.selectOperation(null)
                    enterTextEditingMode(isReEditing = false)
                }
                toolbar.findViewById<View>(R.id.btnTextLayerEdit)?.setBounceClickListener {
                    enterTextEditingMode(isReEditing = true, initialTab = TextEditingTab.KEYBOARD)
                }
                toolbar.findViewById<View>(R.id.btnTextLayerFont)?.setBounceClickListener {
                    enterTextEditingMode(isReEditing = true, initialTab = TextEditingTab.FONT)
                }
                toolbar.findViewById<View>(R.id.btnTextLayerColor)?.setBounceClickListener {
                    enterTextEditingMode(isReEditing = true, initialTab = TextEditingTab.COLOR)
                }
                toolbar.findViewById<View>(R.id.btnTextLayerAnimation)?.setBounceClickListener {
                    enterTextEditingMode(isReEditing = true, initialTab = TextEditingTab.ANIMATION)
                }
                toolbar.findViewById<View>(R.id.btnTextLayerSplit)?.setBounceClickListener {
                    splitSelectedTextLayer()
                }
                toolbar.findViewById<View>(R.id.btnTextLayerDelete)?.setBounceClickListener {
                    deleteSelectedTextLayer()
                }
                toolbar.findViewById<View>(R.id.btnTextLayerAdjust)?.setBounceClickListener {
                    enterTextEditingMode(isReEditing = true, initialTab = TextEditingTab.ADJUSTMENT)
                }
                toolbar.findViewById<View>(R.id.btnTextLayerCopy)?.setBounceClickListener {
                    duplicateSelectedTextLayer()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Text layer toolbar not found: ${e.message}")
            null
        }

        draggableImageOverlay = try {
            findViewById<DraggableImageOverlayView>(R.id.draggableImageOverlay)?.also { overlay ->
                overlay.isSnappingEnabled = isMagnetEnabled
                overlay.onPositionChanged = { _, _ -> }
                overlay.onImageCommitted = { uri, relX, relY, relW, relH, rotationAngle, opacity, isMirrored, maskConfig ->
                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
                        if (op != null) {
                            viewModel.updateOperation(op.copy(
                                imageUri = uri, relativeX = relX, relativeY = relY, relativeWidth = relW, relativeHeight = relH, rotationAngle = rotationAngle, opacity = opacity, isMirrored = isMirrored, maskConfig = maskConfig
                            ))
                        }
                    } else {
                        val start = getGlobalPosition()
                        val fileDuration = getOverlayFileDurationMs(uri)
                        val duration = fileDuration ?: 3000L
                        val end = minOf(start + duration, getTotalSequenceDuration())
                        val chromaColor = draggableImageOverlay?.currentChromaColor
                        val chromaSim = draggableImageOverlay?.currentChromaSimilarity ?: 0.1f
                        viewModel.addImageOverlayOperation(
                            imageUri = uri,
                            relativeX = relX,
                            relativeY = relY,
                            relativeWidth = relW,
                            relativeHeight = relH,
                            rotationAngle = rotationAngle,
                            startTimeMs = start,
                            endTimeMs = end,
                            fileDurationMs = fileDuration,
                            chromaKeyColor = chromaColor,
                            chromaKeySimilarity = chromaSim,
                            opacity = opacity,
                            isMirrored = isMirrored,
                            maskConfig = maskConfig
                        )
                    }
                    viewModel.selectOperation(null)
                    exitImageEditingMode()
                }
                overlay.onMaskChanged = { newMaskConfig ->
                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
                        if (op != null) {
                            viewModel.updateOperation(op.copy(maskConfig = newMaskConfig))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DraggableImageOverlayView not found: ${e.message}")
            null
        }
        mainVideoMaskContainer = findViewById(R.id.mainVideoMaskContainer)
        videoMaskOverlayView = try {
            findViewById<com.eclip.rup.leb.customviews.VideoMaskOverlayView>(R.id.videoMaskOverlayView)?.also { overlay ->
                overlay.onMaskChanged = { maskConfig ->
                    selectedVideoIndex?.let { index ->
                        viewModel.updateMergeItemMask(index, maskConfig)
                        mainVideoMaskContainer?.maskConfig = maskConfig
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "VideoMaskOverlayView not found: ${e.message}")
            null
        }

        cropOverlayView = try {
            findViewById<com.eclip.rup.leb.customviews.CropOverlayView>(R.id.cropOverlayView)?.also { overlay ->
                overlay.onCropBoundsChanged = { x, y, w, h ->
                    val existing = viewModel.project.value?.operations
                        ?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Crop>()
                        ?.lastOrNull()
                    if (existing != null) {
                        viewModel.updateOperation(existing.copy(
                            aspectRatio = "Custom",
                            xFraction = x,
                            yFraction = y,
                            wFraction = w,
                            hFraction = h
                        ))
                    } else {
                        viewModel.addCropOperation("Custom", x, y, w, h)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "CropOverlayView not found: ${e.message}")
            null
        }

        imageEditingToolbar = try {
            findViewById<View>(R.id.imageEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnImageCancel)?.setBounceClickListener {
                    draggableImageOverlay?.deactivate()
                    viewModel.selectOperation(null)
                    exitImageEditingMode()
                }
                toolbar.findViewById<View>(R.id.btnImageDone)?.setBounceClickListener {
                    draggableImageOverlay?.commitImage()
                }
                toolbar.findViewById<View>(R.id.btnImageDelete)?.setBounceClickListener {
                    MaterialAlertDialogBuilder(this@VideoEditingActivity)
                        .setTitle("Delete Image")
                        .setMessage("Are you sure you want to delete this image overlay?")
                        .setPositiveButton("Delete") { _, _ ->
                            draggableImageOverlay?.deactivate()
                            viewModel.selectedOperationId.value?.let { id ->
                                viewModel.removeOperation(id)
                            }
                            exitImageEditingMode()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                toolbar.findViewById<View>(R.id.btnImageDuplicate)?.setBounceClickListener {
                    duplicateSelectedOverlay()
                }
                toolbar.findViewById<View>(R.id.btnImageReplace)?.setBounceClickListener {
                    replaceSelectedOverlay()
                }
                toolbar.findViewById<View>(R.id.btnImageCrop)?.setBounceClickListener {
                    showMaskBottomSheet()
                }
                toolbar.findViewById<View>(R.id.btnImageAnimation)?.setBounceClickListener {
                    val selectedId = viewModel.selectedOperationId.value
                    val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
                    if (op != null) {
                        showAnimationPickerDialog(op)
                    }
                }
                toolbar.findViewById<View>(R.id.btnImageMask)?.setBounceClickListener {
                    showMaskBottomSheet()
                }
                toolbar.findViewById<View>(R.id.btnImageMirror)?.setBounceClickListener {
                    draggableImageOverlay?.toggleMirror()
                }
                toolbar.findViewById<View>(R.id.btnImageChromaKey)?.setBounceClickListener {
                    showChromaKeyDialog()
                }
                toolbar.findViewById<View>(R.id.btnImageLoop)?.setBounceClickListener {
                    val selectedId = viewModel.selectedOperationId.value
                    val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
                    if (op != null) {
                        val newIsLooping = !op.isLooping
                        val actualDuration = op.fileDurationMs ?: 3000L
                        val newEndTimeMs = if (!newIsLooping) {
                            Math.min(op.endTimeMs ?: Long.MAX_VALUE, (op.startTimeMs ?: 0L) + actualDuration)
                        } else {
                            op.endTimeMs
                        }
                        viewModel.updateOperation(op.copy(isLooping = newIsLooping, endTimeMs = newEndTimeMs))
                        
                        val color = if (newIsLooping) androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.colorPrimary) else androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.toolTextInactive)
                        toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageLoop)?.setColorFilter(color)
                        toolbar.findViewById<android.widget.TextView>(R.id.tvImageLoop)?.setTextColor(color)
                    }
                }

                val slider = toolbar.findViewById<Slider>(R.id.imageRotationSlider)
                val tvValue = toolbar.findViewById<TextView>(R.id.tvImageRotationValue)
                slider?.addOnChangeListener { _, value, _ ->
                    tvValue?.text = "${value.toInt()}°"
                    draggableImageOverlay?.setRotationAngle(value)
                }

                toolbar.findViewById<View>(R.id.btnImageRotate)?.setBounceClickListener {
                    val opacityRow = toolbar.findViewById<View>(R.id.imageOpacitySliderRow)
                    val rotationRow = toolbar.findViewById<View>(R.id.imageRotationSliderRow)
                    if (rotationRow?.visibility == View.VISIBLE) {
                        rotationRow.visibility = View.GONE
                        toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageRotate)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.toolTextInactive))
                    } else {
                        rotationRow?.visibility = View.VISIBLE
                        opacityRow?.visibility = View.GONE
                        toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageRotate)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.colorPrimary))
                        toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.toolTextInactive))
                    }
                }

                toolbar.findViewById<View>(R.id.btnImageOpacity)?.setBounceClickListener {
                    val opacityRow = toolbar.findViewById<View>(R.id.imageOpacitySliderRow)
                    val rotationRow = toolbar.findViewById<View>(R.id.imageRotationSliderRow)
                    if (opacityRow?.visibility == View.VISIBLE) {
                        opacityRow.visibility = View.GONE
                        toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.toolTextInactive))
                    } else {
                        opacityRow?.visibility = View.VISIBLE
                        rotationRow?.visibility = View.GONE
                        toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.colorPrimary))
                        toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageRotate)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.toolTextInactive))
                    }
                }

                val opacitySlider = toolbar.findViewById<Slider>(R.id.imageOpacitySlider)
                val tvOpacityValue = toolbar.findViewById<TextView>(R.id.tvImageOpacityValue)
                opacitySlider?.addOnChangeListener { _, value, _ ->
                    tvOpacityValue?.text = "${value.toInt()}%"
                    draggableImageOverlay?.setOpacity(value / 100f)
                }

            }
        } catch (e: Exception) {
            Log.w(TAG, "Image editing toolbar not found: ${e.message}")
            null
        }

        videoEditingToolbar = try {
            findViewById<View>(R.id.videoEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnVideoCancel)?.setBounceClickListener {
                    selectedVideoIndex = null
                    viewModel.project.value?.let { renderTracks(it) }
                    exitVideoEditingMode()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoSplit)?.setBounceClickListener {
                    splitSelectedVideo()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoTrim)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            showVideoSegmentTrimDialog(index, items[index])
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoDelete)?.setBounceClickListener {
                    MaterialAlertDialogBuilder(this@VideoEditingActivity)
                        .setTitle("Delete Clip")
                        .setMessage("Are you sure you want to delete this clip from the project?")
                        .setPositiveButton("Delete") { _, _ ->
                            deleteSelectedVideo()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoExtractAudio)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            extractAudioFromSegment(index, items[index])
                        }
                    }
                }

                toolbar.findViewById<ImageButton>(R.id.btnVideoFreezeFrame)?.setBounceClickListener {
                    freezeFrameAtCurrentPosition()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoSpeed)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            showSpeedEditingToolbar(index, items[index])
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoRotate)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            val currentRotation = items[index].rotation
                            val nextRotation = (currentRotation + 90f) % 360f
                            viewModel.updateMergeItemRotation(index, nextRotation)
                            viewModel.project.value?.let { renderTracks(it) }
                            Toast.makeText(this@VideoEditingActivity, "Rotated to ${nextRotation.toInt()}°", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoReplace)?.setBounceClickListener {
                    replaceSelectedClip()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoOpacity)?.setBounceClickListener {
                    val opacityRow = toolbar.findViewById<View>(R.id.videoOpacitySliderRow)
                    if (opacityRow?.visibility == View.VISIBLE) {
                        opacityRow.visibility = View.GONE
                        toolbar.findViewById<ImageButton>(R.id.btnVideoOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.toolTextInactive))
                    } else {
                        opacityRow?.visibility = View.VISIBLE
                        toolbar.findViewById<ImageButton>(R.id.btnVideoOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.colorPrimary))
                        
                        selectedVideoIndex?.let { index ->
                            val items = getSequenceItems()
                            if (index >= 0 && index < items.size) {
                                val currentOpacity = items[index].opacity * 100f
                                toolbar.findViewById<Slider>(R.id.videoOpacitySlider)?.value = currentOpacity
                                toolbar.findViewById<TextView>(R.id.tvVideoOpacityValue)?.text = "${currentOpacity.toInt()}%"
                            }
                        }
                    }
                }

                val videoOpacitySlider = toolbar.findViewById<Slider>(R.id.videoOpacitySlider)
                val tvVideoOpacityValue = toolbar.findViewById<TextView>(R.id.tvVideoOpacityValue)
                videoOpacitySlider?.addOnChangeListener { _, value, _ ->
                    tvVideoOpacityValue?.text = "${value.toInt()}%"
                    selectedVideoIndex?.let { index ->
                        viewModel.updateMergeItemOpacity(index, value / 100f)
                    }
                }

                toolbar.findViewById<ImageButton>(R.id.btnVideoToPip)?.setBounceClickListener {
                    convertClipToPip()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoCopy)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        viewModel.duplicateSequenceSegment(index, originalMainVideoDurationMs)
                        viewModel.project.value?.let { renderTracks(it) }
                        Toast.makeText(this@VideoEditingActivity, "Clip duplicated", Toast.LENGTH_SHORT).show()
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoCrop)?.setBounceClickListener {
                    cropAction()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoChroma)?.setBounceClickListener {
                    showChromaKeyDialog()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoAnimation)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            showAnimationPickerDialog(index)
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoReverse)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            reverseVideoSegment(index, items[index])
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoMirror)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        if (index == 0) {
                            val isCurrentlyMirrored = viewModel.project.value?.operations?.any { it is EditOperation.MirrorMain && it.isMirrored } ?: false
                            viewModel.updateMainVideoMirror(!isCurrentlyMirrored)
                        } else {
                            viewModel.toggleMergeItemMirror(index - 1)
                        }
                        viewModel.project.value?.let { renderTracks(it) }
                        syncUiWithPlayer()
                    }
                }

                toolbar.findViewById<ImageButton>(R.id.btnVideoMute)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        viewModel.toggleMuteClip(index)
                        viewModel.project.value?.let { renderTracks(it) }
                        updateVideoMuteButtonState(toolbar, index)
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoFilter)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        showColorFilterSelectionDialog(index)
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoMask)?.setBounceClickListener {
                    showMaskBottomSheet()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoAdjust)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        showAdjustSelectionDialog(index)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video editing toolbar not found: ${e.message}")
            null
        }

        speedEditingToolbar = try {
            findViewById<View>(R.id.speedEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnCloseSpeedSheet)?.setBounceClickListener {
                    hideSpeedEditingToolbar()
                }
                toolbar.findViewById<View>(R.id.speedBtn05)?.setBounceClickListener { applySpeedToSegment(0.5f) }
                toolbar.findViewById<View>(R.id.speedBtn10)?.setBounceClickListener { applySpeedToSegment(1.0f) }
                toolbar.findViewById<View>(R.id.speedBtn15)?.setBounceClickListener { applySpeedToSegment(1.5f) }
                toolbar.findViewById<View>(R.id.speedBtn20)?.setBounceClickListener { applySpeedToSegment(2.0f) }

                val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.sliderCustomSpeed)
                val tvCustomSpeed = toolbar.findViewById<TextView>(R.id.tvCustomSpeedValue)
                
                slider?.addOnChangeListener { _, value, _ ->
                    val roundedValue = String.format(java.util.Locale.US, "%.1f", value).toFloat()
                    tvCustomSpeed?.text = "${roundedValue}x"
                }

                slider?.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
                    override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                        val roundedValue = String.format(java.util.Locale.US, "%.1f", slider.value).toFloat()
                        applySpeedToSegment(roundedValue)
                    }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Speed editing toolbar not found: ${e.message}")
            null
        }

        audioEditingToolbar = try {
            findViewById<View>(R.id.audioEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnAudioDone)?.setBounceClickListener {
                    exitAudioEditingMode()
                }
                toolbar.findViewById<ImageButton>(R.id.btnAudioCancel)?.setBounceClickListener {
                    exitAudioEditingMode()
                }
                toolbar.findViewById<ImageButton>(R.id.btnAudioDelete)?.setBounceClickListener {
                    MaterialAlertDialogBuilder(this@VideoEditingActivity)
                        .setTitle("Delete Audio")
                        .setMessage("Are you sure you want to delete this audio track?")
                        .setPositiveButton("Delete") { _, _ ->
                            viewModel.selectedOperationId.value?.let { id ->
                                val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddBackgroundAudio)?.id == id } as? EditOperation.AddBackgroundAudio
                                if (op != null) {
                                    activeWaveformJobs.remove(op.audioUri.toString())?.cancel()
                                    AudioWaveformExtractor.evictForVideoPath(op.audioUri.path ?: op.audioUri.toString())
                                    uriToFilePathCache[op.audioUri.toString()]?.let { cachedPath ->
                                        AudioWaveformExtractor.evictForVideoPath(cachedPath)
                                    }
                                    AppCachePolicyManager.deleteFileIfInCache(this@VideoEditingActivity, op.audioUri)
                                }
                                viewModel.deleteOperation(id)
                            }
                            exitAudioEditingMode()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                toolbar.findViewById<ImageButton>(R.id.btnAudioBeats)?.setBounceClickListener {
                    val id = viewModel.selectedOperationId.value ?: return@setBounceClickListener
                    val project = viewModel.project.value ?: return@setBounceClickListener
                    val op = project.operations.find { it is com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio ?: return@setBounceClickListener
                    
                    if (op.beats.isNotEmpty()) {
                        // Toggle off if already detected
                        viewModel.updateOperation(op.copy(beats = emptyList()))
                        android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_beats_cleared, android.widget.Toast.LENGTH_SHORT).show()
                        toolbar.findViewById<ImageButton>(R.id.btnAudioBeats)?.setColorFilter(getColor(R.color.toolTextInactive))
                        return@setBounceClickListener
                    }
                    
                    android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_analyzing_beats, android.widget.Toast.LENGTH_SHORT).show()
                    com.eclip.rup.leb.utils.AudioAnalyzer.detectBeats(this@VideoEditingActivity, op.audioUri) { beats ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@launch
                            if (beats.isNotEmpty()) {
                                viewModel.updateOperation(op.copy(beats = beats))
                                android.widget.Toast.makeText(this@VideoEditingActivity, "Found ${beats.size} beats!", android.widget.Toast.LENGTH_SHORT).show()
                                toolbar.findViewById<ImageButton>(R.id.btnAudioBeats)?.setColorFilter(getColor(R.color.colorPrimary))
                            } else {
                                android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_no_clear_beats_found, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnAudioDucking)?.setBounceClickListener {
                    val id = viewModel.selectedOperationId.value ?: return@setBounceClickListener
                    val project = viewModel.project.value ?: return@setBounceClickListener
                    val op = project.operations.find { it is com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio ?: return@setBounceClickListener

                    val newDucking = !op.ducking
                    viewModel.updateOperation(op.copy(ducking = newDucking))
                    
                    val color = if (newDucking) getColor(R.color.colorPrimary) else getColor(R.color.toolTextInactive)
                    toolbar.findViewById<ImageButton>(R.id.btnAudioDucking)?.setColorFilter(color)
                    val msg = if (newDucking) "Audio ducking enabled" else "Audio ducking disabled"
                    android.widget.Toast.makeText(this@VideoEditingActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                toolbar.findViewById<ImageButton>(R.id.btnAudioFade)?.setBounceClickListener {
                    val id = viewModel.selectedOperationId.value ?: return@setBounceClickListener
                    val project = viewModel.project.value ?: return@setBounceClickListener
                    val op = project.operations.find { it is com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio ?: return@setBounceClickListener
                    showFadeDialog(op)
                }
                val volumeSlider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.audioVolumeSlider)
                val tvVolumeValue = toolbar.findViewById<TextView>(R.id.tvAudioVolumeValue)
                volumeSlider?.addOnChangeListener { _, value, _ ->
                    tvVolumeValue?.text = "${(value * 100).toInt()}%"
                    viewModel.selectedOperationId.value?.let { id ->
                        val project = viewModel.project.value ?: return@addOnChangeListener
                        val op = project.operations.find { it is com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio
                        if (op != null) {
                            viewModel.updateOperation(op.copy(volume = value))
                        }
                    }
                }

                val trimTrack = toolbar.findViewById<com.eclip.rup.leb.customviews.TrackTrimView>(R.id.audioTrimTrack)
                val tvTrimValue = toolbar.findViewById<TextView>(R.id.tvAudioTrimValues)
                
                trimTrack?.onTrimChanged = { startMs, endMs, _ ->
                    tvTrimValue?.text = "${formatDuration(startMs.toInt())} - ${formatDuration(endMs.toInt())}"
                    viewModel.selectedOperationId.value?.let { id ->
                        val project = viewModel.project.value ?: return@let
                        val op = project.operations.find { it is com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio
                        if (op != null) {
                            viewModel.updateOperation(op.copy(
                                internalStartMs = startMs,
                                internalEndMs = endMs
                            ))
                        }
                    }
                }

                trimTrack?.onTrimAdjustingWithDelta = { startMs, endMs, _, _ ->
                    tvTrimValue?.text = "${formatDuration(startMs.toInt())} - ${formatDuration(endMs.toInt())}"
                }
                
                val btnAudioPreviewPlay = toolbar.findViewById<ImageButton>(R.id.btnAudioPreviewPlay)
                btnAudioPreviewPlay?.setBounceClickListener {
                    if (audioPreviewPlayer?.isPlaying == true) {
                        audioPreviewPlayer?.stop()
                        audioPreviewPlayer?.release()
                        audioPreviewPlayer = null
                        btnAudioPreviewPlay.setImageResource(R.drawable.ic_play_24)
                        return@setBounceClickListener
                    }

                    viewModel.selectedOperationId.value?.let { id ->
                        val project = viewModel.project.value ?: return@setBounceClickListener
                        val op = project.operations.find { it is com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio
                        if (op != null) {
                            try {
                                audioPreviewPlayer = android.media.MediaPlayer().apply {
                                    setDataSource(this@VideoEditingActivity, op.audioUri)
                                    prepare()
                                    seekTo(op.internalStartMs.toInt())
                                    start()
                                    btnAudioPreviewPlay.setImageResource(R.drawable.ic_pause_24)
                                    
                                    val durationToPlay = if (op.internalEndMs > 0 && op.internalEndMs > op.internalStartMs) {
                                        op.internalEndMs - op.internalStartMs
                                    } else {
                                        op.originalDurationMs - op.internalStartMs
                                    }
                                    
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        try {
                                            if (audioPreviewPlayer === this && isPlaying) {
                                                stop()
                                                release()
                                                if (audioPreviewPlayer === this) {
                                                    audioPreviewPlayer = null
                                                }
                                                btnAudioPreviewPlay.setImageResource(R.drawable.ic_play_24)
                                            }
                                        } catch (e: Exception) {
                                            // Player was already released or invalid state
                                        }
                                    }, durationToPlay)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to preview audio segment", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio editing toolbar not found: ${e.message}")
            null
        }

        cropEditingToolbar = try {
            findViewById<View>(R.id.cropEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnCloseSheet)?.setBounceClickListener {
                    val currentCrop = viewModel.project.value?.operations
                        ?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Crop>()
                        ?.lastOrNull()
                    if (currentCrop != null) {
                        val init = initialCropOperation
                        if (init == null) {
                            viewModel.removeOperation(currentCrop.id)
                        } else {
                            viewModel.addCropOperation(
                                aspectRatio = init.aspectRatio,
                                xFraction = init.xFraction,
                                yFraction = init.yFraction,
                                wFraction = init.wFraction,
                                hFraction = init.hFraction
                            )
                        }
                    }
                    exitCropEditingMode()
                }
                toolbar.findViewById<ImageButton>(R.id.btnCropDone)?.setBounceClickListener {
                    exitCropEditingMode()
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatio1)?.setBounceClickListener {
                    cropOverlayView?.visibility = View.GONE
                    val bounds = getPresetCropBounds("16:9")
                    viewModel.addCropOperation("16:9", bounds.left, bounds.top, bounds.width(), bounds.height())
                    updateCropUi("16:9")
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatio2)?.setBounceClickListener {
                    cropOverlayView?.visibility = View.GONE
                    val bounds = getPresetCropBounds("9:16")
                    viewModel.addCropOperation("9:16", bounds.left, bounds.top, bounds.width(), bounds.height())
                    updateCropUi("9:16")
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatio3)?.setBounceClickListener {
                    cropOverlayView?.visibility = View.GONE
                    val bounds = getPresetCropBounds("1:1")
                    viewModel.addCropOperation("1:1", bounds.left, bounds.top, bounds.width(), bounds.height())
                    updateCropUi("1:1")
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatioCustom)?.setBounceClickListener {
                    val existing = viewModel.project.value?.operations
                        ?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Crop>()
                        ?.lastOrNull()
                    
                    val x: Float
                    val y: Float
                    val w: Float
                    val h: Float
                    if (existing != null) {
                        x = existing.xFraction
                        y = existing.yFraction
                        w = existing.wFraction
                        h = existing.hFraction
                    } else {
                        val bounds = getPresetCropBounds("16:9")
                        x = bounds.left
                        y = bounds.top
                        w = bounds.width()
                        h = bounds.height()
                    }
                    
                    cropOverlayView?.setCropBounds(x, y, w, h)
                    cropOverlayView?.visibility = View.VISIBLE
                    viewModel.addCropOperation("Custom", x, y, w, h)
                    updateCropUi("Custom")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Crop editing toolbar not found: ${e.message}")
            null
        }




        backgroundEditingToolbar = try {
            findViewById<View>(R.id.backgroundEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<View>(R.id.btnBackFromBackground)?.setBounceClickListener {
                    exitCanvasBackgroundEditingMode()
                }
                toolbar.findViewById<View>(R.id.btnBackgroundDone)?.setBounceClickListener {
                    exitCanvasBackgroundEditingMode()
                }
                toolbar.findViewById<View>(R.id.btnBackgroundColor)?.setBounceClickListener {
                    showCustomColorPicker("#000000") { hexColor ->
                        viewModel.updateCanvasBackgroundOperation(
                            type = EditOperation.CanvasBackground.BackgroundType.COLOR,
                            colorHex = hexColor
                        )
                        updateCanvasBackgroundPreview()
                    }
                }
                toolbar.findViewById<View>(R.id.btnBackgroundBlur)?.setBounceClickListener {
                    viewModel.updateCanvasBackgroundOperation(
                        type = EditOperation.CanvasBackground.BackgroundType.BLUR,
                        blurRadius = 25
                    )
                    updateCanvasBackgroundPreview()
                }
                toolbar.findViewById<View>(R.id.btnBackgroundImage)?.setBounceClickListener {
                    pickBackgroundImage()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Background editing toolbar not found: ${e.message}")
            null
        }

        mosaicOverlayView = findViewById(R.id.mosaicOverlayView)
        mosaicEditingToolbar = try {
            findViewById<View>(R.id.mosaicEditingToolbar)?.also { toolbar ->
                val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.mosaicIntensitySlider)
                val tvValue = toolbar.findViewById<TextView>(R.id.tvMosaicIntensityValue)
                slider?.addOnChangeListener { _, value, _ ->
                    activeMosaicIntensity = value.toInt()
                    tvValue?.text = "$activeMosaicIntensity"
                    mosaicOverlayView?.intensity = activeMosaicIntensity
                    mosaicOverlayView?.invalidate()
                }

                val btnRect = toolbar.findViewById<ImageButton>(R.id.btnMosaicShapeRect)
                val btnCircle = toolbar.findViewById<ImageButton>(R.id.btnMosaicShapeCircle)

                btnRect?.setBounceClickListener {
                    activeMosaicShape = EditOperation.MaskShape.RECTANGLE
                    mosaicOverlayView?.shape = activeMosaicShape
                    mosaicOverlayView?.invalidate()
                    btnRect.setColorFilter(getColor(R.color.colorPrimary))
                    btnCircle?.setColorFilter(getColor(R.color.toolTextInactive))
                }

                btnCircle?.setBounceClickListener {
                    activeMosaicShape = EditOperation.MaskShape.ELLIPSE
                    mosaicOverlayView?.shape = activeMosaicShape
                    mosaicOverlayView?.invalidate()
                    btnCircle.setColorFilter(getColor(R.color.colorPrimary))
                    btnRect?.setColorFilter(getColor(R.color.toolTextInactive))
                }

                toolbar.findViewById<ImageButton>(R.id.btnMosaicCancel)?.setBounceClickListener {
                    viewModel.selectOperation(null)
                    exitMosaicEditingMode()
                }

                toolbar.findViewById<ImageButton>(R.id.btnMosaicDone)?.setBounceClickListener {
                    if (activeEditingMosaicOpId != null) {
                        val opId = activeEditingMosaicOpId
                        val currentOp = viewModel.project.value?.operations?.find {
                            (it as? EditOperation.MosaicOverlay)?.id == opId
                        } as? EditOperation.MosaicOverlay
                        if (currentOp != null) {
                            viewModel.updateOperation(currentOp.copy(
                                relativeX = mosaicOverlayView?.relativeX ?: currentOp.relativeX,
                                relativeY = mosaicOverlayView?.relativeY ?: currentOp.relativeY,
                                relativeWidth = mosaicOverlayView?.relativeWidth ?: currentOp.relativeWidth,
                                relativeHeight = mosaicOverlayView?.relativeHeight ?: currentOp.relativeHeight,
                                intensity = activeMosaicIntensity,
                                shape = activeMosaicShape
                            ))
                        }
                        exitMosaicEditingMode()
                        viewModel.selectOperation(null)
                        Toast.makeText(this@VideoEditingActivity, "Mosaic effect updated", Toast.LENGTH_SHORT).show()
                    } else {
                        val start = getGlobalPosition()
                        val totalDuration = getTotalSequenceDuration()
                        val duration = 4000L
                        val end = minOf(start + duration, totalDuration)
                        val newOp = EditOperation.MosaicOverlay(
                            relativeX = mosaicOverlayView?.relativeX ?: 0.5f,
                            relativeY = mosaicOverlayView?.relativeY ?: 0.5f,
                            relativeWidth = mosaicOverlayView?.relativeWidth ?: 0.35f,
                            relativeHeight = mosaicOverlayView?.relativeHeight ?: 0.35f,
                            intensity = activeMosaicIntensity,
                            shape = activeMosaicShape,
                            startTimeMs = start,
                            endTimeMs = end
                        )
                        viewModel.addMosaicOverlay(
                            relativeX = newOp.relativeX,
                            relativeY = newOp.relativeY,
                            relativeWidth = newOp.relativeWidth,
                            relativeHeight = newOp.relativeHeight,
                            intensity = newOp.intensity,
                            shape = newOp.shape,
                            startTimeMs = newOp.startTimeMs,
                            endTimeMs = newOp.endTimeMs
                        )
                        exitMosaicEditingMode()
                        viewModel.selectOperation(newOp.id)
                        Toast.makeText(this@VideoEditingActivity, "Mosaic effect added", Toast.LENGTH_SHORT).show()
                    }
                }

                toolbar.findViewById<ImageButton>(R.id.btnMosaicDelete)?.setBounceClickListener {
                    activeEditingMosaicOpId?.let { id ->
                        viewModel.removeOperation(id)
                    }
                    viewModel.selectOperation(null)
                    exitMosaicEditingMode()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mosaic editing toolbar not found: ${e.message}")
            null
        }



        findViewById<ImageButton>(R.id.btnHome)?.setBounceClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<ImageView>(R.id.btnClose)?.setBounceClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<ImageButton>(R.id.btnClose)?.setBounceClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        findViewById<View>(R.id.btnHelp)?.setBounceClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Eclip Video Editor")
                .setMessage("• Timeline: Drag horizontally to scrub through your video.\n• Tools: Edit, Ratio, Overlay, Effect, Audio, Stickers, Canvas, Draw, and Voice.\n• Playhead: Center white line indicates current playback position.\n• Export: Tap 'Done' to export your edited video.")
                .setPositiveButton("Got it", null)
                .show()
        }

        findViewById<View>(R.id.btnDoneExport)?.setBounceClickListener {
            showQualitySettingsDialog()
        }

        findViewById<ImageButton>(R.id.btnExportTop)?.setBounceClickListener {
            showQualitySettingsDialog()
        }

        findViewById<View>(R.id.btnPrevClip)?.setBounceClickListener {
            pausePlaybackOnTimelineInteraction()
            val items = getSequenceItems()
            if (items.isNotEmpty()) {
                val currentPos = getGlobalPosition()
                var targetPos = 0L
                var accumulated = 0L
                for (item in items) {
                    if (accumulated < currentPos - 200L) {
                        targetPos = accumulated
                    }
                    accumulated += item.trimmedDurationMs
                }
                seekToGlobalPosition(targetPos)
                val totalDuration = getTotalSequenceDuration()
                val scrollX = (targetPos * pixelsPerMs).toInt()
                timelineHorizontalScroll.smoothScrollTo(scrollX, 0)
                updateDurationDisplay(targetPos.toInt(), totalDuration.toInt())
            }
        }

        findViewById<View>(R.id.btnNextClip)?.setBounceClickListener {
            pausePlaybackOnTimelineInteraction()
            val items = getSequenceItems()
            if (items.isNotEmpty()) {
                val currentPos = getGlobalPosition()
                var targetPos = getTotalSequenceDuration()
                var accumulated = 0L
                for (item in items) {
                    accumulated += item.trimmedDurationMs
                    if (accumulated > currentPos + 200L) {
                        targetPos = accumulated
                        break
                    }
                }
                seekToGlobalPosition(targetPos)
                val totalDuration = getTotalSequenceDuration()
                val scrollX = (targetPos * pixelsPerMs).toInt()
                timelineHorizontalScroll.smoothScrollTo(scrollX, 0)
                updateDurationDisplay(targetPos.toInt(), totalDuration.toInt())
            }
        }



        findViewById<View>(R.id.btnJumpToStart)?.setBounceClickListener {
            pausePlaybackOnTimelineInteraction()
            val totalDuration = getTotalSequenceDuration()
            seekToGlobalPosition(0L)
            timelineHorizontalScroll.scrollTo(0, 0)
            updateDurationDisplay(0, totalDuration.toInt())
        }



        layoutSaveSplit = findViewById(R.id.layoutSaveSplit)
        btnSaveText = findViewById(R.id.btnSaveText)
        btnSaveDropdown = findViewById(R.id.btnSaveDropdown)
        editingControlsWrapper = findViewById(R.id.editingControlsWrapper)
        editingControlsScroll = findViewById(R.id.editingControlsScroll)

        tvPreviewBadge = findViewById(R.id.tvPreviewBadge)

        btnSaveText.setBounceClickListener {
            showQualitySettingsDialog()
        }

        btnSaveDropdown.setBounceClickListener {
            showQualitySettingsDialog()
        }

        // Bottom Tool Buttons
        findViewById<ImageButton>(R.id.btnTrimMain)?.setBounceClickListener {
            setActiveToolButton(R.id.btnTrimMain)
            val sequenceItems = getSequenceItems()
            if (sequenceItems.isNotEmpty()) {
                val currentPos = getGlobalPosition()
                var accumulated = 0L
                var activeIndex = 0
                for (i in sequenceItems.indices) {
                    val dur = sequenceItems[i].trimmedDurationMs
                    if (currentPos in accumulated until (accumulated + dur)) {
                        activeIndex = i
                        break
                    }
                    accumulated += dur
                }
                selectedVideoIndex = activeIndex
                enterVideoEditingMode()
                updateTrackSelectionState()
            }
        }

        findViewById<ImageButton>(R.id.btnAudio).setBounceClickListener {
            setActiveToolButton(R.id.btnAudio)
            audioAction()
        }

        findViewById<ImageButton>(R.id.btnText).setBounceClickListener {
            setActiveToolButton(R.id.btnText)
            textAction()
        }

        findViewById<ImageButton>(R.id.btnSticker)?.setBounceClickListener {
            setActiveToolButton(R.id.btnSticker)
            mediaOverlayAction()
        }

        findViewById<ImageButton>(R.id.btnEffect)?.setBounceClickListener {
            setActiveToolButton(R.id.btnEffect)
            showColorFilterSelectionDialog(selectedVideoIndex ?: 0)
        }

        findViewById<ImageButton>(R.id.btnMediaOverlay)?.setBounceClickListener {
            setActiveToolButton(R.id.btnMediaOverlay)
            mediaOverlayAction()
        }



        findViewById<ImageButton>(R.id.btnCanvasBackground)?.setBounceClickListener {
            setActiveToolButton(R.id.btnCanvasBackground)
            canvasBackgroundAction()
        }

        findViewById<ImageButton>(R.id.btnDraw).setBounceClickListener {
            setActiveToolButton(R.id.btnDraw)
            openHandwritingMode()
        }

        findViewById<ImageButton>(R.id.btnVoiceOver)?.setBounceClickListener {
            setActiveToolButton(R.id.btnVoiceOver)
            voiceOverAction()
        }



        findViewById<ImageButton>(R.id.btnCrop).setBounceClickListener {
            setActiveToolButton(R.id.btnCrop)
            cropAction()
        }

        findViewById<ImageButton>(R.id.btnSort)?.setBounceClickListener {
            setActiveToolButton(R.id.btnSort)
            showReorderClipsDialog()
        }

        findViewById<ImageButton>(R.id.btnMosaic)?.setBounceClickListener {
            setActiveToolButton(R.id.btnMosaic)
            openMosaicEditingMode()
        }

        try {
            btnUndo = findViewById(R.id.btnUndo)
            btnRedo = findViewById(R.id.btnRedo)
            btnPreview = findViewById(R.id.btnPreview)
            btnUndo.setBounceClickListener {
                pausePlaybackOnTimelineInteraction()
                if (isShowingPreview) dismissPreview()
                cancelActiveEditingModes()
                selectedVideoIndex = null
                viewModel.undo()
            }
            btnRedo.setBounceClickListener {
                pausePlaybackOnTimelineInteraction()
                if (isShowingPreview) dismissPreview()
                cancelActiveEditingModes()
                selectedVideoIndex = null
                viewModel.redo()
            }
            btnPreview.setBounceClickListener {
                if (isShowingPreview) {
                    dismissPreview()
                } else {
                    renderSegmentedPreview()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Undo/Redo/Preview buttons not found in layout (optional)")
        }

    }

    private fun updateUIInteractionState() {
        val uiState = viewModel.uiState.value
        val isBusy = uiState.isExporting || isRenderingPreview || isEditorLocked || (activeLoadingReasons.isNotEmpty())
        val isProjectEmpty = findViewById<View>(R.id.emptyProjectState)?.visibility == View.VISIBLE
        val hasMedia = getSequenceItems().isNotEmpty() || (viewModel.project.value?.sourceUri != null && !isProjectEmpty)
        
        val alpha = if (isBusy) 0.4f else 1.0f
        
        if (::editingControlsWrapper.isInitialized) {
            editingControlsWrapper.alpha = if (isProjectEmpty) 0.4f else alpha
            for (i in 0 until editingControlsWrapper.childCount) {
                val child = editingControlsWrapper.getChildAt(i)
                child?.isEnabled = !isBusy && !isProjectEmpty
                if (child is android.view.ViewGroup) {
                    for (j in 0 until child.childCount) {
                        child.getChildAt(j)?.isEnabled = !isBusy && !isProjectEmpty
                    }
                }
            }
        }
        
        val hasOps = viewModel.project.value?.hasOperations() == true
        if (::layoutSaveSplit.isInitialized && ::btnSaveText.isInitialized && ::btnSaveDropdown.isInitialized) {
            layoutSaveSplit.alpha = if (isBusy || isProjectEmpty) 0.4f else 1.0f
            btnSaveText.isEnabled = !isBusy && !isProjectEmpty
            btnSaveDropdown.isEnabled = !isBusy && !isProjectEmpty
        }

        val canExport = !isBusy && hasMedia && !isProjectEmpty
        findViewById<View>(R.id.btnExportTop)?.apply {
            isEnabled = canExport
            this.alpha = if (canExport) 1.0f else 0.4f
        }

        findViewById<View>(R.id.btnDoneExport)?.apply {
            isEnabled = canExport
            this.alpha = if (canExport) 1.0f else 0.5f
        }
        
        if (::btnPreview.isInitialized) {
            val hasPreviewableOps = viewModel.project.value?.operations?.any { 
                it is EditOperation.Crop || it is EditOperation.AddText
            } == true
            btnPreview.isEnabled = !isBusy && hasPreviewableOps && !isProjectEmpty
            btnPreview.alpha = if (isBusy) 0.4f else if (hasPreviewableOps && !isProjectEmpty) 1.0f else 0.5f
            if (isShowingPreview) {
                btnPreview.setColorFilter(getColor(R.color.colorPrimary))
            } else {
                btnPreview.setColorFilter(getColor(R.color.colorOnPrimary))
            }
        }
        
        customVideoSeeker.isEnabled = !isBusy && !isProjectEmpty
    }

    private fun setActiveToolButton(activeId: Int) {
        if (isShowingPreview) dismissPreview()
        val toolIds = listOf(
            R.id.btnTrimMain,
            R.id.btnAudio,
            R.id.btnText,
            R.id.btnSticker,
            R.id.btnEffect,
            R.id.btnMediaOverlay,
            R.id.btnCanvasBackground,
            R.id.btnDraw,
            R.id.btnVoiceOver,
            R.id.btnCrop,
            R.id.btnSort,
            R.id.btnMosaic
        )
        for (id in toolIds) {
            val btn = findViewById<ImageButton>(id) ?: continue
            btn.setBackgroundResource(if (id == activeId) R.drawable.tool_button_active else R.drawable.tool_button_inactive)
            val parentLayout = btn.parent as? android.widget.LinearLayout
            val textView = parentLayout?.getChildAt(1) as? TextView
            textView?.setTextColor(
                if (id == activeId) getColor(R.color.toolTextActive) else getColor(R.color.toolTextInactive)
            )
        }
    }

    private fun observeViewModelState() {
        lifecycleScope.launch {
            viewModel.clipStates.collect { _ ->
                checkEditorReadiness()
            }
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                if (uiState.isExporting) {
                    hideCentralizedLoading()
                    val progress = uiState.exportProgress
                    exportScreen.findViewById<TextView>(R.id.tvExportPercentage)?.text = "$progress%"
                    exportScreen.findViewById<android.widget.ProgressBar>(R.id.exportProgressBar)?.progress = progress
                } else {
                    val emptyStateVisible = findViewById<View>(R.id.emptyProjectState)?.visibility == View.VISIBLE
                    if (editorReadinessState != EditorReadinessState.READY && !emptyStateVisible && !isVideoLoaded) {
                        showLoading()
                    } else if (editorReadinessState == EditorReadinessState.READY || isVideoLoaded) {
                        hideLoading()
                    }
                }

                exportScreen.visibility = View.GONE

                if (::btnUndo.isInitialized && ::btnRedo.isInitialized) {
                    val isProjectEmpty = findViewById<View>(R.id.emptyProjectState)?.visibility == View.VISIBLE
                    btnUndo.isEnabled = uiState.canUndo && !isProjectEmpty
                    btnUndo.alpha = if (uiState.canUndo && !isProjectEmpty) 1.0f else 0.5f
                    btnRedo.isEnabled = uiState.canRedo && !isProjectEmpty
                    btnRedo.alpha = if (uiState.canRedo && !isProjectEmpty) 1.0f else 0.5f
                }

                uiState.errorMessage?.let { error ->
                    showError(error)
                    viewModel.clearError()
                }

                if (uiState.pendingOperationCount > 0) {
                    Log.d(TAG, "Pending operations: ${uiState.pendingOperationCount}")
                }
                updateUIInteractionState()
            }
        }

        lifecycleScope.launch {
            viewModel.selectedOperationId.collect { selectedId ->
                if (selectedId != null && isShowingPreview) dismissPreview()
                textOverlayView?.hiddenOperationId = selectedId
                imageOverlayView?.hiddenOperationId = selectedId
                mosaicOverlayView?.hiddenOperationId = selectedId
                
                if (selectedId != null) {


                    // Reset editing modes to prevent multiple active editing toolbars/states
                    draggableTextOverlay?.deactivate()
                    draggableImageOverlay?.deactivate()
                    exitTextEditingMode()
                    exitTextLayerMode()
                    exitImageEditingMode()
                    if (!isMosaicEditingActive || activeEditingMosaicOpId != selectedId) {
                        exitMosaicEditingMode()
                    }

                    val op = viewModel.project.value?.operations?.find {
                        when (it) {
                            is EditOperation.AddText -> it.id == selectedId
                            is EditOperation.AddImageOverlay -> it.id == selectedId
                            is EditOperation.AddBackgroundAudio -> it.id == selectedId
                            is EditOperation.MosaicOverlay -> it.id == selectedId
                            else -> false
                        }
                    }
                    when (op) {
                        is EditOperation.AddText -> {
                            draggableTextOverlay?.activateForEdit(op)
                            enterTextLayerMode(op)
                        }
                        is EditOperation.AddImageOverlay -> {
                            draggableImageOverlay?.activateForEdit(op)
                            enterImageEditingModeForEdit(op.rotationAngle)
                        }
                        is EditOperation.AddBackgroundAudio -> {
                            enterAudioEditingMode(op)
                        }
                        is EditOperation.MosaicOverlay -> {
                            openMosaicEditingMode(op)
                        }
                        else -> {}
                    }
                } else {
                    draggableTextOverlay?.deactivate()
                    draggableImageOverlay?.deactivate()
                    exitTextEditingMode()
                    exitTextLayerMode()
                    exitImageEditingMode()
                    exitAudioEditingMode()
                    exitMosaicEditingMode()
                }
                
                // Update selection highlights without full rebuild
                updateTrackSelectionState()
                updateLayerReorderButtons()
            }
        }

        lifecycleScope.launch {
            viewModel.project.collect { project ->
                if (project != null) {
                    Log.d(TAG, "Project updated with ${project.getOperationCount()} operations")
                    mosaicOverlayView?.mosaicOperations = project.operations.filterIsInstance<EditOperation.MosaicOverlay>()

                    if (project.sourceUri?.scheme == "file") {
                        val projectSourcePath = project.sourceUri?.path
                        if (projectSourcePath != null && (!::tempInputFile.isInitialized || tempInputFile.absolutePath != projectSourcePath)) {
                            tempInputFile = File(projectSourcePath)
                            videoFileName = tempInputFile.name
                            val dur = if (originalMainVideoDurationMs > 0L) {
                                originalMainVideoDurationMs
                            } else {
                                withContext(Dispatchers.IO) {
                                    try {
                                        val r = android.media.MediaMetadataRetriever()
                                        try {
                                            r.setDataSource(tempInputFile.absolutePath)
                                            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                        } finally {
                                            try {
                                                r.release()
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Error releasing retriever: ${e.message}")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error getting original duration: ${e.message}")
                                        0L
                                    }
                                }
                            }
                            if (dur > 0L) {
                                originalMainVideoDurationMs = dur
                            }
                        }
                    }

                    updateUIInteractionState()

                    textOverlayView?.let { overlay ->
                        val overlayOps = project.operations.filter { it is EditOperation.AddText }
                        overlay.setOverlayOperations(overlayOps)
                    }

                    imageOverlayView?.let { overlay ->
                        val overlayOps = project.operations.filterIsInstance<EditOperation.AddImageOverlay>()
                        overlay.setImageOperations(overlayOps)
                    }

                    val cropOps = project.operations.filterIsInstance<EditOperation.Crop>()
                    if (cropOps.isNotEmpty()) {
                        applyCropPreview(cropOps.last().aspectRatio)
                    } else {
                        resetCropPreview()
                    }
                    
                    updateCanvasBackgroundPreview()

                    // Diff-aware rendering: only re-render tracks that actually changed
                    val prevProject = lastRenderedProject
                    rebuildTimelineCache(project)

                    if (prevProject == null) {
                        // First render — full render required
                        renderTracks(project)
                    } else {
                        val changedTracks = detectChangedTracks(prevProject, project)
                        val zoomChanged = (lastRenderedPixelsPerMs != pixelsPerMs)
                        val totalSequenceDuration = timelineCache?.totalDuration ?: getTotalSequenceDuration()

                        if (changedTracks.isEmpty()) {
                            if (zoomChanged) {
                                updateTimelineZoomLayout(totalSequenceDuration)
                            } else {
                                updateTrackSelectionState()
                            }
                        } else if (changedTracks.contains(TrackCategory.SEQUENCE)) {
                            // Sequence changed — need full render (affects layout of all tracks)
                            renderTracks(project)
                        } else {
                            if (zoomChanged) {
                                updateTimelineZoomLayout(totalSequenceDuration)
                            }
                            // Selective re-render: only rebuild changed overlay tracks
                            if (changedTracks.contains(TrackCategory.PIP)) renderPipTracks(project, totalSequenceDuration)
                            if (changedTracks.contains(TrackCategory.AUDIO)) renderAudioTracks(project, totalSequenceDuration)
                            if (changedTracks.contains(TrackCategory.TEXT)) renderTextTracks(project, totalSequenceDuration)
                            if (changedTracks.contains(TrackCategory.EFFECT)) renderEffectTracks(project, totalSequenceDuration)
                            if (changedTracks.contains(TrackCategory.MOSAIC)) renderMosaicTracks(project, totalSequenceDuration)
                            if (changedTracks.contains(TrackCategory.EXO_SOURCE) && !changedTracks.contains(TrackCategory.SEQUENCE)) {
                                rebuildExoPlayerSource(project)
                            }
                            updateTrackSelectionState()
                        }
                    }
                    lastRenderedProject = project
                    lastRenderedPixelsPerMs = pixelsPerMs

                    updateLayerReorderButtons()
                }
            }
        }
    }

    private fun updateLayerReorderButtons() {
        if (!::btnMoveLayerUp.isInitialized || !::btnMoveLayerDown.isInitialized) return
        
        val selectedId = viewModel.selectedOperationId.value
        val project = viewModel.project.value
        
        if (selectedId == null || project == null) {
            btnMoveLayerUp.visibility = View.GONE
            btnMoveLayerDown.visibility = View.GONE
            return
        }
        
        val overlayOps = project.operations.filter {
            it is EditOperation.AddText || it is EditOperation.AddImageOverlay || it is EditOperation.MosaicOverlay
        }
        
        val selectedIndex = overlayOps.indexOfFirst { op ->
            when (op) {
                is EditOperation.AddText -> op.id == selectedId
                is EditOperation.AddImageOverlay -> op.id == selectedId
                is EditOperation.MosaicOverlay -> op.id == selectedId
                else -> false
            }
        }
        
        if (selectedIndex == -1) {
            btnMoveLayerUp.visibility = View.GONE
            btnMoveLayerDown.visibility = View.GONE
            return
        }
        
        val canMoveUp = selectedIndex < overlayOps.size - 1
        val canMoveDown = selectedIndex > 0
        
        btnMoveLayerUp.visibility = if (canMoveUp) View.VISIBLE else View.GONE
        btnMoveLayerDown.visibility = if (canMoveDown) View.VISIBLE else View.GONE
    }

    private fun applyCropPreview(aspectRatio: String) {
        val cropEditingActive = cropEditingToolbar?.visibility == View.VISIBLE

        if (cropEditingActive) {
            resetCropPreview()
            return
        }

        val canvasContainer = findViewById<FrameLayout>(R.id.canvasContainer) ?: return
        val cropOp = viewModel.project.value?.operations
            ?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Crop>()
            ?.lastOrNull()

        val bounds = when (aspectRatio) {
            "16:9", "9:16", "1:1" -> {
                if (cropOp != null && (cropOp.xFraction > 0f || cropOp.yFraction > 0f || cropOp.wFraction < 1f || cropOp.hFraction < 1f)) {
                    android.graphics.RectF(
                        cropOp.xFraction,
                        cropOp.yFraction,
                        cropOp.xFraction + cropOp.wFraction,
                        cropOp.yFraction + cropOp.hFraction
                    )
                } else {
                    getPresetCropBounds(aspectRatio)
                }
            }
            "Custom" -> {
                if (cropOp != null) {
                    android.graphics.RectF(
                        cropOp.xFraction,
                        cropOp.yFraction,
                        cropOp.xFraction + cropOp.wFraction,
                        cropOp.yFraction + cropOp.hFraction
                    )
                } else {
                    android.graphics.RectF(0f, 0f, 1f, 1f)
                }
            }
            else -> {
                resetCropPreview()
                return
            }
        }

        val xFrac = bounds.left.coerceIn(0f, 1f)
        val yFrac = bounds.top.coerceIn(0f, 1f)
        val wFrac = bounds.width().coerceIn(0.001f, 1f)
        val hFrac = bounds.height().coerceIn(0.001f, 1f)

        val activeParent = (canvasContainer.parent as? View) ?: playerContainer
        activeParent.post {
            val containerWidth = activeParent.width.toFloat()
            val containerHeight = activeParent.height.toFloat()
            if (containerWidth <= 0f || containerHeight <= 0f) return@post

            val effectiveHeight = if (savedPlayerContainerHeight > 0) {
                if (isTextEditingActive) {
                    savedPlayerContainerHeight.toFloat()
                } else if (containerHeight > savedPlayerContainerHeight + 40f) {
                    savedPlayerContainerHeight.toFloat()
                } else {
                    containerHeight
                }
            } else {
                containerHeight
            }

            val baseVideoRatio = if (primaryVideoAspectRatio > 0f) primaryVideoAspectRatio else {
                val format = if (::player.isInitialized) player.videoFormat else null
                if (format != null && format.width > 0 && format.height > 0) {
                    val rot = format.rotationDegrees
                    val vw = if (rot == 90 || rot == 270) format.height else format.width
                    val vh = if (rot == 90 || rot == 270) format.width else format.height
                    vw.toFloat() / vh.toFloat()
                } else 16f / 9f
            }

            val croppedAspectRatio = (baseVideoRatio * wFrac) / hFrac
            val containerRatio = containerWidth / effectiveHeight

            val canvasW: Float
            val canvasH: Float
            if (croppedAspectRatio > containerRatio) {
                canvasW = containerWidth
                canvasH = containerWidth / croppedAspectRatio
            } else {
                canvasW = effectiveHeight * croppedAspectRatio
                canvasH = effectiveHeight
            }

            // 1. Size canvasContainer to the cropped aspect ratio box
            val canvasLp = canvasContainer.layoutParams as FrameLayout.LayoutParams
            canvasLp.width = canvasW.toInt()
            canvasLp.height = canvasH.toInt()
            canvasLp.gravity = Gravity.CENTER
            canvasContainer.layoutParams = canvasLp

            // Enable clipping on canvasContainer and mainVideoMaskContainer
            canvasContainer.clipChildren = true
            canvasContainer.clipToPadding = true
            mainVideoMaskContainer?.clipChildren = true
            mainVideoMaskContainer?.clipToPadding = true

            // 2. Scale playerView so the crop box fills canvasContainer
            val fullWidth = canvasW / wFrac
            val fullHeight = canvasH / hFrac

            val playerLp = playerView.layoutParams as FrameLayout.LayoutParams
            playerLp.width = fullWidth.toInt()
            playerLp.height = fullHeight.toInt()
            playerLp.gravity = Gravity.TOP or Gravity.START
            playerView.layoutParams = playerLp
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL

            playerView.translationX = -fullWidth * xFrac
            playerView.translationY = -fullHeight * yFrac

            val hwCanvas = findViewById<com.eclip.rup.leb.customviews.HandwritingCanvasView>(R.id.handwritingCanvasView)
            val overlays = listOf(textOverlayView, draggableTextOverlay, imageOverlayView, draggableImageOverlay, cropOverlayView, videoMaskOverlayView, hwCanvas)
            for (overlay in overlays) {
                overlay?.let {
                    val overlayParams = it.layoutParams as FrameLayout.LayoutParams
                    overlayParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                    overlayParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                    overlayParams.gravity = Gravity.CENTER
                    it.layoutParams = overlayParams
                    it.translationX = 0f
                    it.translationY = 0f
                }
            }

            textOverlayView?.setVideoSize(canvasW.toInt(), canvasH.toInt())
            draggableTextOverlay?.setVideoSize(canvasW.toInt(), canvasH.toInt())
            imageOverlayView?.setVideoSize(canvasW.toInt(), canvasH.toInt())
            draggableImageOverlay?.setVideoSize(canvasW.toInt(), canvasH.toInt())
            cropOverlayView?.setVideoSize(canvasW.toInt(), canvasH.toInt())
        }
    }

    private fun resetCropPreview() {
        val canvasContainer = findViewById<FrameLayout>(R.id.canvasContainer) ?: return
        val activeParent = (canvasContainer.parent as? View) ?: playerContainer
        activeParent.post {
            val containerWidth = activeParent.width
            val containerHeight = activeParent.height
            if (containerWidth <= 0 || containerHeight <= 0) return@post

            val effectiveHeight = if (savedPlayerContainerHeight > 0) {
                if (isTextEditingActive) {
                    savedPlayerContainerHeight
                } else if (containerHeight > savedPlayerContainerHeight + 40) {
                    savedPlayerContainerHeight
                } else {
                    containerHeight
                }
            } else {
                containerHeight
            }

            val baseVideoRatio = if (primaryVideoAspectRatio > 0f) primaryVideoAspectRatio else 16f / 9f
            val containerRatio = containerWidth.toFloat() / effectiveHeight.toFloat()
            var finalWidth = containerWidth
            var finalHeight = effectiveHeight

            if (baseVideoRatio > containerRatio) {
                finalHeight = (containerWidth / baseVideoRatio).toInt()
            } else {
                finalWidth = (effectiveHeight * baseVideoRatio).toInt()
            }

            val canvasLp = canvasContainer.layoutParams as FrameLayout.LayoutParams
            canvasLp.width = finalWidth
            canvasLp.height = finalHeight
            canvasLp.gravity = Gravity.CENTER
            canvasContainer.layoutParams = canvasLp

            val playerLp = playerView.layoutParams as FrameLayout.LayoutParams
            playerLp.width = FrameLayout.LayoutParams.MATCH_PARENT
            playerLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            playerLp.gravity = Gravity.CENTER
            playerView.layoutParams = playerLp
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            playerView.translationX = 0f
            playerView.translationY = 0f

            val hwCanvas = findViewById<com.eclip.rup.leb.customviews.HandwritingCanvasView>(R.id.handwritingCanvasView)
            val overlays = listOf(textOverlayView, draggableTextOverlay, imageOverlayView, draggableImageOverlay, cropOverlayView, videoMaskOverlayView, hwCanvas)
            for (overlay in overlays) {
                overlay?.let {
                    val overlayParams = it.layoutParams as FrameLayout.LayoutParams
                    overlayParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                    overlayParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                    overlayParams.gravity = Gravity.CENTER
                    it.layoutParams = overlayParams
                    it.translationX = 0f
                    it.translationY = 0f
                }
            }

            textOverlayView?.setVideoSize(finalWidth, finalHeight)
            draggableTextOverlay?.setVideoSize(finalWidth, finalHeight)
            imageOverlayView?.setVideoSize(finalWidth, finalHeight)
            draggableImageOverlay?.setVideoSize(finalWidth, finalHeight)
            cropOverlayView?.setVideoSize(finalWidth, finalHeight)
        }
    }

    @SuppressLint("InflateParams")
    private fun trimAction() {
        Toast.makeText(this, R.string.toast_drag_the_handles_on_the_timeli, Toast.LENGTH_LONG).show()
    }

    private fun captureCurrentFrame() {
        closeActiveEditingModes()
        val container = findViewById<FrameLayout>(R.id.playerContainer)
        val tvDuration = findViewById<TextView>(R.id.tvDuration)
        val tvPreviewBadge = findViewById<TextView>(R.id.tvPreviewBadge)
        
        val durationVis = tvDuration?.visibility ?: View.GONE
        val badgeVis = tvPreviewBadge?.visibility ?: View.GONE
        
        tvDuration?.visibility = View.INVISIBLE
        tvPreviewBadge?.visibility = View.INVISIBLE
        
        container.post {
            if (container.width <= 0 || container.height <= 0) {
                tvDuration?.visibility = durationVis
                tvPreviewBadge?.visibility = badgeVis
                return@post
            }
            val bitmap = android.graphics.Bitmap.createBitmap(container.width, container.height, android.graphics.Bitmap.Config.ARGB_8888)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val location = IntArray(2)
                container.getLocationInWindow(location)
                val rect = android.graphics.Rect(location[0], location[1], location[0] + container.width, location[1] + container.height)
                
                try {
                    android.view.PixelCopy.request(window, rect, bitmap, { copyResult ->
                        tvDuration?.visibility = durationVis
                        tvPreviewBadge?.visibility = badgeVis
                        if (copyResult == android.view.PixelCopy.SUCCESS) {
                            saveBitmapToGallery(bitmap)
                        } else {
                            Toast.makeText(this, "Failed to capture frame", Toast.LENGTH_SHORT).show()
                        }
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                } catch (e: Exception) {
                    e.printStackTrace()
                    tvDuration?.visibility = durationVis
                    tvPreviewBadge?.visibility = badgeVis
                    Toast.makeText(this, "Failed to capture frame", Toast.LENGTH_SHORT).show()
                }
            } else {
                val canvas = android.graphics.Canvas(bitmap)
                val bg = container.background
                if (bg != null) bg.draw(canvas) else canvas.drawColor(android.graphics.Color.BLACK)
                
                val textureView = findTextureView(playerView)
                if (textureView != null) {
                    val videoBitmap = textureView.bitmap
                    if (videoBitmap != null) {
                        canvas.save()
                        canvas.translate(playerView.x + textureView.x, playerView.y + textureView.y)
                        canvas.scale(playerView.scaleX, playerView.scaleY)
                        canvas.drawBitmap(videoBitmap, 0f, 0f, null)
                        canvas.restore()
                    }
                }
                
                val overlays = listOf(R.id.textOverlayView, R.id.imageOverlayView, R.id.cropOverlayView)
                for (id in overlays) {
                    val view = container.findViewById<View>(id)
                    if (view != null && view.visibility == View.VISIBLE) {
                        canvas.save()
                        canvas.translate(view.x, view.y)
                        view.draw(canvas)
                        canvas.restore()
                    }
                }
                
                tvDuration?.visibility = durationVis
                tvPreviewBadge?.visibility = badgeVis
                saveBitmapToGallery(bitmap)
            }
        }
    }

    private fun findTextureView(viewGroup: android.view.ViewGroup): android.view.TextureView? {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is android.view.TextureView) return child
            if (child is android.view.ViewGroup) {
                val tv = findTextureView(child)
                if (tv != null) return tv
            }
        }
        return null
    }

    private fun saveBitmapToGallery(bitmap: android.graphics.Bitmap) {
        val filename = "Eclip_Frame_${System.currentTimeMillis()}.png"
        var fos: java.io.OutputStream? = null
        var imageUri: Uri? = null
        try {
            val sharedPreferences = getSharedPreferences("eclip_prefs", Context.MODE_PRIVATE)
            val customUriString = sharedPreferences.getString("export_snapshot_directory_uri", null)
            
            if (customUriString != null) {
                try {
                    val treeUri = Uri.parse(customUriString)
                    val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    imageUri = android.provider.DocumentsContract.createDocument(
                        contentResolver,
                        parentUri,
                        "image/png",
                        filename
                    )
                    fos = imageUri?.let { contentResolver.openOutputStream(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving snapshot to custom directory: ${e.message}, falling back to default", e)
                }
            }
            
            if (fos == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Eclip")
                    }
                    imageUri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    fos = imageUri?.let { resolver.openOutputStream(it) }
                } else {
                    val imagesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
                    val directory = java.io.File(imagesDir, "Eclip")
                    if (!directory.exists()) directory.mkdirs()
                    val image = java.io.File(directory, filename)
                    fos = java.io.FileOutputStream(image)
                    imageUri = Uri.fromFile(image)
                }
            }
            
            fos?.use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                Toast.makeText(this, "Frame saved to gallery", Toast.LENGTH_SHORT).show()
            }
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && imageUri != null) {
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, imageUri))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save frame", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("InflateParams")
    private fun cropAction() {
        enterCropEditingMode()
    }

    private fun canvasBackgroundAction() {
        enterCanvasBackgroundEditingMode()
    }

    private fun showMainToolbar() {
        if (::editingControlsScroll.isInitialized) {
            editingControlsScroll.visibility = View.VISIBLE
        }
        if (::editingControlsWrapper.isInitialized) {
            editingControlsWrapper.visibility = View.VISIBLE
        }
    }

    private fun hideMainToolbar() {
        if (::editingControlsScroll.isInitialized) {
            editingControlsScroll.visibility = View.GONE
        }
        if (::editingControlsWrapper.isInitialized) {
            editingControlsWrapper.visibility = View.GONE
        }
    }

    private fun enterCanvasBackgroundEditingMode() {
        closeActiveEditingModes()
        hideMainToolbar()
        backgroundEditingToolbar?.visibility = View.VISIBLE
    }

    private fun exitCanvasBackgroundEditingMode() {
        backgroundEditingToolbar?.visibility = View.GONE
        showMainToolbar()
        setActiveToolButton(-1)
        updateUIInteractionState()
    }

    private var isHandwritingActive = false

    private fun setupHandwritingControls() {
        val btnDraw = findViewById<ImageButton>(R.id.btnDraw)
        btnDraw?.setBounceClickListener {
            setActiveToolButton(R.id.btnDraw)
            openHandwritingMode()
        }

        val handwritingPanel = findViewById<View>(R.id.handwritingPanel) ?: return
        val canvasView = findViewById<HandwritingCanvasView>(R.id.handwritingCanvasView) ?: return
        val dotView = handwritingPanel.findViewById<BrushSizeDotView>(R.id.brushSizeDotView)
        val sliderBrushSize = handwritingPanel.findViewById<com.google.android.material.slider.Slider>(R.id.sliderBrushSize)
        val btnClose = handwritingPanel.findViewById<ImageButton>(R.id.btnCloseHandwriting)
        val btnUndo = handwritingPanel.findViewById<ImageButton>(R.id.btnUndoHandwriting)
        val btnRedo = handwritingPanel.findViewById<ImageButton>(R.id.btnRedoHandwriting)
        val btnClear = handwritingPanel.findViewById<ImageButton>(R.id.btnClearHandwriting)
        val btnDone = handwritingPanel.findViewById<ImageButton>(R.id.btnDoneHandwriting)
        val btnCustomColor = handwritingPanel.findViewById<View>(R.id.btnCustomColor)
        val layoutPalette = handwritingPanel.findViewById<LinearLayout>(R.id.layoutColorPalette)

        val presetHexes = listOf(
            "#FFFFFF", "#000000", "#FF007A", "#FF3B30",
            "#FF9500", "#FFCC00", "#34C759", "#32ADE6",
            "#007AFF", "#AF52DE"
        )

        var selectedHex = "#FF007A"

        fun updateColorSelection(hex: String) {
            selectedHex = hex
            val colorInt = try { Color.parseColor(hex) } catch (e: Exception) { Color.RED }
            canvasView.setBrushColor(colorInt)
            dotView?.setBrushColor(colorInt)

            if (layoutPalette != null) {
                for (i in 1 until layoutPalette.childCount) {
                    val child = layoutPalette.getChildAt(i) as? FrameLayout ?: continue
                    val tagHex = child.tag as? String
                    if (tagHex != null && tagHex.equals(hex, ignoreCase = true)) {
                        child.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                        child.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_transition_border_selected)
                    } else {
                        child.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                        child.foreground = null
                    }
                }
            }
        }

        if (layoutPalette != null && layoutPalette.childCount <= 1) {
            val density = resources.displayMetrics.density
            val swatchSize = (36 * density).toInt()
            val innerSize = (24 * density).toInt()
            val margin = (4 * density).toInt()

            for (hex in presetHexes) {
                val frame = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                        setMargins(margin, 0, margin, 0)
                    }
                    tag = hex
                    setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                    isClickable = true
                    isFocusable = true
                }

                val circleView = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER)
                    val drawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(try { Color.parseColor(hex) } catch (e: Exception) { Color.WHITE })
                        setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
                    }
                    background = drawable
                }
                frame.addView(circleView)

                frame.setOnClickListener {
                    updateColorSelection(hex)
                }
                layoutPalette.addView(frame)
            }
        }

        val density = resources.displayMetrics.density
        val minPx = 3f * density
        val maxPx = 60f * density

        sliderBrushSize?.addOnChangeListener { _, value, _ ->
            val fraction = value / 100f
            val sizePx = minPx + fraction * (maxPx - minPx)
            canvasView.setBrushSizePx(sizePx)
            dotView?.setBrushSize(sizePx, minPx, maxPx)
        }

        val initialValue = sliderBrushSize?.value ?: 25f
        val initialSizePx = minPx + (initialValue / 100f) * (maxPx - minPx)
        canvasView.setBrushSizePx(initialSizePx)
        dotView?.setBrushSize(initialSizePx, minPx, maxPx)
        updateColorSelection(selectedHex)

        canvasView.onStrokesChangedListener = {
            btnUndo?.isEnabled = canvasView.canUndo()
            btnUndo?.alpha = if (canvasView.canUndo()) 1.0f else 0.5f
            btnRedo?.isEnabled = canvasView.canRedo()
            btnRedo?.alpha = if (canvasView.canRedo()) 1.0f else 0.5f
        }

        btnUndo?.setBounceClickListener { canvasView.undo() }
        btnRedo?.setBounceClickListener { canvasView.redo() }
        btnClear?.setBounceClickListener { canvasView.clear() }

        btnClose?.setBounceClickListener {
            if (!canvasView.isEmpty()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Discard Handwriting?")
                    .setMessage("Are you sure you want to discard your drawing?")
                    .setPositiveButton("Discard") { _, _ -> closeHandwritingMode() }
                    .setNegativeButton("Keep Editing", null)
                    .show()
            } else {
                closeHandwritingMode()
            }
        }

        btnDone?.setBounceClickListener {
            saveAndCommitHandwriting()
        }

        btnCustomColor?.setBounceClickListener {
            showCustomColorPicker(selectedHex) { customHex ->
                updateColorSelection(customHex)
            }
        }
    }

    private fun getVideoRect(): android.graphics.RectF {
        val activeParent = (canvasContainer.parent as? View) ?: playerContainer
        val containerWidth = activeParent.width.toFloat()
        val containerHeight = activeParent.height.toFloat()
        if (containerWidth <= 0f || containerHeight <= 0f) return android.graphics.RectF(0f, 0f, 1080f, 1920f)

        val baseRatio = if (primaryVideoAspectRatio > 0f) primaryVideoAspectRatio else 16f / 9f
        val containerRatio = containerWidth / containerHeight

        val finalW: Float
        val finalH: Float
        if (baseRatio > containerRatio) {
            finalW = containerWidth
            finalH = containerWidth / baseRatio
        } else {
            finalH = containerHeight
            finalW = containerHeight * baseRatio
        }

        val left = (containerWidth - finalW) / 2f
        val top = (containerHeight - finalH) / 2f
        return android.graphics.RectF(left, top, left + finalW, top + finalH)
    }

    private fun openHandwritingMode() {
        closeActiveEditingModes()
        isHandwritingActive = true
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
            btnPlayPause.setImageResource(R.drawable.ic_play_24)
        }

        val canvasView = findViewById<HandwritingCanvasView>(R.id.handwritingCanvasView)
        val handwritingPanel = findViewById<View>(R.id.handwritingPanel)

        val vRect = getVideoRect()
        canvasView?.setVideoRect(vRect)
        canvasView?.clear()
        canvasView?.visibility = View.VISIBLE
        handwritingPanel?.visibility = View.VISIBLE

        hideMainToolbar()
    }

    private fun closeHandwritingMode() {
        isHandwritingActive = false
        val canvasView = findViewById<HandwritingCanvasView>(R.id.handwritingCanvasView)
        val handwritingPanel = findViewById<View>(R.id.handwritingPanel)

        canvasView?.visibility = View.GONE
        handwritingPanel?.visibility = View.GONE
        showMainToolbar()
        setActiveToolButton(-1)
        updateUIInteractionState()
    }

    private fun saveAndCommitHandwriting() {
        val canvasView = findViewById<HandwritingCanvasView>(R.id.handwritingCanvasView) ?: return
        if (canvasView.isEmpty()) {
            Toast.makeText(this, "Scribble or draw something first!", Toast.LENGTH_SHORT).show()
            return
        }

        val baseRatio = if (primaryVideoAspectRatio > 0f) primaryVideoAspectRatio else 16f / 9f
        val targetWidth: Int
        val targetHeight: Int
        if (baseRatio >= 1.0f) {
            targetWidth = 1920
            targetHeight = (1920f / baseRatio).toInt().coerceAtLeast(1)
        } else {
            targetHeight = 1920
            targetWidth = (1920f * baseRatio).toInt().coerceAtLeast(1)
        }

        val bitmap = canvasView.exportToBitmap(targetWidth, targetHeight)
        if (bitmap == null) {
            Toast.makeText(this, "Failed to render drawing", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val drawingsDir = java.io.File(cacheDir, "drawings").apply { mkdirs() }
            val outFile = java.io.File(drawingsDir, "handwriting_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }

            val imageUri = Uri.fromFile(outFile)
            val startTimeMs = getGlobalPosition()
            val sequenceDuration = getTotalSequenceDuration().coerceAtLeast(3000L)
            val endTimeMs = (startTimeMs + 4000L).coerceAtMost(sequenceDuration)

            val overlayOp = com.eclip.rup.leb.models.EditOperation.AddImageOverlay(
                imageUri = imageUri,
                relativeX = 0.5f,
                relativeY = 0.5f,
                relativeWidth = 1.0f,
                relativeHeight = 1.0f,
                rotationAngle = 0f,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs
            )

            viewModel.addOperation(overlayOp)
            closeHandwritingMode()
            Toast.makeText(this, "Handwriting layer added to timeline", Toast.LENGTH_SHORT).show()

            viewModel.project.value?.let { renderTracks(it) }

        } catch (e: Exception) {
            Toast.makeText(this, "Error saving handwriting: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("InflateParams")
    private fun textAction() {
        if (isTextEditingActive) {
            // Already editing — commit current text
            draggableTextOverlay?.commitText()
            return
        }
        viewModel.selectOperation(null)
        enterTextEditingMode(isReEditing = false)
    }

    fun clearSelectionAndReturnToMainToolbar() {
        // 1. Clear clip/operation selections
        selectedVideoIndex = null
        viewModel.selectOperation(null)

        // 2. Commit or deactivate active text / image draggable overlays
        if (isTextEditingActive) {
            draggableTextOverlay?.commitText()
        } else {
            draggableTextOverlay?.deactivate()
        }
        if (isImageEditingActive) {
            draggableImageOverlay?.commitImage()
        } else {
            draggableImageOverlay?.deactivate()
        }

        // 3. Close and exit all active editing modes / sub-toolbars
        if (isHandwritingActive) {
            closeHandwritingMode()
        }
        if (isMosaicEditingActive) {
            exitMosaicEditingMode()
        }
        if (cropEditingToolbar?.visibility == View.VISIBLE) {
            exitCropEditingMode()
        }
        if (backgroundEditingToolbar?.visibility == View.VISIBLE) {
            exitCanvasBackgroundEditingMode()
        }
        if (speedEditingToolbar?.visibility == View.VISIBLE) {
            hideSpeedEditingToolbar()
        }
        if (audioEditingToolbar?.visibility == View.VISIBLE) {
            exitAudioEditingMode()
        }
        exitVideoEditingMode()
        exitTextEditingMode()
        exitTextLayerMode()
        exitImageEditingMode()

        // 4. Hide all editing toolbars explicitly to ensure clean UI state
        textLayerToolbar?.visibility = View.GONE
        textEditingToolbar?.visibility = View.GONE
        imageEditingToolbar?.visibility = View.GONE
        videoEditingToolbar?.visibility = View.GONE
        audioEditingToolbar?.visibility = View.GONE
        cropEditingToolbar?.visibility = View.GONE
        backgroundEditingToolbar?.visibility = View.GONE
        speedEditingToolbar?.visibility = View.GONE
        mosaicEditingToolbar?.visibility = View.GONE

        // 5. Ensure Main Toolbar is visible and active tool button reset
        showMainToolbar()
        setActiveToolButton(-1)

        // 6. Ensure timeline and player control bar visibility
        findViewById<View>(R.id.playerControlBar)?.visibility = View.VISIBLE
        findViewById<View>(R.id.seekerContainer)?.visibility = View.VISIBLE
        findViewById<View>(R.id.timelineContainer)?.visibility = View.VISIBLE
        findViewById<View>(R.id.timelineDivider)?.visibility = View.VISIBLE

        // 7. Update UI interaction and track selection states
        updateTrackSelectionState()
        updateLayerReorderButtons()
        updateUIInteractionState()
    }

    private fun cancelActiveEditingModes() {
        clearSelectionAndReturnToMainToolbar()
    }

    private fun closeActiveEditingModes() {
        if (isHandwritingActive) {
            closeHandwritingMode()
        }
        if (isTextEditingActive) {
            draggableTextOverlay?.commitText()
            isTextEditingActive = false
        }
        if (isTextLayerEditingActive) {
            isTextLayerEditingActive = false
            textLayerToolbar?.visibility = View.GONE
        }
        if (isImageEditingActive) {
            draggableImageOverlay?.commitImage()
            isImageEditingActive = false
        }
        if (isMosaicEditingActive) {
            exitMosaicEditingMode()
        }
        if (audioPreviewPlayer?.isPlaying == true) {
            try {
                audioPreviewPlayer?.stop()
            } catch (e: Exception) {}
        }
        try {
            audioPreviewPlayer?.release()
        } catch (e: Exception) {}
        audioPreviewPlayer = null
        audioEditingToolbar?.findViewById<ImageButton>(R.id.btnAudioPreviewPlay)?.setImageResource(R.drawable.ic_play_24)
        textLayerToolbar?.visibility = View.GONE
        textEditingToolbar?.visibility = View.GONE
        imageEditingToolbar?.visibility = View.GONE
        videoEditingToolbar?.visibility = View.GONE
        audioEditingToolbar?.visibility = View.GONE
        cropEditingToolbar?.visibility = View.GONE
        backgroundEditingToolbar?.visibility = View.GONE
        mosaicEditingToolbar?.visibility = View.GONE
    }

    private fun getStableTextTabHeight(): Int {
        if (savedKeyboardHeight > (100 * resources.displayMetrics.density).toInt()) {
            return savedKeyboardHeight
        }
        if (normalContentRootHeight > 0) {
            return (normalContentRootHeight * 0.32f).toInt()
        }
        val density = resources.displayMetrics.density
        val screenH = resources.displayMetrics.heightPixels
        return (screenH * 0.30f).toInt().coerceIn((200 * density).toInt(), (250 * density).toInt())
    }

    private fun updateTextToolbarButtonsState(hasText: Boolean) {
        val toolbar = textEditingToolbar ?: return
        val btnFont = toolbar.findViewById<ImageButton>(R.id.btnTextFontTab)
        val btnPalette = toolbar.findViewById<ImageButton>(R.id.btnTextPaletteTab)
        val btnAnimation = toolbar.findViewById<ImageButton>(R.id.btnTextAnimation)
        val btnFormat = toolbar.findViewById<ImageButton>(R.id.btnTextFormatTab)
        val btnDone = toolbar.findViewById<ImageButton>(R.id.btnTextDone)

        val formattingButtons = listOfNotNull(btnFont, btnPalette, btnAnimation, btnFormat, btnDone)

        for (btn in formattingButtons) {
            btn.isEnabled = hasText
            btn.alpha = if (hasText) 1.0f else 0.35f
        }

        if (!hasText && currentTextEditingTab != TextEditingTab.KEYBOARD) {
            switchTextEditingTab(TextEditingTab.KEYBOARD)
        }
    }

    private fun switchTextEditingTab(tab: TextEditingTab) {
        val toolbar = textEditingToolbar ?: return
        val editTextInput = toolbar.findViewById<EditText>(R.id.editTextInputText)
        val hasText = !editTextInput?.text?.toString()?.trim().isNullOrEmpty()

        val targetTab = if (tab != TextEditingTab.KEYBOARD && !hasText) {
            TextEditingTab.KEYBOARD
        } else {
            tab
        }

        currentTextEditingTab = targetTab
        val btnKeyboard = toolbar.findViewById<ImageButton>(R.id.btnTextKeyboardTab)
        val btnFont = toolbar.findViewById<ImageButton>(R.id.btnTextFontTab)
        val btnPalette = toolbar.findViewById<ImageButton>(R.id.btnTextPaletteTab)
        val btnAnimation = toolbar.findViewById<ImageButton>(R.id.btnTextAnimation)
        val btnFormat = toolbar.findViewById<ImageButton>(R.id.btnTextFormatTab)

        val tabContentContainer = toolbar.findViewById<View>(R.id.textTabContentContainer)
        val fontContainer = toolbar.findViewById<View>(R.id.fontSelectionContainer)
        val colorContainer = toolbar.findViewById<View>(R.id.colorPickerContainer)
        val animationContainer = toolbar.findViewById<View>(R.id.animationPickerContainer)
        val formatContainer = toolbar.findViewById<View>(R.id.formatSettingsContainer)

        btnKeyboard?.setColorFilter(getColor(R.color.toolTextInactive))
        btnKeyboard?.setBackgroundResource(R.drawable.tool_button_inactive)
        btnFont?.setColorFilter(getColor(R.color.toolTextInactive))
        btnFont?.setBackgroundResource(R.drawable.tool_button_inactive)
        btnPalette?.setBackgroundResource(R.drawable.tool_button_inactive)
        btnAnimation?.setColorFilter(getColor(R.color.toolTextInactive))
        btnAnimation?.setBackgroundResource(R.drawable.tool_button_inactive)
        btnFormat?.setColorFilter(getColor(R.color.toolTextInactive))
        btnFormat?.setBackgroundResource(R.drawable.tool_button_inactive)

        fontContainer?.visibility = View.GONE
        colorContainer?.visibility = View.GONE
        animationContainer?.visibility = View.GONE
        formatContainer?.visibility = View.GONE

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        when (tab) {
            TextEditingTab.KEYBOARD -> {
                btnKeyboard?.setColorFilter(getColor(R.color.colorPrimary))
                btnKeyboard?.setBackgroundResource(R.drawable.bg_tool_button_active_tinted)

                if (isKeyboardCurrentlyOpen) {
                    tabContentContainer?.visibility = View.GONE
                } else {
                    val targetH = getStableTextTabHeight()
                    tabContentContainer?.layoutParams?.height = targetH
                    tabContentContainer?.visibility = View.VISIBLE
                }

                editTextInput?.requestFocus()
                imm.showSoftInput(editTextInput, InputMethodManager.SHOW_IMPLICIT)
            }
            TextEditingTab.FONT -> {
                btnFont?.setColorFilter(getColor(R.color.colorPrimary))
                btnFont?.setBackgroundResource(R.drawable.bg_tool_button_active_tinted)

                val targetH = getStableTextTabHeight()
                tabContentContainer?.layoutParams?.height = targetH
                tabContentContainer?.visibility = View.VISIBLE
                fontContainer?.visibility = View.VISIBLE

                imm.hideSoftInputFromWindow(editTextInput?.windowToken ?: toolbar.windowToken, 0)
                setupFontSelectionPanel(toolbar)
            }
            TextEditingTab.COLOR -> {
                btnPalette?.setBackgroundResource(R.drawable.bg_tool_button_active_tinted)

                val targetH = getStableTextTabHeight()
                tabContentContainer?.layoutParams?.height = targetH
                tabContentContainer?.visibility = View.VISIBLE
                colorContainer?.visibility = View.VISIBLE

                imm.hideSoftInputFromWindow(editTextInput?.windowToken ?: toolbar.windowToken, 0)
                setupColorPicker(toolbar)
            }
            TextEditingTab.ANIMATION -> {
                btnAnimation?.setColorFilter(getColor(R.color.colorPrimary))
                btnAnimation?.setBackgroundResource(R.drawable.bg_tool_button_active_tinted)

                val targetH = getStableTextTabHeight()
                tabContentContainer?.layoutParams?.height = targetH
                tabContentContainer?.visibility = View.VISIBLE
                animationContainer?.visibility = View.VISIBLE

                imm.hideSoftInputFromWindow(editTextInput?.windowToken ?: toolbar.windowToken, 0)
                setupInlineAnimationPicker(toolbar)
            }
            TextEditingTab.ADJUSTMENT -> {
                btnFormat?.setColorFilter(getColor(R.color.colorPrimary))
                btnFormat?.setBackgroundResource(R.drawable.bg_tool_button_active_tinted)

                val targetH = getStableTextTabHeight()
                tabContentContainer?.layoutParams?.height = targetH
                tabContentContainer?.visibility = View.VISIBLE
                formatContainer?.visibility = View.VISIBLE

                imm.hideSoftInputFromWindow(editTextInput?.windowToken ?: toolbar.windowToken, 0)
                setupAdjustmentPanel(formatContainer)
            }
        }
    }

    private fun setupAdjustmentPanel(container: View?) {
        val formatContainer = container ?: textEditingToolbar?.findViewById<View>(R.id.formatSettingsContainer) ?: return
        setupTextFontSelector(formatContainer)

        val btnFormatBold = formatContainer.findViewById<TextView>(R.id.btnFormatBold)
        val btnFormatItalic = formatContainer.findViewById<TextView>(R.id.btnFormatItalic)
        val btnFormatUnderline = formatContainer.findViewById<TextView>(R.id.btnFormatUnderline)
        val btnFormatCase = formatContainer.findViewById<TextView>(R.id.btnFormatCase)

        btnFormatUnderline?.paintFlags = (btnFormatUnderline?.paintFlags ?: 0) or android.graphics.Paint.UNDERLINE_TEXT_FLAG

        fun updateFormatButtonsUI() {
            if (selectedIsBold) {
                btnFormatBold?.setBackgroundResource(R.drawable.tool_button_active)
                btnFormatBold?.setTextColor(Color.WHITE)
            } else {
                btnFormatBold?.setBackgroundResource(R.drawable.tool_button_inactive)
                btnFormatBold?.setTextColor(Color.parseColor("#8A8A93"))
            }

            if (selectedIsItalic) {
                btnFormatItalic?.setBackgroundResource(R.drawable.tool_button_active)
                btnFormatItalic?.setTextColor(Color.WHITE)
            } else {
                btnFormatItalic?.setBackgroundResource(R.drawable.tool_button_inactive)
                btnFormatItalic?.setTextColor(Color.parseColor("#8A8A93"))
            }

            if (selectedIsUnderline) {
                btnFormatUnderline?.setBackgroundResource(R.drawable.tool_button_active)
                btnFormatUnderline?.setTextColor(Color.WHITE)
            } else {
                btnFormatUnderline?.setBackgroundResource(R.drawable.tool_button_inactive)
                btnFormatUnderline?.setTextColor(Color.parseColor("#8A8A93"))
            }

            when (selectedCaseMode) {
                1 -> {
                    btnFormatCase?.text = "AA"
                    btnFormatCase?.setBackgroundResource(R.drawable.tool_button_active)
                    btnFormatCase?.setTextColor(Color.WHITE)
                }
                2 -> {
                    btnFormatCase?.text = "aa"
                    btnFormatCase?.setBackgroundResource(R.drawable.tool_button_active)
                    btnFormatCase?.setTextColor(Color.WHITE)
                }
                else -> {
                    btnFormatCase?.text = "Aa"
                    btnFormatCase?.setBackgroundResource(R.drawable.tool_button_inactive)
                    btnFormatCase?.setTextColor(Color.parseColor("#8A8A93"))
                }
            }
        }

        updateFormatButtonsUI()

        btnFormatBold?.setOnClickListener {
            selectedIsBold = !selectedIsBold
            draggableTextOverlay?.setBold(selectedIsBold)
            updateFormatButtonsUI()
        }

        btnFormatItalic?.setOnClickListener {
            selectedIsItalic = !selectedIsItalic
            draggableTextOverlay?.setItalic(selectedIsItalic)
            updateFormatButtonsUI()
        }

        btnFormatUnderline?.setOnClickListener {
            selectedIsUnderline = !selectedIsUnderline
            draggableTextOverlay?.setUnderline(selectedIsUnderline)
            updateFormatButtonsUI()
        }

        btnFormatCase?.setOnClickListener {
            selectedCaseMode = (selectedCaseMode + 1) % 3
            draggableTextOverlay?.setCaseMode(selectedCaseMode)
            updateFormatButtonsUI()
        }
    }

    private fun setupInlineAnimationPicker(toolbar: View) {
        val container = toolbar.findViewById<View>(R.id.animationPickerContainer) ?: return
        val tabIn = container.findViewById<TextView>(R.id.tabInAnimInline)
        val tabOut = container.findViewById<TextView>(R.id.tabOutAnimInline)
        val tvDuration = container.findViewById<TextView>(R.id.tvAnimDurationInline)
        val sliderDuration = container.findViewById<com.google.android.material.slider.Slider>(R.id.sliderAnimDurationInline)
        val presetsList = container.findViewById<LinearLayout>(R.id.animPresetsInlineList) ?: return

        sliderDuration?.value = (currentTextAnimationDurationMs / 1000f).coerceIn(0.1f, 2.0f)
        tvDuration?.text = String.format(java.util.Locale.US, "%.1fs", currentTextAnimationDurationMs / 1000f)

        sliderDuration?.clearOnChangeListeners()
        sliderDuration?.addOnChangeListener { _, value, _ ->
            currentTextAnimationDurationMs = (value * 1000).toLong()
            tvDuration?.text = String.format(java.util.Locale.US, "%.1fs", value)

            val selectedId = viewModel.selectedOperationId.value
            if (selectedId != null) {
                val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                if (op != null) {
                    viewModel.updateOperation(op.copy(
                        animationDurationMs = currentTextAnimationDurationMs
                    ))
                }
            }
        }

        val presets = listOf(
            Pair("None", "NONE"),
            Pair("Fade", "FADE"),
            Pair("Zoom", "ZOOM"),
            Pair("Slide Left", "SLIDE_LEFT"),
            Pair("Slide Right", "SLIDE_RIGHT"),
            Pair("Slide Up", "SLIDE_UP"),
            Pair("Slide Down", "SLIDE_DOWN")
        )

        fun refreshPresets() {
            presetsList.removeAllViews()
            val activeAnim = if (isPickingInAnimInline) currentTextInAnimation else currentTextOutAnimation

            for (preset in presets) {
                val item = layoutInflater.inflate(R.layout.item_animation_preset, presetsList, false)
                val tvName = item.findViewById<TextView>(R.id.tvPresetName)
                val ivIcon = item.findViewById<ImageView>(R.id.ivPresetIcon)
                val box = item.findViewById<LinearLayout>(R.id.presetContainer)

                tvName.text = preset.first
                val isSelected = activeAnim.equals(preset.second, ignoreCase = true)
                if (isSelected) {
                    box.setBackgroundResource(R.drawable.tool_button_active)
                    tvName.setTextColor(getColor(R.color.colorPrimary))
                    ivIcon.setColorFilter(getColor(R.color.colorPrimary))
                } else {
                    box.setBackgroundResource(R.drawable.tool_button_inactive)
                    tvName.setTextColor(getColor(R.color.toolTextInactive))
                    ivIcon.setColorFilter(getColor(R.color.toolTextInactive))
                }

                item.setBounceClickListener {
                    if (isPickingInAnimInline) {
                        currentTextInAnimation = preset.second
                    } else {
                        currentTextOutAnimation = preset.second
                    }

                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                        if (op != null) {
                            viewModel.updateOperation(op.copy(
                                inAnimation = currentTextInAnimation,
                                outAnimation = currentTextOutAnimation,
                                animationDurationMs = currentTextAnimationDurationMs
                            ))
                        }
                    }

                    refreshPresets()
                }
                presetsList.addView(item)
            }
        }

        tabIn?.setOnClickListener {
            isPickingInAnimInline = true
            tabIn.setBackgroundResource(R.drawable.bg_font_chip_selected)
            tabIn.setTextColor(Color.WHITE)
            tabOut?.setBackgroundResource(0)
            tabOut?.setTextColor(getColor(R.color.toolTextInactive))
            refreshPresets()
        }

        tabOut?.setOnClickListener {
            isPickingInAnimInline = false
            tabOut.setBackgroundResource(R.drawable.bg_font_chip_selected)
            tabOut.setTextColor(Color.WHITE)
            tabIn?.setBackgroundResource(0)
            tabIn?.setTextColor(getColor(R.color.toolTextInactive))
            refreshPresets()
        }

        refreshPresets()
    }

    private fun updateTextEditingLayoutForKeyboard(isKeyboardOpen: Boolean) {
        if (!isTextEditingActive) return
        if (isKeyboardCurrentlyOpen == isKeyboardOpen) return
        isKeyboardCurrentlyOpen = isKeyboardOpen

        val toolbar = textEditingToolbar ?: return
        val tabContentContainer = toolbar.findViewById<View>(R.id.textTabContentContainer)

        if (isKeyboardOpen) {
            if (currentTextEditingTab == TextEditingTab.KEYBOARD) {
                tabContentContainer?.visibility = View.GONE
            }
        } else {
            if (currentTextEditingTab == TextEditingTab.KEYBOARD) {
                val targetH = getStableTextTabHeight()
                tabContentContainer?.layoutParams?.height = targetH
                tabContentContainer?.visibility = View.VISIBLE
            }
        }
    }

    private fun enterTextEditingMode(isReEditing: Boolean = false, initialTab: TextEditingTab = TextEditingTab.KEYBOARD) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        isTextEditingActive = true
        isKeyboardCurrentlyOpen = (initialTab == TextEditingTab.KEYBOARD)

        if (savedKeyboardHeight == 0) {
            savedKeyboardHeight = getStableTextTabHeight()
        }

        val editTextInput = textEditingToolbar?.findViewById<EditText>(R.id.editTextInputText)

        if (isReEditing) {
            val selectedId = viewModel.selectedOperationId.value
            val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
            selectedTextColor = op?.color ?: draggableTextOverlay?.getTextColor() ?: "#FFFFFF"
            selectedLabelColor = op?.labelColor ?: draggableTextOverlay?.getLabelColor() ?: "NONE"
            selectedBorderColor = op?.borderColor ?: draggableTextOverlay?.getBorderColor() ?: "#000000"
            selectedShadowColor = op?.shadowColor ?: draggableTextOverlay?.getShadowColor() ?: "NONE"
            selectedTextOpacity = op?.opacity ?: draggableTextOverlay?.getOpacity() ?: 1.0f
            selectedLabelOpacity = op?.labelOpacity ?: draggableTextOverlay?.getLabelOpacity() ?: 1.0f
            selectedBorderOpacity = op?.borderOpacity ?: draggableTextOverlay?.getBorderOpacity() ?: 1.0f
            selectedShadowOpacity = op?.shadowOpacity ?: draggableTextOverlay?.getShadowOpacity() ?: 1.0f
            selectedShadowDx = op?.shadowDx ?: draggableTextOverlay?.getShadowDx() ?: 4f
            selectedShadowDy = op?.shadowDy ?: draggableTextOverlay?.getShadowDy() ?: 4f
            selectedShadowBlur = op?.shadowRadius ?: draggableTextOverlay?.getShadowRadius() ?: 8f
            selectedBorderThickness = op?.borderThickness ?: draggableTextOverlay?.getBorderThickness() ?: 0
            selectedIsBold = op?.isBold ?: draggableTextOverlay?.getBold() ?: false
            selectedIsItalic = op?.isItalic ?: draggableTextOverlay?.getItalic() ?: false
            selectedIsUnderline = op?.isUnderline ?: draggableTextOverlay?.getUnderline() ?: false
            selectedCaseMode = op?.caseMode ?: draggableTextOverlay?.getCaseMode() ?: 0
            currentColorTargetMode = ColorTargetMode.TEXT
            currentTextInAnimation = op?.inAnimation ?: "NONE"
            currentTextOutAnimation = op?.outAnimation ?: "NONE"
            currentTextAnimationDurationMs = op?.animationDurationMs ?: 500L
            val text = op?.text ?: draggableTextOverlay?.getText() ?: ""
            editTextInput?.setText(text)
            editTextInput?.setSelection(text.length)
            if (op != null) {
                draggableTextOverlay?.activateForEdit(op)
            }
            updateTextToolbarButtonsState(text.trim().isNotEmpty())
        } else {
            selectedTextColor = "#FFFFFF"
            selectedLabelColor = "NONE"
            selectedBorderColor = "#000000"
            selectedShadowColor = "NONE"
            selectedTextOpacity = 1.0f
            selectedLabelOpacity = 1.0f
            selectedBorderOpacity = 1.0f
            selectedShadowOpacity = 1.0f
            selectedShadowDx = 4f
            selectedShadowDy = 4f
            selectedShadowBlur = 8f
            selectedBorderThickness = 0
            selectedIsBold = false
            selectedIsItalic = false
            selectedIsUnderline = false
            selectedCaseMode = 0
            currentColorTargetMode = ColorTargetMode.TEXT
            currentTextInAnimation = "NONE"
            currentTextOutAnimation = "NONE"
            currentTextAnimationDurationMs = 500L
            draggableTextOverlay?.activate("", 36, fontFilePath)
            editTextInput?.setText("")
            updateTextToolbarButtonsState(false)
        }

        textLayerToolbar?.visibility = View.GONE
        textEditingToolbar?.visibility = View.VISIBLE
        switchTextEditingTab(initialTab)

        hideMainToolbar()
        findViewById<View>(R.id.playerControlBar)?.visibility = View.GONE
        findViewById<View>(R.id.seekerContainer)?.visibility = View.GONE
        findViewById<View>(R.id.timelineDivider)?.visibility = View.GONE

        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }

        if (initialTab == TextEditingTab.KEYBOARD) {
            editTextInput?.post {
                editTextInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editTextInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun exitTextEditingMode() {
        isTextEditingActive = false
        isKeyboardCurrentlyOpen = false

        val editTextInput = textEditingToolbar?.findViewById<EditText>(R.id.editTextInputText)
        if (editTextInput != null) {
            editTextInput.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editTextInput.windowToken, 0)
        }

        textEditingToolbar?.findViewById<View>(R.id.textTabContentContainer)?.visibility = View.GONE
        textEditingToolbar?.visibility = View.GONE
        textLayerToolbar?.visibility = View.GONE
        draggableTextOverlay?.deactivate()

        findViewById<View>(R.id.playerControlBar)?.visibility = View.VISIBLE
        findViewById<View>(R.id.seekerContainer)?.visibility = View.VISIBLE
        findViewById<View>(R.id.timelineContainer)?.visibility = View.VISIBLE
        findViewById<View>(R.id.timelineDivider)?.visibility = View.VISIBLE

        showMainToolbar()
        setActiveToolButton(0)
        updateUIInteractionState()

        val cropOp = viewModel.project.value?.operations
            ?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Crop>()
            ?.lastOrNull()
        if (cropOp != null) {
            applyCropPreview(cropOp.aspectRatio)
        } else {
            resetCropPreview()
        }
    }

    private fun enterTextLayerMode(op: EditOperation.AddText) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        isTextLayerEditingActive = true
        textLayerToolbar?.visibility = View.VISIBLE
        hideMainToolbar()
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitTextLayerMode() {
        isTextLayerEditingActive = false
        textLayerToolbar?.visibility = View.GONE
        showMainToolbar()
        setActiveToolButton(0)
        updateUIInteractionState()
    }

    private fun splitSelectedTextLayer() {
        val selectedId = viewModel.selectedOperationId.value ?: return
        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText ?: return

        val currentPos = getGlobalPosition()
        val start = op.startTimeMs ?: 0L
        val end = op.endTimeMs ?: getTotalSequenceDuration()

        if (currentPos <= start + 100L || currentPos >= end - 100L) {
            Toast.makeText(this, "Position playhead inside text duration to split", Toast.LENGTH_SHORT).show()
            return
        }

        val firstPart = op.copy(endTimeMs = currentPos)
        val secondPartId = System.nanoTime().toString()
        val secondPart = op.copy(id = secondPartId, startTimeMs = currentPos, endTimeMs = end)

        viewModel.updateOperation(firstPart)
        viewModel.addOperation(secondPart)
        viewModel.selectOperation(secondPartId)
        Toast.makeText(this, "Text split successfully", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedTextLayer() {
        val selectedId = viewModel.selectedOperationId.value ?: return
        draggableTextOverlay?.deactivate()
        viewModel.removeOperation(selectedId)
        viewModel.selectOperation(null)
        exitTextLayerMode()
    }

    private fun duplicateSelectedTextLayer() {
        val selectedId = viewModel.selectedOperationId.value ?: return
        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText ?: return

        val newId = System.nanoTime().toString()
        val currentPos = getGlobalPosition()
        val duration = (op.endTimeMs ?: (currentPos + 3000L)) - (op.startTimeMs ?: currentPos)
        val newStart = currentPos
        val newEnd = minOf(newStart + duration, getTotalSequenceDuration())

        val newOp = op.copy(
            id = newId,
            relativeX = ((op.relativeX ?: 0.5f) + 0.05f).coerceIn(0.1f, 0.9f),
            relativeY = ((op.relativeY ?: 0.5f) + 0.05f).coerceIn(0.1f, 0.9f),
            startTimeMs = newStart,
            endTimeMs = newEnd
        )

        viewModel.addOperation(newOp)
        viewModel.selectOperation(newId)
        Toast.makeText(this, "Text duplicated", Toast.LENGTH_SHORT).show()
    }

    private fun duplicateSelectedOverlay() {
        val selectedId = viewModel.selectedOperationId.value ?: return
        val currentProject = viewModel.project.value ?: return
        val op = currentProject.operations.find {
            when (it) {
                is EditOperation.AddText -> it.id == selectedId
                is EditOperation.AddImageOverlay -> it.id == selectedId
                else -> false
            }
        } ?: return

        val newOp = when (op) {
            is EditOperation.AddText -> {
                val newX = ((op.relativeX ?: 0.5f) + 0.05f).coerceIn(0f, 1f)
                val newY = ((op.relativeY ?: 0.5f) + 0.05f).coerceIn(0f, 1f)
                op.copy(
                    id = System.nanoTime().toString(),
                    relativeX = newX,
                    relativeY = newY
                )
            }
            is EditOperation.AddImageOverlay -> {
                val newX = (op.relativeX + 0.05f).coerceIn(0f, 1f)
                val newY = (op.relativeY + 0.05f).coerceIn(0f, 1f)
                op.copy(
                    id = System.nanoTime().toString(),
                    relativeX = newX,
                    relativeY = newY
                )
            }
            else -> null
        }

        val newOpId = when (newOp) {
            is EditOperation.AddText -> newOp.id
            is EditOperation.AddImageOverlay -> newOp.id
            else -> null
        }

        if (newOp != null && newOpId != null) {
            viewModel.addOperation(newOp)
            viewModel.selectOperation(newOpId)
        }
    }

    private fun voiceOverAction() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            showVoiceOverDialog()
        } else {
            requestRecordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showVoiceOverDialog() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_voice_over, null)
        bottomSheet.setContentView(view)

        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseVoiceOver)
        val btnToggle = view.findViewById<ImageButton>(R.id.btnToggleRecording)
        val tvStatus = view.findViewById<TextView>(R.id.tvRecordingStatus)
        val ripple1 = view.findViewById<View>(R.id.ripple1)
        val ripple2 = view.findViewById<View>(R.id.ripple2)
        
        isRecordingVoiceOver = false

        val scaleX1 = android.animation.ObjectAnimator.ofFloat(ripple1, "scaleX", 1f, 1.8f)
        val scaleY1 = android.animation.ObjectAnimator.ofFloat(ripple1, "scaleY", 1f, 1.8f)
        val alpha1 = android.animation.ObjectAnimator.ofFloat(ripple1, "alpha", 0.6f, 0f)
        
        val scaleX2 = android.animation.ObjectAnimator.ofFloat(ripple2, "scaleX", 1f, 1.8f)
        val scaleY2 = android.animation.ObjectAnimator.ofFloat(ripple2, "scaleY", 1f, 1.8f)
        val alpha2 = android.animation.ObjectAnimator.ofFloat(ripple2, "alpha", 0.6f, 0f)

        scaleX1.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleY1.repeatCount = android.animation.ValueAnimator.INFINITE
        alpha1.repeatCount = android.animation.ValueAnimator.INFINITE
        
        scaleX2.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleY2.repeatCount = android.animation.ValueAnimator.INFINITE
        alpha2.repeatCount = android.animation.ValueAnimator.INFINITE
        
        val rippleAnimator = android.animation.AnimatorSet().apply {
            playTogether(scaleX1, scaleY1, alpha1)
            duration = 1500
        }
        val rippleAnimator2 = android.animation.AnimatorSet().apply {
            playTogether(scaleX2, scaleY2, alpha2)
            duration = 1500
            startDelay = 750
        }

        btnToggle.setBounceClickListener {
            if (!isRecordingVoiceOver) {
                // Start Recording
                val assetsDir = DraftManager.getProjectAssetsDirectory(this@VideoEditingActivity)
                voiceOverFile = File(assetsDir, "voice_over_${System.currentTimeMillis()}.m4a")
                try {
                    mediaRecorder = android.media.MediaRecorder().apply {
                        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                        setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                        setOutputFile(voiceOverFile?.absolutePath)
                        prepare()
                        start()
                    }
                    
                    isRecordingVoiceOver = true
                    tvStatus.text = "Recording... Tap to stop"
                    btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF4081"))
                    
                    rippleAnimator.start()
                    rippleAnimator2.start()
                    
                    // Play video while recording
                    voiceOverStartTimeMs = getGlobalPosition()
                    if (::player.isInitialized) {
                        player.play()
                        if (::btnPlayPause.isInitialized) {
                            btnPlayPause.setImageResource(R.drawable.ic_pause_24)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_start_recording, Toast.LENGTH_SHORT).show()
                }
            } else {
                // Stop Recording
                try {
                    mediaRecorder?.stop()
                    mediaRecorder?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                mediaRecorder = null
                isRecordingVoiceOver = false
                
                rippleAnimator.cancel()
                rippleAnimator2.cancel()
                ripple1.alpha = 0f
                ripple2.alpha = 0f
                ripple1.scaleX = 1f
                ripple1.scaleY = 1f
                ripple2.scaleX = 1f
                ripple2.scaleY = 1f
                
                // Pause video
                if (::player.isInitialized) {
                    player.pause()
                    if (::btnPlayPause.isInitialized) {
                        btnPlayPause.setImageResource(R.drawable.ic_play_24)
                    }
                }
                
                tvStatus.text = "Tap to start recording"
                btnToggle.backgroundTintList = null // Reset tint
                
                // Add to audio layer
                voiceOverFile?.let { file ->
                    if (file.exists()) {
                        var durationMs = 3000L
                        try {
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(file.absolutePath)
                                val timeStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                if (timeStr != null) {
                                    durationMs = timeStr.toLongOrNull() ?: 3000L
                                }
                            } finally {
                                try {
                                    retriever.release()
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error releasing MediaMetadataRetriever: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error extracting voice over duration: ${e.message}", e)
                        }
                        
                        com.eclip.rup.leb.utils.AppCachePolicyManager.registerActiveFile(file)
                        viewModel.addBackgroundAudioOperation(
                            audioUri = Uri.fromFile(file),
                            startTimeMs = voiceOverStartTimeMs,
                            endTimeMs = voiceOverStartTimeMs + durationMs,
                            internalStartMs = 0L,
                            internalEndMs = durationMs
                        )
                    }
                }
                bottomSheet.dismiss()
            }
        }

        btnClose.setBounceClickListener {
            bottomSheet.dismiss()
        }
        
        bottomSheet.setOnDismissListener {
            rippleAnimator.cancel()
            rippleAnimator2.cancel()
            if (isRecordingVoiceOver) {
                try { mediaRecorder?.stop(); mediaRecorder?.release() } catch(e: Exception) {}
                mediaRecorder = null
                isRecordingVoiceOver = false
                if (::player.isInitialized) {
                    player.pause()
                    if (::btnPlayPause.isInitialized) {
                        btnPlayPause.setImageResource(R.drawable.ic_play_24)
                    }
                }
            }
            setActiveToolButton(-1)
        }

        bottomSheet.show()
    }

    private fun updateCanvasBackgroundPreview() {
        val op = viewModel.project.value?.operations?.filterIsInstance<EditOperation.CanvasBackground>()?.lastOrNull()
        if (op != null) {
            when (op.type) {
                EditOperation.CanvasBackground.BackgroundType.COLOR -> {
                    try {
                        bgPreviewImageView?.setBackgroundColor(android.graphics.Color.parseColor(op.colorHex))
                        bgPreviewImageView?.setImageDrawable(null)
                        bgPreviewImageView?.visibility = View.VISIBLE
                    } catch (e: Exception) {}
                }
                EditOperation.CanvasBackground.BackgroundType.IMAGE -> {
                    if (op.imageUri != null) {
                        lifecycleScope.launch {
                            val path = getFilePathFromUri(op.imageUri)
                            if (path != null) {
                                try {
                                    val bm = withContext(Dispatchers.IO) {
                                        android.graphics.BitmapFactory.decodeFile(path)
                                    }
                                    bgPreviewImageView?.setImageBitmap(bm)
                                    bgPreviewImageView?.scaleType = ImageView.ScaleType.CENTER_CROP
                                    bgPreviewImageView?.visibility = View.VISIBLE
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
                EditOperation.CanvasBackground.BackgroundType.BLUR -> {
                    bgPreviewImageView?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun pickBackgroundImage() {
        val picker = com.eclip.rup.leb.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.eclip.rup.leb.customviews.MediaPickerBottomSheet.MediaType.IMAGE
            showCategoryTabs = false
            onMediaSelectedListener = { uri ->
                processBackgroundImageSelected(uri)
            }
            onBrowseSystemFoldersRequested = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    pickBackgroundImageLauncher.launch(Intent.createChooser(intent, "Select Background Image"))
                } catch (e: android.content.ActivityNotFoundException) {
                    try {
                        pickBackgroundImageLauncher.launch(intent)
                    } catch (e2: android.content.ActivityNotFoundException) {
                        Toast.makeText(this@VideoEditingActivity, "No app found to handle this action", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        picker.show(supportFragmentManager, "MediaPickerBottomSheet")
    }

    private fun processBackgroundImageSelected(uri: Uri) {
        lifecycleScope.launch {
            val extension = withContext(Dispatchers.IO) {
                getExtensionFromUri(uri) ?: ".png"
            }
            val tempImageFile = withContext(Dispatchers.IO) {
                copyContentUriToTempFile(uri, "bg_image", extension)
            }
            if (tempImageFile != null) {
                val localUri = Uri.fromFile(tempImageFile)
                viewModel.updateCanvasBackgroundOperation(
                    type = EditOperation.CanvasBackground.BackgroundType.IMAGE,
                    imageUri = localUri
                )
                updateCanvasBackgroundPreview()
            } else {
                Toast.makeText(this@VideoEditingActivity, "Failed to load background image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun mediaOverlayAction() {
        if (isImageEditingActive) {
            draggableImageOverlay?.commitImage()
            return
        }
        openImagePicker()
    }


    private fun openImagePicker() {
        val picker = com.eclip.rup.leb.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.eclip.rup.leb.customviews.MediaPickerBottomSheet.MediaType.IMAGE
            showCategoryTabs = true
            showAudioTab = false
            onMediaSelectedListener = { uri ->
                showImageOverlayConfig(uri)
            }
            onBrowseSystemFoldersRequested = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    pickImageOverlayLauncher.launch(Intent.createChooser(intent, "Select Image, GIF, or Video Overlay"))
                } catch (e: android.content.ActivityNotFoundException) {
                    try {
                        pickImageOverlayLauncher.launch(intent)
                    } catch (e2: android.content.ActivityNotFoundException) {
                        Toast.makeText(this@VideoEditingActivity, "No app found to handle this action", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        picker.show(supportFragmentManager, "MediaPickerBottomSheet")
    }

    private fun showImageOverlayConfig(imageUri: Uri) {
        lifecycleScope.launch {
            val extension = withContext(Dispatchers.IO) {
                getExtensionFromUri(imageUri) ?: ".png"
            }
            val tempImageFile = withContext(Dispatchers.IO) {
                copyContentUriToTempFile(imageUri, "overlay_image", extension)
            }
            if (tempImageFile == null) {
                Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_load_overlay_file, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val localUri = Uri.fromFile(tempImageFile)
            
            val isVideo = extension.equals(".mp4", ignoreCase = true) ||
                          extension.equals(".mkv", ignoreCase = true) ||
                          extension.equals(".mov", ignoreCase = true) ||
                          extension.equals(".3gp", ignoreCase = true)
            
            val aspect = if (isVideo) {
                withContext(Dispatchers.IO) {
                    var retriever: android.media.MediaMetadataRetriever? = null
                    try {
                        retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(tempImageFile.absolutePath)
                        val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        val rStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        val w = wStr?.toIntOrNull() ?: 1
                        val h = hStr?.toIntOrNull() ?: 1
                        val r = rStr?.toIntOrNull() ?: 0
                        val isSwapped = r == 90 || r == 270
                        if (isSwapped) h.toFloat() / w else w.toFloat() / h
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting video aspect: ${e.message}", e)
                        1.0f
                    } finally {
                        try { retriever?.release() } catch (_: Exception) {}
                    }
                }
            } else {
                var rotation = 0
                try {
                    val exif = android.media.ExifInterface(tempImageFile.absolutePath)
                    val orientation = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                    when (orientation) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> rotation = 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> rotation = 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> rotation = 270
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading EXIF rotation: ${e.message}")
                }
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(tempImageFile.absolutePath, options)
                if (options.outHeight > 0) {
                    val isSwapped = rotation == 90 || rotation == 270
                    if (isSwapped) options.outHeight.toFloat() / options.outWidth else options.outWidth.toFloat() / options.outHeight
                } else 1.0f
            }
            
            enterImageEditingMode(localUri, aspect)
        }
    }

    private fun getExtensionFromUri(uri: Uri): String? {
        val mimeType = contentResolver.getType(uri)
        if (mimeType != null) {
            // MimeTypeMap doesn't always know HEIC/HEIF — handle them manually
            when (mimeType.lowercase()) {
                "image/heic", "image/heif" -> return ".heic"
                "image/avif" -> return ".avif"
                "image/webp" -> return ".webp"
                "image/gif"  -> return ".gif"
                "image/png"  -> return ".png"
                "image/jpeg", "image/jpg" -> return ".jpg"
                "video/mp4"  -> return ".mp4"
                "video/quicktime" -> return ".mov"
                "video/x-matroska" -> return ".mkv"
                "video/3gpp" -> return ".3gp"
                "video/webm" -> return ".webm"
                "video/avi", "video/x-msvideo" -> return ".avi"
                "audio/mpeg", "audio/mp3" -> return ".mp3"
                "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> return ".m4a"
                "audio/ogg", "application/ogg" -> return ".ogg"
                "audio/flac", "audio/x-flac" -> return ".flac"
                "audio/wav", "audio/x-wav", "audio/wave" -> return ".wav"
                "audio/opus" -> return ".opus"
            }
            val mime = android.webkit.MimeTypeMap.getSingleton()
            val ext = mime.getExtensionFromMimeType(mimeType)
            if (ext != null) {
                return ".$ext"
            }
        }
        // Try path-based lookup first (works for file:// and content:// that embed filename)
        val path = uri.path ?: return null
        val lastDot = path.lastIndexOf('.')
        if (lastDot != -1 && lastDot < path.length - 1) {
            val ext = path.substring(lastDot)
            // Only return if it looks like a real extension (e.g. not "/media/123")
            if (ext.length in 2..5 && ext.all { it == '.' || it.isLetterOrDigit() }) {
                return ext
            }
        }
        // Last resort: ask for the display name (works for MediaStore content:// URIs)
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0) ?: return@use
                    val dot = name.lastIndexOf('.')
                    if (dot != -1 && dot < name.length - 1) return name.substring(dot)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getOverlayFileDurationMs(uri: Uri): Long? {
        val path = uri.path ?: return null
        val isGif = path.endsWith(".gif", ignoreCase = true)
        val isVideo = path.endsWith(".mp4", ignoreCase = true) ||
                      path.endsWith(".mkv", ignoreCase = true) ||
                      path.endsWith(".mov", ignoreCase = true) ||
                      path.endsWith(".3gp", ignoreCase = true)
        if (isGif) {
            try {
                val movie = android.graphics.Movie.decodeFile(path)
                if (movie != null && movie.duration() > 0) {
                    return movie.duration().toLong()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading GIF duration: ${e.message}", e)
            }
        } else if (isVideo) {
            var retriever: android.media.MediaMetadataRetriever? = null
            try {
                retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(path)
                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLongOrNull()
                if (durationMs != null && durationMs > 0) {
                    return durationMs
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading video duration: ${e.message}", e)
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun enterImageEditingMode(uri: Uri, aspect: Float) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        isImageEditingActive = true
        draggableImageOverlay?.activate(uri, aspect)
        imageEditingToolbar?.visibility = View.VISIBLE
        imageEditingToolbar?.let { toolbar ->
            val slider = toolbar.findViewById<Slider>(R.id.imageRotationSlider)
            val tvValue = toolbar.findViewById<TextView>(R.id.tvImageRotationValue)
            slider?.value = 0f
            tvValue?.text = "0°"

            val opacitySlider = toolbar.findViewById<Slider>(R.id.imageOpacitySlider)
            val tvOpacityValue = toolbar.findViewById<TextView>(R.id.tvImageOpacityValue)
            opacitySlider?.value = 100f
            tvOpacityValue?.text = "100%"

            toolbar.findViewById<View>(R.id.imageOpacitySliderRow)?.visibility = View.GONE
            toolbar.findViewById<View>(R.id.imageRotationSliderRow)?.visibility = View.VISIBLE
            toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.toolTextInactive))
        }
        hideMainToolbar()
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun enterImageEditingModeForEdit(rotation: Float) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        isImageEditingActive = true
        imageEditingToolbar?.visibility = View.VISIBLE
        imageEditingToolbar?.let { toolbar ->
            val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.imageRotationSlider)
            val tvValue = toolbar.findViewById<android.widget.TextView>(R.id.tvImageRotationValue)
            slider?.value = rotation
            tvValue?.text = "${rotation.toInt()}°"
            
            val loopContainer = toolbar.findViewById<View>(R.id.btnImageLoopContainer)
            val btnLoop = toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageLoop)
            val tvLoop = toolbar.findViewById<android.widget.TextView>(R.id.tvImageLoop)
            
            val selectedId = viewModel.selectedOperationId.value
            val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
            
            val isGif = op?.imageUri?.path?.endsWith(".gif", ignoreCase = true) == true
            val isVideo = op?.imageUri?.path?.endsWith(".mp4", ignoreCase = true) == true ||
                          op?.imageUri?.path?.endsWith(".mkv", ignoreCase = true) == true ||
                          op?.imageUri?.path?.endsWith(".mov", ignoreCase = true) == true ||
                          op?.imageUri?.path?.endsWith(".3gp", ignoreCase = true) == true
            
            if (op != null && (isGif || isVideo)) {
                loopContainer?.visibility = View.VISIBLE
                val isLooping = op.isLooping
                val color = if (isLooping) androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary) else androidx.core.content.ContextCompat.getColor(this, R.color.toolTextInactive)
                btnLoop?.setColorFilter(color)
                tvLoop?.setTextColor(color)
            } else {
                loopContainer?.visibility = View.GONE
            }

            val opacitySlider = toolbar.findViewById<Slider>(R.id.imageOpacitySlider)
            val tvOpacityValue = toolbar.findViewById<TextView>(R.id.tvImageOpacityValue)
            val currentOpacity = ((op?.opacity ?: 1.0f) * 100f).coerceIn(0f, 100f)
            opacitySlider?.value = currentOpacity
            tvOpacityValue?.text = "${currentOpacity.toInt()}%"

            toolbar.findViewById<View>(R.id.imageOpacitySliderRow)?.visibility = View.GONE
            toolbar.findViewById<View>(R.id.imageRotationSliderRow)?.visibility = View.VISIBLE
            toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.toolTextInactive))
        }
        hideMainToolbar()
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitImageEditingMode() {
        isImageEditingActive = false
        imageEditingToolbar?.visibility = View.GONE
        showMainToolbar()
        setActiveToolButton(0)
        updateUIInteractionState()
    }

    private fun enterVideoEditingMode() {
        closeActiveEditingModes()
        viewModel.selectOperation(null)
        videoEditingToolbar?.visibility = View.VISIBLE
        hideMainToolbar()
        videoEditingToolbar?.findViewById<View>(R.id.btnVideoDeleteContainer)?.visibility = View.VISIBLE
        selectedVideoIndex?.let { index ->
            videoEditingToolbar?.let { toolbar ->
                updateVideoMuteButtonState(toolbar, index)
            }
        }
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun freezeFrameAtCurrentPosition() {
        val index = selectedVideoIndex ?: return
        val sequenceItems = getSequenceItems()
        if (index < 0 || index >= sequenceItems.size) return
        val item = sequenceItems[index]

        showLoading("Creating freeze frame...")
        isImportLoading = true

        val globalPos = getGlobalPosition()

        lifecycleScope.launch {
            try {
                val resultUri = withContext(Dispatchers.IO) {
                    var bitmap: android.graphics.Bitmap? = null
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this@VideoEditingActivity, item.uri)
                        
                        var globalStartMs = 0L
                        for (i in 0 until index) {
                            globalStartMs += sequenceItems[i].trimmedDurationMs
                        }
                        val relativePosMs = globalPos - globalStartMs
                        val sourceTimeMs = item.trimStartMs + (relativePosMs * item.speed).toLong()
                        val coercedSourceTimeMs = sourceTimeMs.coerceIn(item.trimStartMs, item.trimEndMs)

                        bitmap = com.eclip.rup.leb.utils.CodecInstancePool.withHardwareCodecPermit {
                            retriever.getFrameAtTime(coercedSourceTimeMs * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to retrieve frame: ${e.message}", e)
                    } finally {
                        try {
                            retriever.release()
                        } catch (e: Exception) {}
                    }

                    if (bitmap == null) return@withContext null

                    val pngFile = File(cacheDir, "freeze_${System.nanoTime()}.png")
                    try {
                        java.io.FileOutputStream(pngFile).use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save temp PNG: ${e.message}", e)
                        return@withContext null
                    }

                    val mp4File = File(cacheDir, "freeze_vid_${System.nanoTime()}.mp4")
                    val cmd = "-y -loop 1 -i \"${pngFile.absolutePath}\" -c:v h264_mediacodec -t 3 -pix_fmt yuv420p -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" \"${mp4File.absolutePath}\""
                    val renderResult = ffmpegEngine.executeCommand(cmd)

                    try {
                        pngFile.delete()
                    } catch (e: Exception) {}

                    if (renderResult is com.eclip.rup.leb.services.FFmpegRenderEngine.RenderResult.Success) {
                        AppCachePolicyManager.registerActiveFile(mp4File)
                        Uri.fromFile(mp4File)
                    } else {
                        Log.e(TAG, "FFmpeg failed to create freeze frame video")
                        null
                    }
                }

                if (resultUri != null) {
                    val freezeFrameItem = com.eclip.rup.leb.models.EditOperation.MergeItem(resultUri, 3000L, isImage = true)
                    val newItems = mutableListOf<com.eclip.rup.leb.models.EditOperation.MergeItem>()
                    
                    var globalStartMs = 0L
                    for (i in 0 until index) {
                        globalStartMs += sequenceItems[i].trimmedDurationMs
                    }
                    val relativePosMs = globalPos - globalStartMs
                    
                    for (i in 0 until sequenceItems.size) {
                        if (i == index) {
                            val splitSourceMs = item.trimStartMs + (relativePosMs * item.speed).toLong()
                            if (splitSourceMs > item.trimStartMs && splitSourceMs < item.trimEndMs) {
                                val itemA = item.copy(trimEndMs = splitSourceMs)
                                val itemB = item.copy(trimStartMs = splitSourceMs)
                                newItems.add(itemA)
                                newItems.add(freezeFrameItem)
                                newItems.add(itemB)
                            } else if (splitSourceMs <= item.trimStartMs) {
                                newItems.add(freezeFrameItem)
                                newItems.add(item)
                            } else {
                                newItems.add(item)
                                newItems.add(freezeFrameItem)
                            }
                        } else {
                            newItems.add(sequenceItems[i])
                        }
                    }
                    
                    selectedVideoIndex = null
                    exitVideoEditingMode()
                    if (newItems.isNotEmpty()) {
                        viewModel.updateFullSequenceOrder(newItems)
                    }
                    Toast.makeText(this@VideoEditingActivity, R.string.toast_freeze_frame_added_to_sequence, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_create_freeze_frame, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Freeze frame creation error: ${e.message}", e)
                Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_create_freeze_frame, Toast.LENGTH_SHORT).show()
            } finally {
                isImportLoading = false
                hideLoading()
            }
        }
    }

    private fun exitVideoEditingMode() {
        videoEditingToolbar?.visibility = View.GONE
        showMainToolbar()
        setActiveToolButton(0)
        updateUIInteractionState()
    }

    private fun openMosaicEditingMode(existingOp: EditOperation.MosaicOverlay? = null) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        isMosaicEditingActive = true
        activeEditingMosaicOpId = existingOp?.id
        hideMainToolbar()

        mosaicOverlayView?.visibility = View.VISIBLE
        mosaicOverlayView?.isEditingMode = true
        mosaicOverlayView?.hiddenOperationId = existingOp?.id
        mosaicEditingToolbar?.visibility = View.VISIBLE

        val relX = existingOp?.relativeX ?: 0.5f
        val relY = existingOp?.relativeY ?: 0.5f
        val relW = existingOp?.relativeWidth ?: 0.35f
        val relH = existingOp?.relativeHeight ?: 0.35f
        activeMosaicIntensity = existingOp?.intensity ?: 15
        activeMosaicShape = existingOp?.shape ?: EditOperation.MaskShape.RECTANGLE

        mosaicOverlayView?.setMosaicConfig(relX, relY, relW, relH, activeMosaicIntensity, activeMosaicShape)
        mosaicOverlayView?.onMosaicChanged = { _: Float, _: Float, _: Float, _: Float, intens: Int, shp: EditOperation.MaskShape ->
            activeMosaicIntensity = intens
            activeMosaicShape = shp
        }

        mosaicEditingToolbar?.let { toolbar ->
            val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.mosaicIntensitySlider)
            val tvValue = toolbar.findViewById<TextView>(R.id.tvMosaicIntensityValue)
            slider?.value = activeMosaicIntensity.toFloat()
            tvValue?.text = "$activeMosaicIntensity"

            val btnRect = toolbar.findViewById<ImageButton>(R.id.btnMosaicShapeRect)
            val btnCircle = toolbar.findViewById<ImageButton>(R.id.btnMosaicShapeCircle)

            if (activeMosaicShape == EditOperation.MaskShape.RECTANGLE) {
                btnRect?.setColorFilter(getColor(R.color.colorPrimary))
                btnCircle?.setColorFilter(getColor(R.color.toolTextInactive))
            } else {
                btnRect?.setColorFilter(getColor(R.color.toolTextInactive))
                btnCircle?.setColorFilter(getColor(R.color.colorPrimary))
            }

            val btnDelete = toolbar.findViewById<View>(R.id.btnMosaicDelete)
            btnDelete?.visibility = if (existingOp != null) View.VISIBLE else View.GONE
        }

        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitMosaicEditingMode() {
        isMosaicEditingActive = false
        activeEditingMosaicOpId = null
        mosaicOverlayView?.isEditingMode = false
        mosaicOverlayView?.hiddenOperationId = null
        mosaicEditingToolbar?.visibility = View.GONE
        val hasMosaicOps = viewModel.project.value?.operations?.any { it is EditOperation.MosaicOverlay } == true
        mosaicOverlayView?.visibility = if (hasMosaicOps) View.VISIBLE else View.GONE
        mosaicOverlayView?.invalidate()
        showMainToolbar()
        setActiveToolButton(0)
        updateUIInteractionState()
    }

    private fun showReorderClipsDialog() {
        val initialItems = getSequenceItems()
        if (initialItems.isEmpty()) {
            Toast.makeText(this, "No clips in timeline to sort", Toast.LENGTH_SHORT).show()
            return
        }

        data class ReorderItem(val originalIndex: Int, val item: EditOperation.MergeItem)
        val reorderList = initialItems.mapIndexed { idx, item -> ReorderItem(idx, item) }.toMutableList()

        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_dialog_reorder_clips, null)
        bottomSheet.setContentView(view)

        val clipListContainer = view.findViewById<LinearLayout>(R.id.reorderClipList)
        val btnReverse = view.findViewById<View>(R.id.btnReverseOrder)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseReorder)
        val btnCancel = view.findViewById<View>(R.id.btnCancelReorder)
        val btnApply = view.findViewById<View>(R.id.btnApplyReorder)

        fun refreshList() {
            clipListContainer.removeAllViews()
            for (i in reorderList.indices) {
                val reorderItem = reorderList[i]
                val item = reorderItem.item
                val row = layoutInflater.inflate(R.layout.item_reorder_clip, clipListContainer, false)
                val tvIndex = row.findViewById<TextView>(R.id.tvClipIndex)
                val tvName = row.findViewById<TextView>(R.id.tvClipName)
                val btnUp = row.findViewById<ImageButton>(R.id.btnMoveUp)
                val btnDown = row.findViewById<ImageButton>(R.id.btnMoveDown)

                tvIndex.text = "${i + 1}"
                val name = item.uri.lastPathSegment ?: "Clip ${i + 1}"
                tvName.text = "$name (${formatDuration(item.trimmedDurationMs.toInt())})"

                btnUp.visibility = if (i > 0) View.VISIBLE else View.INVISIBLE
                btnDown.visibility = if (i < reorderList.size - 1) View.VISIBLE else View.INVISIBLE

                btnUp.setBounceClickListener {
                    if (i > 0) {
                        val temp = reorderList[i]
                        reorderList[i] = reorderList[i - 1]
                        reorderList[i - 1] = temp
                        refreshList()
                    }
                }

                btnDown.setBounceClickListener {
                    if (i < reorderList.size - 1) {
                        val temp = reorderList[i]
                        reorderList[i] = reorderList[i + 1]
                        reorderList[i + 1] = temp
                        refreshList()
                    }
                }

                clipListContainer.addView(row)
            }
        }

        btnReverse?.setBounceClickListener {
            reorderList.reverse()
            refreshList()
        }

        btnClose?.setBounceClickListener { bottomSheet.dismiss() }
        btnCancel?.setBounceClickListener { bottomSheet.dismiss() }

        btnApply?.setBounceClickListener {
            if (reorderList.isNotEmpty()) {
                val currentFreshItems = getSequenceItems()
                if (currentFreshItems.size != initialItems.size) {
                    Toast.makeText(this@VideoEditingActivity, "Timeline sequence changed, please try again", Toast.LENGTH_SHORT).show()
                    bottomSheet.dismiss()
                    return@setBounceClickListener
                }

                val finalReorderedItems = reorderList.mapNotNull { reorderItem ->
                    if (reorderItem.originalIndex in currentFreshItems.indices) {
                        currentFreshItems[reorderItem.originalIndex]
                    } else null
                }

                if (finalReorderedItems.size == currentFreshItems.size) {
                    viewModel.updateFullSequenceOrder(finalReorderedItems)
                    selectedVideoIndex = null
                    exitVideoEditingMode()
                    seekToGlobalPosition(0L)
                    timelineHorizontalScroll.scrollTo(0, 0)
                    viewModel.project.value?.let { renderTracks(it) }
                    Toast.makeText(this@VideoEditingActivity, "Clips reordered successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@VideoEditingActivity, "Timeline sequence changed, please try again", Toast.LENGTH_SHORT).show()
                }
            }
            bottomSheet.dismiss()
        }

        refreshList()
        bottomSheet.show()
    }

    private fun showAnimationPickerDialog(target: Any) {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_animation_picker_bottom_sheet, null)
        bottomSheet.setContentView(view)

        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseAnimationSheet)
        val tabIn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.tabInAnimation)
        val tabOut = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.tabOutAnimation)
        val presetsList = view.findViewById<LinearLayout>(R.id.animationPresetsList)
        val sliderDuration = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderAnimationDuration)
        val tvDuration = view.findViewById<TextView>(R.id.tvAnimationDurationValue)
        val btnApply = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnApplyAnimation)

        var isPickingIn = true
        var currentInAnim = when (target) {
            is EditOperation.AddImageOverlay -> target.inAnimation
            is EditOperation.AddText -> target.inAnimation
            is EditOperation.MergeItem -> target.inAnimation
            else -> "NONE"
        }
        var currentOutAnim = when (target) {
            is EditOperation.AddImageOverlay -> target.outAnimation
            is EditOperation.AddText -> target.outAnimation
            is EditOperation.MergeItem -> target.outAnimation
            else -> "NONE"
        }
        var currentDurationMs = when (target) {
            is EditOperation.AddImageOverlay -> target.animationDurationMs
            is EditOperation.AddText -> target.animationDurationMs
            is EditOperation.MergeItem -> target.animationDurationMs
            else -> 500L
        }

        sliderDuration?.value = (currentDurationMs / 1000f).coerceIn(0.1f, 2.0f)
        tvDuration?.text = String.format(java.util.Locale.US, "%.1fs", currentDurationMs / 1000f)

        sliderDuration?.addOnChangeListener { _, value, _ ->
            currentDurationMs = (value * 1000).toLong()
            tvDuration?.text = String.format(java.util.Locale.US, "%.1fs", value)
        }

        val presets = listOf(
            Pair("None", "NONE"),
            Pair("Fade", "FADE"),
            Pair("Zoom", "ZOOM"),
            Pair("Slide Left", "SLIDE_LEFT"),
            Pair("Slide Right", "SLIDE_RIGHT"),
            Pair("Slide Up", "SLIDE_UP"),
            Pair("Slide Down", "SLIDE_DOWN")
        )

        fun refreshPresets() {
            presetsList.removeAllViews()
            val activeAnim = if (isPickingIn) currentInAnim else currentOutAnim
            for (preset in presets) {
                val item = layoutInflater.inflate(R.layout.item_animation_preset, presetsList, false)
                val tvName = item.findViewById<TextView>(R.id.tvPresetName)
                val ivIcon = item.findViewById<ImageView>(R.id.ivPresetIcon)
                val container = item.findViewById<LinearLayout>(R.id.presetContainer)

                tvName.text = preset.first
                val isSelected = activeAnim.equals(preset.second, ignoreCase = true)
                if (isSelected) {
                    container.setBackgroundResource(R.drawable.tool_button_active)
                    tvName.setTextColor(getColor(R.color.colorPrimary))
                    ivIcon.setColorFilter(getColor(R.color.colorPrimary))
                } else {
                    container.setBackgroundResource(R.drawable.tool_button_inactive)
                    tvName.setTextColor(getColor(R.color.toolTextInactive))
                    ivIcon.setColorFilter(getColor(R.color.toolTextInactive))
                }

                item.setBounceClickListener {
                    if (isPickingIn) {
                        currentInAnim = preset.second
                    } else {
                        currentOutAnim = preset.second
                    }
                    refreshPresets()
                }
                presetsList.addView(item)
            }
        }

        tabIn.setOnClickListener {
            isPickingIn = true
            tabIn.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPrimary))
            tabIn.setTextColor(Color.WHITE)
            tabOut.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            tabOut.setTextColor(getColor(R.color.toolTextInactive))
            refreshPresets()
        }

        tabOut.setOnClickListener {
            isPickingIn = false
            tabOut.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPrimary))
            tabOut.setTextColor(Color.WHITE)
            tabIn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            tabIn.setTextColor(getColor(R.color.toolTextInactive))
            refreshPresets()
        }

        btnClose.setBounceClickListener { bottomSheet.dismiss() }

        btnApply.setBounceClickListener {
            when (target) {
                is EditOperation.AddImageOverlay -> {
                    viewModel.updateOperation(target.copy(
                        inAnimation = currentInAnim,
                        outAnimation = currentOutAnim,
                        animationDurationMs = currentDurationMs
                    ))
                }
                is EditOperation.AddText -> {
                    viewModel.updateOperation(target.copy(
                        inAnimation = currentInAnim,
                        outAnimation = currentOutAnim,
                        animationDurationMs = currentDurationMs
                    ))
                }
                is Int -> {
                    viewModel.updateMergeItemAnimation(target, currentInAnim, currentOutAnim, currentDurationMs)
                }
            }
            bottomSheet.dismiss()
            viewModel.project.value?.let { renderTracks(it) }
            textOverlayView?.invalidate()
            imageOverlayView?.invalidate()
            Toast.makeText(this, "Animation applied", Toast.LENGTH_SHORT).show()
        }

        refreshPresets()
        bottomSheet.show()
    }

    private fun convertClipToPip() {
        val index = selectedVideoIndex ?: return
        val sequenceItems = getSequenceItems()
        if (index < 0 || index >= sequenceItems.size) return
        if (sequenceItems.size <= 1) {
            Toast.makeText(this, "Cannot convert the only clip to PIP", Toast.LENGTH_SHORT).show()
            return
        }

        val item = sequenceItems[index]
        
        var globalStartMs = 0L
        for (i in 0 until index) {
            globalStartMs += sequenceItems[i].trimmedDurationMs
        }
        val duration = item.trimmedDurationMs
        val globalEndMs = globalStartMs + duration
        
        // Add as AddImageOverlay
        viewModel.addImageOverlayOperation(
            imageUri = item.uri,
            relativeX = 0.5f,
            relativeY = 0.5f,
            relativeWidth = 0.45f,
            relativeHeight = 0.45f,
            rotationAngle = item.rotation.toFloat(),
            startTimeMs = globalStartMs,
            endTimeMs = globalEndMs,
            fileDurationMs = duration,
            isLooping = false,
            chromaKeyColor = item.chromaKeyColor,
            chromaKeySimilarity = item.chromaKeySimilarity,
            opacity = item.opacity,
            isMirrored = item.isMirrored,
            maskConfig = item.maskConfig
        )
        
        // Remove from sequence
        val remaining = sequenceItems.toMutableList()
        remaining.removeAt(index)

        if (index == 0 && remaining.isNotEmpty()) {
            val newPrimary = remaining[0]
            val newSourceFile = File(newPrimary.uri.path ?: newPrimary.uri.toString())
            tempInputFile = newSourceFile
            originalMainVideoDurationMs = newPrimary.durationMs
            videoFileName = tempInputFile.name
            videoUri = newPrimary.uri
        }

        viewModel.updateFullSequenceOrder(remaining)
        
        selectedVideoIndex = null
        exitVideoEditingMode()
        viewModel.project.value?.let { renderTracks(it) }
        Toast.makeText(this, "Converted clip to PIP overlay", Toast.LENGTH_SHORT).show()
    }

    private fun replaceSelectedClip() {
        val index = selectedVideoIndex ?: return
        val sequenceItems = getSequenceItems()
        if (index < 0 || index >= sequenceItems.size) return
        val item = sequenceItems[index]
        
        val picker = com.eclip.rup.leb.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.eclip.rup.leb.customviews.MediaPickerBottomSheet.MediaType.VIDEO
            showCategoryTabs = true
            showAudioTab = false
            onMediaSelectedListener = { uri ->
                lifecycleScope.launch {
                    val extension = withContext(Dispatchers.IO) {
                        getExtensionFromUri(uri) ?: ".mp4"
                    }
                    val tempFile = withContext(Dispatchers.IO) {
                        copyContentUriToTempFile(uri, "replace_clip", extension)
                    }
                    if (tempFile != null) {
                        // Clean up old replaced item resources
                        val oldUri = item.uri
                        activeFrameExtractionJobs.remove(oldUri)?.cancel()
                        activeWaveformJobs.remove(oldUri.toString())?.cancel()
                        activeExtractionUris.remove(oldUri)
                        if (::thumbnailPriorityScheduler.isInitialized) {
                            thumbnailPriorityScheduler.cancelPendingJobsFor(oldUri.toString())
                        }
                        ThumbnailCacheManager.evictForUri(this@VideoEditingActivity, oldUri.toString())
                        AudioWaveformExtractor.evictForVideoPath(oldUri.path ?: oldUri.toString())
                        AppCachePolicyManager.deleteFileIfInCache(this@VideoEditingActivity, oldUri)

                        val localUri = Uri.fromFile(tempFile)
                        val dur = withContext(Dispatchers.IO) {
                            var r: android.media.MediaMetadataRetriever? = null
                            try {
                                r = android.media.MediaMetadataRetriever()
                                r.setDataSource(tempFile.absolutePath)
                                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: item.durationMs
                            } catch (e: Exception) {
                                item.durationMs
                            } finally {
                                try { r?.release() } catch (_: Exception) {}
                            }
                        }
                        
                        val isImg = extension.equals(".png", true) || extension.equals(".jpg", true) || extension.equals(".jpeg", true) || extension.equals(".webp", true)
                        
                        if (index == 0) {
                            tempInputFile = tempFile
                            videoFileName = tempFile.name
                            val updatedItem = item.copy(uri = localUri, durationMs = dur, isImage = isImg)
                            viewModel.updateMainVideoTrim(0L, minOf(item.trimmedDurationMs, dur))
                            val seq = getSequenceItems().toMutableList()
                            seq[0] = updatedItem
                            viewModel.updateFullSequenceOrder(seq)
                        } else {
                            val seq = getSequenceItems().toMutableList()
                            seq[index] = item.copy(uri = localUri, durationMs = dur, isImage = isImg)
                            viewModel.updateSequenceOrder(seq.drop(1))
                        }
                        viewModel.project.value?.let { renderTracks(it) }
                        Toast.makeText(this@VideoEditingActivity, "Clip replaced successfully", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        picker.show(supportFragmentManager, "ReplaceMediaPicker")
    }

    private fun replaceSelectedOverlay() {
        val selectedId = viewModel.selectedOperationId.value ?: return
        val currentProject = viewModel.project.value ?: return
        val op = currentProject.operations.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay ?: return
        
        val picker = com.eclip.rup.leb.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.eclip.rup.leb.customviews.MediaPickerBottomSheet.MediaType.IMAGE
            showCategoryTabs = true
            showAudioTab = false
            onMediaSelectedListener = { uri ->
                lifecycleScope.launch {
                    val extension = withContext(Dispatchers.IO) {
                        getExtensionFromUri(uri) ?: ".png"
                    }
                    val tempFile = withContext(Dispatchers.IO) {
                        copyContentUriToTempFile(uri, "replace_overlay", extension)
                    }
                    if (tempFile != null) {
                        val oldUri = op.imageUri
                        ThumbnailCacheManager.evictForUri(this@VideoEditingActivity, oldUri.toString())
                        AppCachePolicyManager.deleteFileIfInCache(this@VideoEditingActivity, oldUri)

                        val localUri = Uri.fromFile(tempFile)
                        val updatedOp = op.copy(imageUri = localUri)
                        viewModel.updateOperation(updatedOp)
                        draggableImageOverlay?.activateForEdit(updatedOp)
                        viewModel.project.value?.let { renderTracks(it) }
                        Toast.makeText(this@VideoEditingActivity, "Overlay replaced successfully", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        picker.show(supportFragmentManager, "ReplaceOverlayPicker")
    }

    private fun enterAudioEditingMode(op: com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        audioEditingToolbar?.visibility = View.VISIBLE
        hideMainToolbar()
        audioEditingToolbar?.let { toolbar ->
            val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.audioVolumeSlider)
            val tvValue = toolbar.findViewById<TextView>(R.id.tvAudioVolumeValue)
            slider?.value = op.volume.coerceIn(0f, 2f)
            tvValue?.text = "${(op.volume * 100).toInt()}%"
            
            val btnBeats = toolbar.findViewById<ImageButton>(R.id.btnAudioBeats)
            btnBeats?.setColorFilter(if (op.beats.isNotEmpty()) getColor(R.color.colorPrimary) else getColor(R.color.toolTextInactive))

            val btnDucking = toolbar.findViewById<ImageButton>(R.id.btnAudioDucking)
            btnDucking?.setColorFilter(if (op.ducking) getColor(R.color.colorPrimary) else getColor(R.color.toolTextInactive))

            val btnFade = toolbar.findViewById<ImageButton>(R.id.btnAudioFade)
            val isFadeActive = op.fadeInDurationMs > 0 || op.fadeOutDurationMs > 0
            btnFade?.setColorFilter(if (isFadeActive) getColor(R.color.colorPrimary) else getColor(R.color.toolTextInactive))


            val trimTrack = toolbar.findViewById<com.eclip.rup.leb.customviews.TrackTrimView>(R.id.audioTrimTrack)
            val tvTrimValue = toolbar.findViewById<TextView>(R.id.tvAudioTrimValues)
            
            val maxMs = getTotalSequenceDuration().coerceAtLeast(op.originalDurationMs)
            if (op.originalDurationMs > 0) {
                trimTrack?.isMainVideoTrack = false
                trimTrack?.isAudioTrack = true
                trimTrack?.trackColor = android.graphics.Color.TRANSPARENT
                trimTrack?.maxDurationMs = op.originalDurationMs
                val seqDuration = getTotalSequenceDuration()
                trimTrack?.maxSelectionDurationMs = if (seqDuration > 0) seqDuration else null
                
                val trackWidth = resources.displayMetrics.widthPixels - 64.dpToPx()
                trimTrack?.customMsPerPixel = op.originalDurationMs.toFloat() / trackWidth
                
                val endMs = if (op.internalEndMs > 0) op.internalEndMs else op.originalDurationMs
                val initialEndMs = endMs.coerceAtMost(op.internalStartMs + seqDuration)
                trimTrack?.setRange(op.originalDurationMs, op.internalStartMs, initialEndMs)
                
                tvTrimValue?.text = "${formatDuration(op.internalStartMs.toInt())} - ${formatDuration(endMs.toInt())}"
                
                // Extract waveform
                if (trimTrack != null) {
                    val waveJob = lifecycleScope.launch(AppDispatchers.Waveform) {
                        val path = getFilePathFromUri(op.audioUri)
                        if (path != null) {
                            val amps = com.eclip.rup.leb.utils.AudioWaveformExtractor.extractWaveform(this@VideoEditingActivity, path)
                            if (amps != null) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    trimTrack.audioAmplitudes = amps
                                    trimTrack.invalidate()
                                }
                            }
                        }
                    }
                    activeRenderJobs.add(waveJob)
                }
            } else {
                trimTrack?.setRange(maxMs, 0, maxMs)
                tvTrimValue?.text = "Unknown duration"
            }
        }
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitAudioEditingMode() {
        audioEditingToolbar?.visibility = View.GONE
        showMainToolbar()
        viewModel.selectOperation(null)
        
        if (audioPreviewPlayer?.isPlaying == true) {
            try {
                audioPreviewPlayer?.stop()
            } catch (e: Exception) {}
        }
        try {
            audioPreviewPlayer?.release()
        } catch (e: Exception) {}
        audioPreviewPlayer = null
        
        audioEditingToolbar?.findViewById<ImageButton>(R.id.btnAudioPreviewPlay)?.setImageResource(R.drawable.ic_play_24)
        setActiveToolButton(0)
        updateUIInteractionState()
    }

    private fun showFadeDialog(op: com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio) {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_audio_fade, null)
        bottomSheet.setContentView(view)

        val fadeInSlider = view.findViewById<com.google.android.material.slider.Slider>(R.id.fadeInSlider)
        val fadeOutSlider = view.findViewById<com.google.android.material.slider.Slider>(R.id.fadeOutSlider)
        val tvFadeInValue = view.findViewById<TextView>(R.id.tvFadeInValue)
        val tvFadeOutValue = view.findViewById<TextView>(R.id.tvFadeOutValue)
        val btnFadeDone = view.findViewById<android.view.View>(R.id.btnFadeDone)

        val clipDurMs = if (op.endTimeMs != null && op.startTimeMs != null) {
            op.endTimeMs - op.startTimeMs
        } else if (op.internalEndMs > 0L) {
            op.internalEndMs - op.internalStartMs
        } else {
            op.originalDurationMs - op.internalStartMs
        }
        val maxFadeMs = if (clipDurMs > 0) clipDurMs.coerceAtMost(5000L).toFloat() / 1000f else 5.0f

        fadeInSlider.valueTo = if (maxFadeMs >= 0.1f) maxFadeMs else 0.1f
        fadeOutSlider.valueTo = if (maxFadeMs >= 0.1f) maxFadeMs else 0.1f

        val initFadeInSec = (op.fadeInDurationMs / 1000f).coerceIn(0f, fadeInSlider.valueTo)
        val initFadeOutSec = (op.fadeOutDurationMs / 1000f).coerceIn(0f, fadeOutSlider.valueTo)

        fadeInSlider.value = initFadeInSec
        fadeOutSlider.value = initFadeOutSec
        tvFadeInValue.text = String.format(java.util.Locale.US, "%.1fs", initFadeInSec)
        tvFadeOutValue.text = String.format(java.util.Locale.US, "%.1fs", initFadeOutSec)

        fadeInSlider.addOnChangeListener { _, value, _ ->
            tvFadeInValue.text = String.format(java.util.Locale.US, "%.1fs", value)
            if (clipDurMs > 0 && value + fadeOutSlider.value > clipDurMs / 1000f) {
                val newOut = (clipDurMs / 1000f - value).coerceAtLeast(0f).coerceAtMost(fadeOutSlider.valueTo)
                fadeOutSlider.value = newOut
                tvFadeOutValue.text = String.format(java.util.Locale.US, "%.1fs", newOut)
            }
        }

        fadeOutSlider.addOnChangeListener { _, value, _ ->
            tvFadeOutValue.text = String.format(java.util.Locale.US, "%.1fs", value)
            if (clipDurMs > 0 && value + fadeInSlider.value > clipDurMs / 1000f) {
                val newIn = (clipDurMs / 1000f - value).coerceAtLeast(0f).coerceAtMost(fadeInSlider.valueTo)
                fadeInSlider.value = newIn
                tvFadeInValue.text = String.format(java.util.Locale.US, "%.1fs", newIn)
            }
        }

        btnFadeDone.setBounceClickListener {
            val newFadeInMs = (fadeInSlider.value * 1000).toLong()
            val newFadeOutMs = (fadeOutSlider.value * 1000).toLong()
            viewModel.updateOperation(op.copy(fadeInDurationMs = newFadeInMs, fadeOutDurationMs = newFadeOutMs))
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    private fun getPresetCropBounds(aspectRatio: String): android.graphics.RectF {
        val rect = android.graphics.RectF(0f, 0f, 1f, 1f)
        val format = if (::player.isInitialized) player.videoFormat else null
        val videoRatio = if (format != null && format.width > 0 && format.height > 0) {
            val rotation = format.rotationDegrees
            val videoWidth = if (rotation == 90 || rotation == 270) format.height else format.width
            val videoHeight = if (rotation == 90 || rotation == 270) format.width else format.height
            videoWidth.toFloat() / videoHeight
        } else if (primaryVideoAspectRatio > 0f) {
            primaryVideoAspectRatio
        } else {
            16f / 9f
        }
        val targetRatio = when (aspectRatio) {
            "16:9" -> 16f / 9f
            "9:16" -> 9f / 16f
            "1:1" -> 1f
            else -> return rect
        }
        if (videoRatio > targetRatio) {
            val w = targetRatio / videoRatio
            val x = (1f - w) / 2f
            rect.set(x, 0f, x + w, 1f)
        } else {
            val h = videoRatio / targetRatio
            val y = (1f - h) / 2f
            rect.set(0f, y, 1f, y + h)
        }
        return rect
    }

    private fun enterCropEditingMode() {
        closeActiveEditingModes()
        cropEditingToolbar?.visibility = View.VISIBLE
        hideMainToolbar()
        
        val cropOp = viewModel.project.value?.operations
            ?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Crop>()
            ?.lastOrNull()

        initialCropOperation = cropOp
        val currentRatio = cropOp?.aspectRatio ?: "Original"
            
        updateCropUi(currentRatio)
        resetCropPreview()

        if (currentRatio == "Custom" && cropOp != null) {
            cropOverlayView?.setCropBounds(
                cropOp.xFraction,
                cropOp.yFraction,
                cropOp.wFraction,
                cropOp.hFraction
            )
            cropOverlayView?.visibility = View.VISIBLE
        } else {
            val bounds = getPresetCropBounds(currentRatio)
            cropOverlayView?.setCropBounds(bounds.left, bounds.top, bounds.width(), bounds.height())
            cropOverlayView?.visibility = View.GONE
        }
        
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitCropEditingMode() {
        cropEditingToolbar?.visibility = View.GONE
        cropOverlayView?.visibility = View.GONE
        showMainToolbar()
        setActiveToolButton(0)

        val cropOp = viewModel.project.value?.operations
            ?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Crop>()
            ?.lastOrNull()
        if (cropOp != null) {
            applyCropPreview(cropOp.aspectRatio)
        } else {
            resetCropPreview()
        }
        updateUIInteractionState()
    }

    private fun showSpeedEditingToolbar(index: Int, item: com.eclip.rup.leb.models.EditOperation.MergeItem) {
        closeActiveEditingModes()
        speedEditingToolbar?.visibility = View.VISIBLE
        hideMainToolbar()
        
        updateSpeedUi(item.speed)
        
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun hideSpeedEditingToolbar() {
        speedEditingToolbar?.visibility = View.GONE
        if (selectedVideoIndex != null) {
            videoEditingToolbar?.visibility = View.VISIBLE
        } else {
            showMainToolbar()
        }
        updateUIInteractionState()
    }

    val activeLoadingReasons = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val seekSafetyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var seekSafetyRunnable: Runnable? = null

    fun showCentralizedLoading(reason: String = "general") {
        activeLoadingReasons.add(reason)
        if (reason == "seeking") {
            seekSafetyRunnable?.let { seekSafetyHandler.removeCallbacks(it) }
            val runnable = Runnable {
                hideCentralizedLoading("seeking")
            }
            seekSafetyRunnable = runnable
            seekSafetyHandler.postDelayed(runnable, 3500L)
        }
        runOnUiThread {
            updateCentralizedLoaderState()
        }
    }

    fun hideCentralizedLoading(reason: String? = null) {
        if (reason == null) {
            activeLoadingReasons.clear()
            seekSafetyRunnable?.let { seekSafetyHandler.removeCallbacks(it) }
            seekSafetyRunnable = null
        } else {
            activeLoadingReasons.remove(reason)
            if (reason == "seeking") {
                seekSafetyRunnable?.let { seekSafetyHandler.removeCallbacks(it) }
                seekSafetyRunnable = null
            }
        }
        runOnUiThread {
            updateCentralizedLoaderState()
        }
    }

    private fun updateCentralizedLoaderState() {
        val isLoading = activeLoadingReasons.isNotEmpty()
        
        // 1. Centralized Loader on Preview Area only
        if (::previewLoadingOverlay.isInitialized) {
            previewLoadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
            val indicator = previewLoadingOverlay.findViewById<com.eclip.rup.leb.customviews.ThreeDotLoadingView>(R.id.previewThreeDotLoadingIndicator)
            if (isLoading) {
                indicator?.show()
            } else {
                indicator?.hide()
            }
        }

        // 2. Ensure duplicate full-screen loader remains hidden
        if (::loadingScreen.isInitialized) {
            loadingScreen.visibility = View.GONE
            loadingScreen.findViewById<com.eclip.rup.leb.customviews.ThreeDotLoadingView>(R.id.threeDotLoadingIndicator)?.hide()
        }

        // 3. Temporarily lock/unlock all editor interactions and controls
        isEditorLocked = isLoading
        setEditingButtonsEnabled(!isLoading)
    }

    private fun showLoading(message: String? = null, subtitle: String? = null, initialPercent: Int? = null) {
        showCentralizedLoading(message ?: "general")
    }

    private fun updateLoadingProgress(percent: Int) {
        // Ignored for 3-dot lightweight indicator
    }

    private fun hideLoading() {
        hideCentralizedLoading()
    }

    private fun updateSpeedUi(speed: Float) {
        val toolbar = speedEditingToolbar ?: return
        val speeds = mapOf(
            0.5f to Pair(R.id.bgSpeed05, R.id.txtSpeed05),
            1.0f to Pair(R.id.bgSpeed10, R.id.txtSpeed10),
            1.5f to Pair(R.id.bgSpeed15, R.id.txtSpeed15),
            2.0f to Pair(R.id.bgSpeed20, R.id.txtSpeed20)
        )
        
        speeds.forEach { (s, views) ->
            val bg = toolbar.findViewById<View>(views.first)
            val txt = toolbar.findViewById<TextView>(views.second)
            if (kotlin.math.abs(speed - s) < 0.01f) {
                bg?.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                txt?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.colorOnPrimary))
            } else {
                bg?.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                txt?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.toolTextInactive))
            }
        }
        
        val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.sliderCustomSpeed)
        val tvCustomSpeed = toolbar.findViewById<TextView>(R.id.tvCustomSpeedValue)
        
        slider?.let {
            val safeSpeed = speed.coerceIn(it.valueFrom, it.valueTo)
            if (kotlin.math.abs(it.value - safeSpeed) > 0.01f) {
                it.value = safeSpeed
            }
            val roundedValue = String.format(java.util.Locale.US, "%.1f", safeSpeed).toFloat()
            tvCustomSpeed?.text = "${roundedValue}x"
        }
    }

    private fun applySpeedToSegment(speed: Float) {
        val index = selectedVideoIndex ?: return
        val items = getSequenceItems()
        if (index < 0 || index >= items.size) return
        
        val item = items[index]
        if (item.speed == speed) {
            hideSpeedEditingToolbar()
            return
        }
        
        updateSpeedUi(speed)
        
        if (speed == 1.0f) {
            // Reset to normal speed, but preserve reverse if it is reversed!
            if (item.isReversed) {
                lifecycleScope.launch {
                    try {
                        showLoading("Resetting speed...")
                        val outputFileName = "proxy_reverse_${System.currentTimeMillis()}.mp4"
                        val outputFilePath = java.io.File(cacheDir, outputFileName).absolutePath
                        val result = ffmpegEngine.reverseVideo(
                            sourceFilePath = getFilePathFromUri(item.uri) ?: "",
                            startMs = item.trimStartMs,
                            endMs = item.trimEndMs,
                            outputFilePath = outputFilePath
                        )
                        if (result is com.eclip.rup.leb.services.FFmpegRenderEngine.RenderResult.Success) {
                            com.eclip.rup.leb.utils.AppCachePolicyManager.registerActivePath(result.outputPath)
                            val proxyUri = android.net.Uri.fromFile(java.io.File(result.outputPath))
                            if (index == 0) {
                                viewModel.updateMainVideoSpeed(1.0f, null)
                                viewModel.updateMainVideoReverse(isReversed = true, proxyUri = proxyUri)
                            } else {
                                updateVideoSegment(index, item.copy(speed = 1.0f, proxyUri = proxyUri, isReversed = true))
                            }
                            hideSpeedEditingToolbar()
                        } else {
                            android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_apply_speed_reset, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Speed reset error: ${e.message}", e)
                        android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_apply_speed_reset, android.widget.Toast.LENGTH_SHORT).show()
                    } finally {
                        hideLoading()
                        updateUIInteractionState()
                    }
                }
            } else {
                if (index == 0) {
                    viewModel.updateMainVideoSpeed(1.0f, null)
                } else {
                    updateVideoSegment(index, item.copy(speed = 1.0f, proxyUri = null))
                }
                hideSpeedEditingToolbar()
            }
            return
        }

        lifecycleScope.launch {
            try {
                showLoading("Applying speed changes...")
                val outputFileName = "proxy_speed_${System.currentTimeMillis()}.mp4"
                val outputFilePath = java.io.File(cacheDir, outputFileName).absolutePath

                val result = ffmpegEngine.generateSpeedProxy(
                    sourceFilePath = getFilePathFromUri(item.uri) ?: "",
                    startMs = item.trimStartMs,
                    endMs = item.trimEndMs,
                    speed = speed,
                    outputFilePath = outputFilePath
                )

                if (result is com.eclip.rup.leb.services.FFmpegRenderEngine.RenderResult.Success) {
                    com.eclip.rup.leb.utils.AppCachePolicyManager.registerActivePath(result.outputPath)
                    if (item.isReversed) {
                        showLoading("Reversing speed-adjusted video...")
                        val reverseFileName = "proxy_reverse_${System.currentTimeMillis()}.mp4"
                        val reverseFilePath = java.io.File(cacheDir, reverseFileName).absolutePath
                        val speedProxyPath = result.outputPath

                        val durationMs = ((item.trimEndMs - item.trimStartMs) / speed).toLong()
                        val reverseResult = ffmpegEngine.reverseVideo(
                            sourceFilePath = speedProxyPath,
                            startMs = 0L,
                            endMs = durationMs,
                            outputFilePath = reverseFilePath
                        )

                        if (reverseResult is com.eclip.rup.leb.services.FFmpegRenderEngine.RenderResult.Success) {
                            com.eclip.rup.leb.utils.AppCachePolicyManager.registerActivePath(reverseResult.outputPath)
                            val finalProxyUri = android.net.Uri.fromFile(java.io.File(reverseResult.outputPath))
                            if (index == 0) {
                                viewModel.updateMainVideoSpeed(speed, null)
                                viewModel.updateMainVideoReverse(isReversed = true, proxyUri = finalProxyUri)
                            } else {
                                updateVideoSegment(index, item.copy(speed = speed, proxyUri = finalProxyUri, isReversed = true))
                            }
                            hideSpeedEditingToolbar()
                        } else {
                            android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_reverse_speed_proxy, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val proxyUri = android.net.Uri.fromFile(java.io.File(result.outputPath))
                        if (index == 0) {
                            viewModel.updateMainVideoSpeed(speed, proxyUri)
                        } else {
                            updateVideoSegment(index, item.copy(speed = speed, proxyUri = proxyUri))
                        }
                        hideSpeedEditingToolbar()
                    }
                } else {
                    android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_apply_speed, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speed change error: ${e.message}", e)
                android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_apply_speed, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                hideLoading()
                updateUIInteractionState()
            }
        }
    }

    private fun reverseVideoSegment(index: Int, item: com.eclip.rup.leb.models.EditOperation.MergeItem) {
        if (item.isReversed) {
            // Un-reverse the clip
            if (index == 0) {
                if (item.speed == 1.0f) {
                    viewModel.updateMainVideoReverse(isReversed = false, proxyUri = null)
                } else {
                    regenerateSpeedProxyForUnreverse(index, item)
                }
            } else {
                if (item.speed == 1.0f) {
                    updateVideoSegment(index, item.copy(isReversed = false, proxyUri = null))
                } else {
                    regenerateSpeedProxyForUnreverse(index, item)
                }
            }
            updateUIInteractionState()
            return
        }

        lifecycleScope.launch {
            try {
                showLoading("Reversing video...")
                val outputFileName = "proxy_reverse_${System.currentTimeMillis()}.mp4"
                val outputFilePath = java.io.File(cacheDir, outputFileName).absolutePath

                val sourceFilePath: String
                val startMs: Long
                val endMs: Long

                if (item.proxyUri != null) {
                    sourceFilePath = getFilePathFromUri(item.proxyUri) ?: ""
                    startMs = 0L
                    endMs = item.trimmedDurationMs
                } else {
                    sourceFilePath = getFilePathFromUri(item.uri) ?: ""
                    startMs = item.trimStartMs
                    endMs = item.trimEndMs
                }

                val result = ffmpegEngine.reverseVideo(
                    sourceFilePath = sourceFilePath,
                    startMs = startMs,
                    endMs = endMs,
                    outputFilePath = outputFilePath
                )

                if (result is com.eclip.rup.leb.services.FFmpegRenderEngine.RenderResult.Success) {
                    com.eclip.rup.leb.utils.AppCachePolicyManager.registerActivePath(result.outputPath)
                    val reversedProxyUri = android.net.Uri.fromFile(java.io.File(result.outputPath))
                    if (index == 0) {
                        viewModel.updateMainVideoReverse(isReversed = true, proxyUri = reversedProxyUri)
                    } else {
                        updateVideoSegment(index, item.copy(isReversed = true, proxyUri = reversedProxyUri))
                    }
                } else {
                    android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_reverse_video, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reverse video error: ${e.message}", e)
                android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_reverse_video, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                hideLoading()
                updateUIInteractionState()
            }
        }
    }

    private fun regenerateSpeedProxyForUnreverse(index: Int, item: com.eclip.rup.leb.models.EditOperation.MergeItem) {
        lifecycleScope.launch {
            try {
                showLoading("Restoring forward video...")
                val outputFileName = "proxy_speed_${System.currentTimeMillis()}.mp4"
                val outputFilePath = java.io.File(cacheDir, outputFileName).absolutePath

                val result = ffmpegEngine.generateSpeedProxy(
                    sourceFilePath = getFilePathFromUri(item.uri) ?: "",
                    startMs = item.trimStartMs,
                    endMs = item.trimEndMs,
                    speed = item.speed,
                    outputFilePath = outputFilePath
                )

                if (result is com.eclip.rup.leb.services.FFmpegRenderEngine.RenderResult.Success) {
                    com.eclip.rup.leb.utils.AppCachePolicyManager.registerActivePath(result.outputPath)
                    val proxyUri = android.net.Uri.fromFile(java.io.File(result.outputPath))
                    if (index == 0) {
                        viewModel.updateMainVideoSpeed(item.speed, proxyUri)
                        viewModel.updateMainVideoReverse(isReversed = false, proxyUri = null)
                    } else {
                        updateVideoSegment(index, item.copy(isReversed = false, speed = item.speed, proxyUri = proxyUri))
                    }
                } else {
                    android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_restore_video_speed, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Restore forward video error: ${e.message}", e)
                android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_restore_video_speed, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                hideLoading()
                updateUIInteractionState()
            }
        }
    }
    
    
    private fun updateVideoSegment(index: Int, newItem: com.eclip.rup.leb.models.EditOperation.MergeItem) {
        val project = viewModel.project.value ?: return
        val currentMergeOp = project.operations.find { it is com.eclip.rup.leb.models.EditOperation.Merge } as? com.eclip.rup.leb.models.EditOperation.Merge ?: return
        val updatedItems = currentMergeOp.items.toMutableList()
        updatedItems[index - 1] = newItem
        val newMergeOp = currentMergeOp.copy(items = updatedItems)
        viewModel.updateOperation(newMergeOp)
        viewModel.project.value?.let { renderTracks(it) }
    }
    
    private fun updateCanvasBackgroundPreview(activeClipIndex: Int = lastActiveClipIndexForBlur) {
        val project = viewModel.project.value ?: return
        val bgOp = project.operations.filterIsInstance<EditOperation.CanvasBackground>().lastOrNull()
        
        bgPreviewImageView?.setImageBitmap(null)
        bgPreviewImageView?.background = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bgPreviewImageView?.setRenderEffect(null)
        }
        
        if (bgOp == null) {
            bgPreviewImageView?.setBackgroundColor(android.graphics.Color.BLACK)
            return
        }
        
        when (bgOp.type) {
            EditOperation.CanvasBackground.BackgroundType.COLOR -> {
                try {
                    val color = android.graphics.Color.parseColor(bgOp.colorHex)
                    bgPreviewImageView?.setBackgroundColor(color)
                } catch (e: Exception) {
                    bgPreviewImageView?.setBackgroundColor(android.graphics.Color.BLACK)
                }
            }
            EditOperation.CanvasBackground.BackgroundType.BLUR -> {
                val clipUri = getSequenceItems().getOrNull(activeClipIndex)?.uri ?: return
                currentBlurJob?.cancel()
                currentBlurJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val path = getFilePathFromUri(clipUri)
                        if (path != null) {
                            var retriever: android.media.MediaMetadataRetriever? = null
                            val bitmap = try {
                                retriever = android.media.MediaMetadataRetriever()
                                retriever.setDataSource(path)
                                com.eclip.rup.leb.utils.CodecInstancePool.withHardwareCodecPermit {
                                    retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                }
                            } finally {
                                try { retriever?.release() } catch (_: Exception) {}
                            }
                            
                            if (bitmap != null) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    bgPreviewImageView?.setImageBitmap(bitmap)
                                    bgPreviewImageView?.scaleType = ImageView.ScaleType.CENTER_CROP
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val radius = bgOp.blurRadius.toFloat()
                                        bgPreviewImageView?.setRenderEffect(
                                            android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            EditOperation.CanvasBackground.BackgroundType.IMAGE -> {
                bgOp.imageUri?.let { uri ->
                    try {
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                            android.graphics.ImageDecoder.decodeBitmap(source)
                        } else {
                            @Suppress("DEPRECATION")
                            android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
                        }
                        bgPreviewImageView?.setImageBitmap(bitmap)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun updateCropUi(ratio: String) {
        val toolbar = cropEditingToolbar ?: return
        val ratios = mapOf(
            "16:9" to Triple(R.id.bg16_9, R.id.ic16_9, R.id.txt16_9),
            "9:16" to Triple(R.id.bg9_16, R.id.ic9_16, R.id.txt9_16),
            "1:1" to Triple(R.id.bg1_1, R.id.ic1_1, R.id.txt1_1),
            "Custom" to Triple(R.id.bgCustom, R.id.icCustom, R.id.txtCustom)
        )

        ratios.forEach { (key, views) ->
            val isActive = key == ratio
            toolbar.findViewById<View>(views.first)?.setBackgroundResource(
                if (isActive) R.drawable.bg_aspect_ratio_selected else R.drawable.bg_aspect_ratio_item
            )
            toolbar.findViewById<ImageView>(views.second)?.setColorFilter(
                resources.getColor(if (isActive) R.color.onPrimaryContainer else R.color.iconSecondary, null)
            )
            toolbar.findViewById<TextView>(views.third)?.apply {
                setTextColor(resources.getColor(if (isActive) R.color.colorOnPrimary else R.color.toolTextInactive, null))
                paint.isFakeBoldText = isActive
            }
        }

        cropOverlayView?.visibility = if (ratio == "Custom") View.VISIBLE else View.GONE
    }

    private fun splitSelectedVideo() {
        val index = selectedVideoIndex ?: return
        val globalPos = getGlobalPosition()
        
        val sequenceItems = getSequenceItems()
        if (index < 0 || index >= sequenceItems.size) return
        
        val item = sequenceItems[index]
        
        // Calculate the clip's starting global position
        var clipStartGlobal = 0L
        for (i in 0 until index) {
            clipStartGlobal += sequenceItems[i].trimmedDurationMs
        }
        val clipEndGlobal = clipStartGlobal + item.trimmedDurationMs
        
        // Check if the seeker is inside this clip
        if (globalPos <= clipStartGlobal || globalPos >= clipEndGlobal) {
            Toast.makeText(this, R.string.toast_seeker_is_not_over_the_selecte, Toast.LENGTH_SHORT).show()
            return
        }
        
        // Calculate the local split time inside the clip's own timeline
        val localOffset = globalPos - clipStartGlobal
        val localSplitTimeMs = item.trimStartMs + localOffset
        
        viewModel.splitVideoSegment(index, localSplitTimeMs, item.uri, item.durationMs)
        selectedVideoIndex = null
        exitVideoEditingMode()
    }
    
    private fun clearProjectAndShowImportScreen() {
        if (::player.isInitialized) {
            if (player.isPlaying) player.pause()
            player.clearMediaItems()
        }
        isVideoLoaded = false
        videoUri = null
        activeFrameExtractionJobs.values.forEach { it.cancel() }
        activeFrameExtractionJobs.clear()
        activeExtractionUris.clear()
        activeRenderJobs.forEach { it.cancel() }
        activeRenderJobs.clear()
        viewModel.clearAllOperations() // wait, no initialize is better
        
        // Keep timeline container visible, but hide its normal contents
        timelineContainer.visibility = View.VISIBLE
        playerContainer.visibility = View.INVISIBLE
        findViewById<View>(R.id.seekerContainer).visibility = View.VISIBLE
        findViewById<View>(R.id.editingControlsScroll).visibility = View.INVISIBLE
        
        // Hide normal timeline elements inside the container
        findViewById<View>(R.id.timelineHorizontalScroll)?.visibility = View.INVISIBLE
        findViewById<View>(R.id.btnTimelineAdd)?.visibility = View.INVISIBLE
        findViewById<View>(R.id.customVideoSeeker)?.visibility = View.INVISIBLE
        
        // Hide toolbars
        exitVideoEditingMode()
        exitAudioEditingMode()
        exitImageEditingMode()
        exitTextEditingMode()
        
        // Show empty state
        findViewById<View>(R.id.emptyProjectState)?.visibility = View.VISIBLE
        setEditingButtonsEnabled(false)
        
        // Ensure loading screen is hidden and reset
        isImportLoading = false
        hideCentralizedLoading()
    }

    private fun setEditingButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.5f
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.isEnabled = enabled
            btnPlayPause.alpha = alpha
        }
        findViewById<View>(R.id.btnMagnet)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        findViewById<View>(R.id.btnMute)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        findViewById<View>(R.id.btnCaptureFrame)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        findViewById<View>(R.id.layoutSaveSplit)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        if (::timeRulerView.isInitialized) {
            timeRulerView.isEnabled = enabled
        }
        if (::customVideoSeeker.isInitialized) {
            customVideoSeeker.isEnabled = enabled
        }
        if (::timelineHorizontalScroll.isInitialized) {
            timelineHorizontalScroll.isEnabled = enabled
        }
        if (::btnUndo.isInitialized) {
            val canUndo = viewModel.uiState.value?.canUndo == true
            btnUndo.isEnabled = enabled && canUndo
            btnUndo.alpha = if (enabled && canUndo) 1.0f else 0.5f
        }
        if (::btnRedo.isInitialized) {
            val canRedo = viewModel.uiState.value?.canRedo == true
            btnRedo.isEnabled = enabled && canRedo
            btnRedo.alpha = if (enabled && canRedo) 1.0f else 0.5f
        }
        updateUIInteractionState()
    }

    private fun deleteSelectedVideo() {
        val index = selectedVideoIndex ?: return
        val items = getSequenceItems()
        
        if (items.size <= 1) {
            clearProjectAndShowImportScreen()
            return
        }

        val deletingItem = items.getOrNull(index)
        if (deletingItem != null) {
            val deletingUri = deletingItem.uri
            val isUriStillInUse = items.filterIndexed { i, _ -> i != index }.any { it.uri == deletingUri }
            if (!isUriStillInUse) {
                activeFrameExtractionJobs.remove(deletingUri)?.cancel()
                activeWaveformJobs.remove(deletingUri.toString())?.cancel()
                activeExtractionUris.remove(deletingUri)
                if (::thumbnailPriorityScheduler.isInitialized) {
                    thumbnailPriorityScheduler.cancelPendingJobsFor(deletingUri.toString())
                }
                ThumbnailCacheManager.evictForUri(this, deletingUri.toString())
                AudioWaveformExtractor.evictForVideoPath(deletingUri.path ?: deletingUri.toString())
                AppCachePolicyManager.deleteFileIfInCache(this, deletingUri)
            }
        }
        
        if (index == 0) {
            val nextItem = items[1]
            val newSourceFile = File(nextItem.uri.path ?: nextItem.uri.toString())
            tempInputFile = newSourceFile
            originalMainVideoDurationMs = nextItem.durationMs
            videoFileName = tempInputFile.name
            videoUri = nextItem.uri
        }

        viewModel.deleteSequenceSegment(index)
        selectedVideoIndex = null
        exitVideoEditingMode()
    }

    private fun extractAudioFromSegment(index: Int, item: com.eclip.rup.leb.models.EditOperation.MergeItem) {
        lifecycleScope.launch {
            try {
                showLoading("Extracting audio...")
                val ffmpegEngine = com.eclip.rup.leb.services.FFmpegRenderEngine(this@VideoEditingActivity)
                
                // Generate a unique output file path
                val outputFileName = "extracted_audio_${System.currentTimeMillis()}.m4a"
                val assetsDir = DraftManager.getProjectAssetsDirectory(this@VideoEditingActivity)
                val outputFilePath = File(assetsDir, outputFileName).absolutePath
                val sourceFilePath = item.uri.path ?: item.uri.toString()

                val result = withContext(Dispatchers.IO) {
                    ffmpegEngine.extractAudio(sourceFilePath, outputFilePath)
                }

                if (result is com.eclip.rup.leb.services.FFmpegRenderEngine.RenderResult.Success) {
                    val audioFile = File(outputFilePath)
                    AppCachePolicyManager.registerActiveFile(audioFile)
                    val sequenceItems = getSequenceItems()
                    var clipStartGlobal = 0L
                    for (i in 0 until index) {
                        clipStartGlobal += sequenceItems[i].trimmedDurationMs
                    }
                    val clipEndGlobal = clipStartGlobal + item.trimmedDurationMs

                    val tempUri = Uri.fromFile(audioFile)
                    viewModel.addOperation(
                        com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio(
                            audioUri = tempUri,
                            internalStartMs = item.trimStartMs,
                            internalEndMs = item.trimEndMs,
                            startTimeMs = clipStartGlobal,
                            endTimeMs = clipEndGlobal,
                            originalDurationMs = item.durationMs,
                            extractedFromSegmentIndex = index
                        )
                    )
                    Toast.makeText(this@VideoEditingActivity, R.string.toast_audio_extracted_to_new_layer, Toast.LENGTH_SHORT).show()
                } else if (result is com.eclip.rup.leb.services.FFmpegRenderEngine.RenderResult.Failure) {
                    val userMsg = result.userMessage ?: getString(R.string.toast_failed_to_extract_audio)
                    Toast.makeText(this@VideoEditingActivity, userMsg, Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Audio extraction failed: ${result.error}")
                }
                
                selectedVideoIndex = null
                exitVideoEditingMode()
            } catch (e: Exception) {
                Log.e(TAG, "Audio extraction error: ${e.message}", e)
                Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_extract_audio, Toast.LENGTH_SHORT).show()
            } finally {
                hideLoading()
                updateUIInteractionState()
            }
        }
    }

    private fun mergeAction() {
        openFilePickerMerge()
    }

    private fun openFilePickerMerge() {
        val picker = com.eclip.rup.leb.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.eclip.rup.leb.customviews.MediaPickerBottomSheet.MediaType.VIDEO
            showCategoryTabs = true
            showAudioTab = false
            onClipSelectedListener = { clip ->
                processMergeClips(listOf(clip))
            }
            onMultipleClipsSelectedListener = { clips ->
                processMergeClips(clips)
            }
            onMediaSelectedListener = { uri ->
                processMergeVideoUris(listOf(uri))
            }
            onMultipleMediaSelectedListener = { uris ->
                processMergeVideoUris(uris)
            }
            onBrowseSystemFoldersRequested = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try {
                    pickVideoLauncher.launch(Intent.createChooser(intent, "Select Media"))
                } catch (e: android.content.ActivityNotFoundException) {
                    try {
                        pickVideoLauncher.launch(intent)
                    } catch (e2: android.content.ActivityNotFoundException) {
                        Toast.makeText(this@VideoEditingActivity, "No app found to handle this action", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        picker.show(supportFragmentManager, "MediaPickerBottomSheet")
    }

    private fun processMergeClips(clips: List<TimelineClip>) {
        if (clips.isEmpty()) return

        val mergeItems = clips.map { clip ->
            viewModel.updateClipState(clip.uri.toString(), com.eclip.rup.leb.models.ClipState.PENDING)
            com.eclip.rup.leb.models.EditOperation.MergeItem(
                uri = clip.uri,
                durationMs = clip.durationMs,
                trimStartMs = 0L,
                trimEndMs = clip.durationMs,
                isImage = clip.isImage || isImageUri(clip.uri)
            )
        }

        viewModel.addMergeOperation(mergeItems)
        viewModel.project.value?.let { renderTracks(it) }

        val totalDuration = getTotalSequenceDuration()
        if (totalDuration > 0L) {
            updateDurationDisplay(getGlobalPosition().toInt(), totalDuration.toInt())
            if (::customVideoSeeker.isInitialized) {
                customVideoSeeker.setVideoDuration(totalDuration)
            }
            if (::timeRulerView.isInitialized) {
                timeRulerView.setVideoDuration(totalDuration)
            }
        }

        Toast.makeText(this@VideoEditingActivity, "${mergeItems.size} clip(s) added to sequence", Toast.LENGTH_SHORT).show()

        clips.forEach { clip ->
            requiredClipUris.add(clip.uri.toString())
            startBackgroundClipProcessing(clip.uri, isMain = false)
        }
    }

    private fun processMergeVideoUris(selectedVideoUris: List<Uri>) {
        if (selectedVideoUris.isEmpty()) return
        
        val mergeItems = selectedVideoUris.map { uri ->
            viewModel.updateClipState(uri.toString(), com.eclip.rup.leb.models.ClipState.PENDING)
            val fastDur = extractMediaDurationFast(uri).takeIf { it > 0L } ?: 5000L
            com.eclip.rup.leb.models.EditOperation.MergeItem(
                uri = uri,
                durationMs = fastDur,
                trimStartMs = 0L,
                trimEndMs = fastDur,
                isImage = isImageUri(uri)
            )
        }
        
        viewModel.addMergeOperation(mergeItems)
        viewModel.project.value?.let { renderTracks(it) }
        
        val totalDuration = getTotalSequenceDuration()
        if (totalDuration > 0L) {
            updateDurationDisplay(getGlobalPosition().toInt(), totalDuration.toInt())
            if (::customVideoSeeker.isInitialized) {
                customVideoSeeker.setVideoDuration(totalDuration)
            }
            if (::timeRulerView.isInitialized) {
                timeRulerView.setVideoDuration(totalDuration)
            }
        }
        
        Toast.makeText(this@VideoEditingActivity, "${mergeItems.size} clip(s) added to sequence", Toast.LENGTH_SHORT).show()
        
        selectedVideoUris.forEach { uri ->
            requiredClipUris.add(uri.toString())
            startBackgroundClipProcessing(uri, isMain = false)
        }
    }

    private fun handleMainSelectedVideo(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take persistable permission", e)
        }
        emptyProjectState.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
        timelineContainer.visibility = View.VISIBLE
        findViewById<View>(R.id.seekerContainer).visibility = View.VISIBLE
        findViewById<View>(R.id.editingControlsScroll).visibility = View.VISIBLE
        
        // Restore timeline children visibility
        findViewById<View>(R.id.timelineHorizontalScroll)?.visibility = View.VISIBLE
        findViewById<View>(R.id.btnTimelineAdd)?.visibility = View.VISIBLE
        findViewById<View>(R.id.customVideoSeeker)?.visibility = View.VISIBLE
        
        requiredClipUris.add(uri.toString())
        transitionToReadinessState(EditorReadinessState.PROCESSING)
        isVideoLoaded = false
        videoUri = uri
        
        val fastDur = extractMediaDurationFast(uri).takeIf { it > 0L } ?: 5000L
        originalMainVideoDurationMs = fastDur
        
        val displayName = uri.lastPathSegment ?: "video"
        viewModel.initializeProject(uri, displayName)
        viewModel.updateMainVideoTrim(0L, fastDur)
        initializeVideoData()
    }

    private fun openFilePickerMain() {
        val picker = com.eclip.rup.leb.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.eclip.rup.leb.customviews.MediaPickerBottomSheet.MediaType.VIDEO
            showCategoryTabs = true
            showAudioTab = false
            onMediaSelectedListener = { uri ->
                handleMainSelectedVideo(uri)
            }
            onMultipleMediaSelectedListener = { uris ->
                if (uris.isNotEmpty()) {
                    handleMainSelectedVideo(uris.first())
                    if (uris.size > 1) {
                        processMergeVideoUris(uris.drop(1))
                    }
                }
            }
            onBrowseSystemFoldersRequested = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
                }
                mainFilePickerLauncher.launch(intent)
            }
        }
        picker.show(supportFragmentManager, "MediaPickerBottomSheet")
    }

    private val mainFilePickerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleMainSelectedVideo(uri)
            }
        }
    }

    private val pickVideoLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                val selectedVideoUris = mutableListOf<Uri>()
                if (data.clipData != null) {
                    val itemCount = data.clipData!!.itemCount
                    for (i in 0 until itemCount) {
                        selectedVideoUris.add(data.clipData!!.getItemAt(i).uri)
                    }
                } else {
                    data.data?.let { uri -> selectedVideoUris.add(uri) }
                }
                processMergeVideoUris(selectedVideoUris)
            }
        }
    }

    private val pickAudioLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { audioUri ->
                processAudioSelected(audioUri)
            }
        }
    }

    private val pickImageOverlayLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { imageUri ->
                showImageOverlayConfig(imageUri)
            }
        }
    }

    private val pickBackgroundImageLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { bgUri ->
                processBackgroundImageSelected(bgUri)
            }
        }
    }

    private val pickDirectoryLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        if (treeUri != null) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(treeUri, takeFlags)

                val sharedPreferences = getSharedPreferences("eclip_prefs", Context.MODE_PRIVATE)
                val isAudioOnly = viewModel.exportAudioOnly.value
                val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
                sharedPreferences.edit().putString(prefKey, treeUri.toString()).apply()

                val activeTitle = activeDirectoryTitleView
                val activePath = activeDirectoryPathView
                if (activeTitle != null && activePath != null) {
                    updateExportDirectoryUi(activeTitle, activePath)
                }
                Toast.makeText(this, R.string.toast_export_location_updated_succes, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving export directory: ${e.message}", e)
                Toast.makeText(this, R.string.toast_failed_to_select_folder, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun audioAction() {
        val picker = com.eclip.rup.leb.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.eclip.rup.leb.customviews.MediaPickerBottomSheet.MediaType.AUDIO
            showCategoryTabs = false
            onMediaSelectedListener = { uri ->
                processAudioSelected(uri)
            }
            onBrowseSystemFoldersRequested = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "audio/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    pickAudioLauncher.launch(Intent.createChooser(intent, "Select Audio"))
                } catch (e: android.content.ActivityNotFoundException) {
                    try {
                        pickAudioLauncher.launch(intent)
                    } catch (e2: android.content.ActivityNotFoundException) {
                        Toast.makeText(this@VideoEditingActivity, "No app found to handle this action", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        picker.show(supportFragmentManager, "MediaPickerBottomSheet")
    }

    private fun processAudioSelected(audioUri: Uri) {
        lifecycleScope.launch {
            val tempFile = withContext(Dispatchers.IO) { copyContentUriToTempFile(audioUri, "audio", ".m4a") }
            if (tempFile != null) {
                val tempUri = Uri.fromFile(tempFile)
                val realDurationMs = withContext(Dispatchers.IO) {
                    try {
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            r.setDataSource(this@VideoEditingActivity, tempUri)
                            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } finally {
                            try {
                                r.release()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error releasing MediaMetadataRetriever: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error extracting audio duration: ${e.message}", e)
                        0L
                    }
                }
                val totalSeqDur = getTotalSequenceDuration()
                val endGlobalMs = if (realDurationMs > 0) minOf(totalSeqDur, realDurationMs) else totalSeqDur
                viewModel.addOperation(
                    com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio(
                        audioUri = tempUri,
                        internalStartMs = 0L,
                        internalEndMs = realDurationMs,
                        startTimeMs = 0L,
                        endTimeMs = endGlobalMs,
                        originalDurationMs = realDurationMs
                    )
                )
                Toast.makeText(this@VideoEditingActivity, R.string.toast_audio_track_added, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * SAVE/EXPORT: Consolidates all pending operations into a single FFmpeg command and runs it.
     *
     * KEY FIX: fontFilePath (populated in onCreate from assets/fonts/Roboto-Regular.ttf)
     * is now passed to buildConsolidatedFFmpegCommand so drawtext gets a valid fontfile= path.
     */
    /**
     * SAVE/EXPORT FLOW: Consolidates pending operations into FFmpeg command,
     * starts ExportService, and launches the dedicated ExportActivity screen.
     */
    private fun saveAction() {
        startExportFlow()
    }

    private fun startExportFlow() {
        commitActiveEditsIfAny()
        if (isShowingPreview) dismissPreview()
        val project = viewModel.project.value
        if (project == null) {
            showError("No project loaded")
            return
        }

        // Warn but don't block — export will just skip text rendering if font is missing
        if (fontFilePath == null) {
            Log.w(TAG, "fontFilePath is null — text overlays will be skipped. " +
                    "Check that assets/fonts/Roboto-Regular.ttf exists.")
        }

        lifecycleScope.launch(Dispatchers.Main) {
            var tempOutputFile: File? = null
            var concatFile: File? = null
            try {
                val sourceFilePath = tempInputFile.absolutePath
                val isAudioOnly = viewModel.exportAudioOnly.value
                val ext = if (isAudioOnly) ".mp3" else ".mp4"
                tempOutputFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}$ext")
                val tempOutputPath = tempOutputFile.absolutePath

                var ffmpegCommand = withContext(Dispatchers.IO) {
                    viewModel.buildConsolidatedFFmpegCommand(
                        sourceFilePath = sourceFilePath,
                        outputFilePath = tempOutputPath,
                        fontFilePath = fontFilePath,
                        context = this@VideoEditingActivity
                    )
                }

                if (ffmpegCommand == null) {
                    showError("Failed to build FFmpeg command")
                    updateUIInteractionState()
                    return@launch
                }

                Log.d(TAG, "Raw FFmpeg command: $ffmpegCommand")

                val currentProject = viewModel.project.value
                if (currentProject != null && ffmpegCommand.contains("(CONCAT_LIST:")) {
                    val cmdSnapshot = ffmpegCommand
                    concatFile = withContext(Dispatchers.IO) {
                        val concatListStart = cmdSnapshot.indexOf("(CONCAT_LIST:") + "(CONCAT_LIST:".length
                        val concatListEnd = cmdSnapshot.lastIndexOf(")")
                        if (concatListStart > 13 && concatListEnd > concatListStart) {
                            val concatList = cmdSnapshot.substring(concatListStart, concatListEnd)
                            val processedConcatList = processConcatList(concatList)

                            val file = File(cacheDir, "concat_${System.currentTimeMillis()}.txt")
                            file.writeText(processedConcatList)
                            file
                        } else null
                    }

                    if (concatFile != null) {
                        ffmpegCommand = ffmpegCommand
                            .replace("{CONCAT_FILE_PATH}", concatFile.absolutePath)
                            .substring(0, ffmpegCommand.indexOf("(CONCAT_LIST:"))
                            .trim()
                    }
                }

                Log.d(TAG, "Final FFmpeg command: $ffmpegCommand")
                val totalDurationSecs = getTotalSequenceDuration() / 1000.0

                // Launch ExportActivity with all parameters (foreground-only export)
                val exportActivityIntent = Intent(this@VideoEditingActivity, ExportActivity::class.java).apply {
                    putExtra(ExportActivity.EXTRA_SOURCE_URI, videoUri?.toString())
                    putExtra(ExportActivity.EXTRA_PROJECT_NAME, project.sourceName)
                    putExtra(ExportActivity.EXTRA_RESOLUTION, viewModel.exportResolution.value)
                    putExtra(ExportActivity.EXTRA_FPS, viewModel.exportFps.value)
                    putExtra(ExportActivity.EXTRA_IS_AUDIO_ONLY, isAudioOnly)
                    putExtra(ExportActivity.EXTRA_COMMAND, ffmpegCommand)
                    putExtra(ExportActivity.EXTRA_TEMP_OUTPUT_PATH, tempOutputPath)
                    putExtra(ExportActivity.EXTRA_CONCAT_FILE_PATH, concatFile?.absolutePath)
                    putExtra(ExportActivity.EXTRA_TOTAL_DURATION_SECS, totalDurationSecs)
                }
                startActivity(exportActivityIntent)

            } catch (e: Exception) {
                tempOutputFile?.let { if (it.exists()) it.delete() }
                concatFile?.let { if (it.exists()) it.delete() }
                showError(e.message ?: "Unknown error")
                Log.e(TAG, "Export start exception: ${e.message}", e)
            } finally {
                updateUIInteractionState()
            }
        }
    }

    private fun showQualitySettingsDialog() {
        commitActiveEditsIfAny()
        if (isShowingPreview) dismissPreview()
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.export_quality_bottom_sheet_dialog, null)

        val cgResolution = sheetView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.cgResolution)
        val cgFps = sheetView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.cgFps)
        val switchAudioOnly = sheetView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchAudioOnly)
        val btnClose = sheetView.findViewById<ImageButton>(R.id.btnCloseSheet)

        // Initialize state
        val currentRes = viewModel.exportResolution.value
        val currentFps = viewModel.exportFps.value
        val isAudioOnly = viewModel.exportAudioOnly.value

        when (currentRes) {
            360 -> cgResolution.check(R.id.chipRes360)
            480 -> cgResolution.check(R.id.chipRes480)
            720 -> cgResolution.check(R.id.chipRes720)
            else -> cgResolution.check(R.id.chipRes1080)
        }

        when (currentFps) {
            24 -> cgFps.check(R.id.chipFps24)
            25 -> cgFps.check(R.id.chipFps25)
            30 -> cgFps.check(R.id.chipFps30)
            50 -> cgFps.check(R.id.chipFps50)
            60 -> cgFps.check(R.id.chipFps60)
            else -> cgFps.check(R.id.chipFps30)
        }

        switchAudioOnly.isChecked = isAudioOnly

        val layoutExportDirectory = sheetView.findViewById<LinearLayout>(R.id.layoutExportDirectory)
        val tvExportDirectoryTitle = sheetView.findViewById<TextView>(R.id.tvExportDirectoryTitle)
        val tvExportDirectoryPath = sheetView.findViewById<TextView>(R.id.tvExportDirectoryPath)

        activeDirectoryTitleView = tvExportDirectoryTitle
        activeDirectoryPathView = tvExportDirectoryPath
        updateExportDirectoryUi(tvExportDirectoryTitle, tvExportDirectoryPath)

        fun saveSettings() {
            val res = when (cgResolution.checkedChipId) {
                R.id.chipRes360 -> 360
                R.id.chipRes480 -> 480
                R.id.chipRes720 -> 720
                else -> 1080
            }
            val fps = when (cgFps.checkedChipId) {
                R.id.chipFps24 -> 24
                R.id.chipFps25 -> 25
                R.id.chipFps30 -> 30
                R.id.chipFps50 -> 50
                R.id.chipFps60 -> 60
                else -> 30
            }
            viewModel.setExportSettings(res, fps, switchAudioOnly.isChecked)
            updateExportDirectoryUi(tvExportDirectoryTitle, tvExportDirectoryPath)
        }

        cgResolution.setOnCheckedStateChangeListener { _, _ -> saveSettings() }
        cgFps.setOnCheckedStateChangeListener { _, _ -> saveSettings() }
        switchAudioOnly.setOnCheckedChangeListener { _, _ -> saveSettings() }

        btnClose.setBounceClickListener {
            bottomSheetDialog.dismiss()
        }

        layoutExportDirectory.setBounceClickListener {
            try {
                pickDirectoryLauncher.launch(null)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.toast_failed_to_select_folder, Toast.LENGTH_SHORT).show()
            }
        }

        bottomSheetDialog.setOnDismissListener {
            activeDirectoryTitleView = null
            activeDirectoryPathView = null
        }

        sheetView.findViewById<View>(R.id.btnSaveProject)?.setBounceClickListener {
            bottomSheetDialog.dismiss()
            startExportFlow()
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    private fun cancelExport() {
        lifecycleScope.launch {
            try {
                ffmpegEngine.cancelAllSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling FFmpeg sessions: ${e.message}")
            }
        }
        exportJob?.cancel()
        viewModel.exportError("Export cancelled")
        Toast.makeText(this, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
    }

    private fun updateExportDirectoryUi(titleView: TextView, pathView: TextView) {
        val sharedPreferences = getSharedPreferences("eclip_prefs", Context.MODE_PRIVATE)
        val isAudioOnly = viewModel.exportAudioOnly.value
        val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
        val customUriString = sharedPreferences.getString(prefKey, null)
        
        if (customUriString != null) {
            val customUri = Uri.parse(customUriString)
            val displayName = getDocumentFolderName(customUri) ?: "Custom Folder"
            titleView.text = displayName
            pathView.text = customUri.path ?: customUriString
        } else {
            titleView.text = "Eclip (Default)"
            pathView.text = if (isAudioOnly) "Music/Eclip" else "Movies/Eclip"
        }
    }

    private fun getDocumentFolderName(uri: Uri): String? {
        return try {
            val documentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                uri,
                android.provider.DocumentsContract.getTreeDocumentId(uri)
            )
            contentResolver.query(documentUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun setupExoPlayer() {
        val projectUri = intent.getParcelableExtra<Uri>("PROJECT_URI")
        currentDraftId = intent.getStringExtra("DRAFT_ID") ?: ""

        if (projectUri != null) {
            if (currentDraftId.isBlank()) {
                val lastSeg = projectUri.lastPathSegment?.removeSuffix(".json")
                if (!lastSeg.isNullOrBlank()) {
                    currentDraftId = lastSeg
                } else {
                    currentDraftId = com.eclip.rup.leb.utils.DraftManager.generateUniqueDraftId()
                }
            }

            if (projectUri.scheme == "content") {
                try {
                    contentResolver.takePersistableUriPermission(
                        projectUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "Could not take persistable URI permission for projectUri: ${e.message}")
                }
            }

            try {
                val reader = if (projectUri.scheme == "file") {
                    java.io.File(projectUri.path ?: "").bufferedReader()
                } else {
                    contentResolver.openInputStream(projectUri)?.bufferedReader()
                }

                reader?.use { r ->
                    val editRecipe = com.eclip.rup.leb.utils.ProjectSerializer.deserialize(r)
                    val project = editRecipe.toVideoProject()
                    viewModel.loadProject(project)
                    videoUri = project.sourceUri
                    requiredClipUris.add(project.sourceUri.toString())
                    project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Merge>()
                        .forEach { op ->
                            op.items.forEach { item ->
                                requiredClipUris.add(item.uri.toString())
                                startBackgroundClipProcessing(item.uri, isMain = false)
                            }
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load project", e)
                Toast.makeText(this, "Failed to load project", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (currentDraftId.isBlank()) {
                currentDraftId = com.eclip.rup.leb.utils.DraftManager.generateUniqueDraftId()
            }
            val incomingTimelineClips = androidx.core.content.IntentCompat.getParcelableArrayListExtra(
                intent, "TIMELINE_CLIPS", TimelineClip::class.java
            )
            val mediaUris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, "MEDIA_URIS", Uri::class.java)
            val singleVideoUri = androidx.core.content.IntentCompat.getParcelableExtra(intent, "VIDEO_URI", Uri::class.java)

            if (!incomingTimelineClips.isNullOrEmpty()) {
                initialTimelineClips = incomingTimelineClips
                videoUri = incomingTimelineClips.first().uri
                requiredClipUris.addAll(incomingTimelineClips.map { it.uri.toString() })
                incomingTimelineClips.forEach { clip ->
                    if (!clip.path.startsWith("content://")) {
                        val localPath = clip.path.removePrefix("file://")
                        val f = File(localPath)
                        if (f.exists() && f.canRead()) {
                            uriToFilePathCache[clip.uri.toString()] = f.absolutePath
                            uriToFilePathCache[clip.path] = f.absolutePath
                        }
                    }
                }
                setupTimelineVideoGuruStyle(incomingTimelineClips)
                transitionToReadinessState(EditorReadinessState.PROCESSING)
            } else if (!mediaUris.isNullOrEmpty() || singleVideoUri != null) {
                val urisToProcess = mediaUris ?: listOfNotNull(singleVideoUri)
                videoUri = urisToProcess.first()
                importViewModel.processImportUris(this, urisToProcess, bgMediaProcessor)
            } else {
                transitionToReadinessState(EditorReadinessState.READY)
            }
        }

        if (videoUri != null && videoUri?.scheme == "content") {
            try {
                contentResolver.takePersistableUriPermission(videoUri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.d(TAG, "Could not take persistable URI permission for videoUri: ${e.message}")
            }
        }

        val allIncomingMediaUris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, "MEDIA_URIS", Uri::class.java)
        if (allIncomingMediaUris != null) {
            for (uri in allIncomingMediaUris) {
                if (uri.scheme == "content") {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not take persistable URI permission for media item: ${e.message}")
                    }
                }
            }
        }
        
        if (videoUri != null) {
            com.eclip.rup.leb.engine.thumbnails.ScalableThumbnailEngine.setScrollThrottling(true)
            val renderersFactory = com.google.android.exoplayer2.DefaultRenderersFactory(this).apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(com.google.android.exoplayer2.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }
            player = com.google.android.exoplayer2.ExoPlayer.Builder(this, renderersFactory).build()
            player.addAnalyticsListener(com.eclip.rup.leb.utils.TimelineLifecycleLogger.createExoAnalyticsListener())
            playerView.player = player

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_BUFFERING) {
                        showCentralizedLoading("buffering")
                    } else if (state == Player.STATE_READY) {
                        hideCentralizedLoading("buffering")
                        hideCentralizedLoading("seeking")
                        hideCentralizedLoading("initial_loading")
                        hideCentralizedLoading("readiness")
                        hideCentralizedLoading("import")
                        hideCentralizedLoading("general")
                        hideCentralizedLoading(null)
                        isVideoLoaded = true
                        com.eclip.rup.leb.engine.thumbnails.ScalableThumbnailEngine.setScrollThrottling(false)
                        val totalDuration = timelineCache?.totalDuration?.takeIf { it > 0L } ?: getTotalSequenceDuration()
                        customVideoSeeker.setVideoDuration(totalDuration)
                        timeRulerView.setVideoDuration(totalDuration)
                        updateDurationDisplay(getGlobalPosition().toInt(), totalDuration.toInt())
                        
                        if (!isInitialFitDone && totalDuration > 0L) {
                            timelineHorizontalScroll.post {
                                val scrollWidth = timelineHorizontalScroll.width.toFloat()
                                if (scrollWidth > 0) {
                                    val safeDuration = totalDuration
                                    val density = resources.displayMetrics.density
                                    val defaultScale = 0.12f * density
                                    val fitScale = (scrollWidth - 32.dpToPx()) / safeDuration
                                    val adaptiveScale = calculateAdaptivePixelsPerMs(safeDuration)
                                    val newScale = if (safeDuration <= 4000L && fitScale > defaultScale) {
                                        fitScale.coerceIn(defaultScale, 0.30f * density)
                                    } else {
                                        adaptiveScale
                                    }
                                    if (pixelsPerMs != newScale) {
                                        pixelsPerMs = newScale
                                        updateTimelineZoomLayout(safeDuration)
                                    }
                                    if (activeSegmentViews.isEmpty()) {
                                        viewModel.project.value?.let { renderTracks(it) }
                                    }
                                    isInitialFitDone = true
                                }
                            }
                        }

                        // We no longer rely on dynamic ExoPlayer video size for overlays.
                        // Overlays will be sized to match the canvasContainer, which reflects the final output canvas.
                        updateUIInteractionState()
                        checkEditorReadiness()
                    } else if (state == Player.STATE_ENDED) {
                        hideCentralizedLoading("buffering")
                        hideCentralizedLoading("seeking")
                        // Reset UI components when video reaches the end
                        val btnPlayPause = findViewById<ImageButton>(R.id.btnPlayPause)
                        btnPlayPause.setImageResource(R.drawable.ic_play_24)
                        try {
                            isProgrammaticScroll = true
                            timelineHorizontalScroll.scrollTo((getTotalSequenceDuration() * pixelsPerMs).toInt(), 0)
                        } finally {
                            isProgrammaticScroll = false
                        }

                        stopProgressUpdater()
                        if (isShowingPreview) dismissPreview()
                    } else if (state == Player.STATE_IDLE) {
                        hideCentralizedLoading("buffering")
                        hideCentralizedLoading("seeking")
                    }
                }

                override fun onRenderedFirstFrame() {
                    hideCentralizedLoading("seeking")
                    hideCentralizedLoading("buffering")
                    hideCentralizedLoading("initial_loading")
                    hideCentralizedLoading("readiness")
                    hideCentralizedLoading("import")
                    hideCentralizedLoading("general")
                    hideCentralizedLoading("preview_render")
                    hideCentralizedLoading(null)
                    isVideoLoaded = true
                    com.eclip.rup.leb.engine.thumbnails.ScalableThumbnailEngine.setScrollThrottling(false)
                    checkEditorReadiness()
                }

                private var runtimePlaybackErrorRetryCount = 0

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        runtimePlaybackErrorRetryCount = 0
                        startProgressUpdater()
                    } else if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
                        stopProgressUpdater()
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady && player.playbackState != Player.STATE_ENDED) {
                        startProgressUpdater()
                    } else if (!playWhenReady) {
                        stopProgressUpdater()
                    }
                }

                override fun onVideoSizeChanged(videoSize: com.google.android.exoplayer2.video.VideoSize) {
                    // Do nothing here for overlays because ExoPlayer's surface changes size dynamically when clips 
                    // have different aspect ratios, but we want our overlays to stick to the primary video's canvas.
                }

                override fun onPositionDiscontinuity(reason: Int) {
                    // Update UI immediately on manual seek
                    if (isVideoLoaded) {
                        syncUiWithPlayer()
                    }
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    super.onPlayerError(error)
                    Log.w(TAG, "ExoPlayer playback warning: ${error.errorCode} - ${error.message}", error)

                    if (runtimePlaybackErrorRetryCount < 3) {
                        runtimePlaybackErrorRetryCount++
                        Log.w(TAG, "Attempting graceful recovery from runtime player error (attempt $runtimePlaybackErrorRetryCount)...")
                        val currentGlobalPos = getGlobalPosition()
                        val project = viewModel.project.value
                        if (project != null) {
                            lastMediaSourceFingerprint = "" // Force complete rebuild
                            rebuildExoPlayerSource(project)
                            seekToGlobalPosition(currentGlobalPos, force = true)
                        } else {
                            player.prepare()
                        }
                    } else {
                        Log.e(TAG, "Exhausted player error retries. Pausing playback safely without closing editor.")
                        player.pause()
                        btnPlayPause.setImageResource(R.drawable.ic_play_24)
                        runtimePlaybackErrorRetryCount = 0
                        if (editorReadinessState != EditorReadinessState.READY) {
                            transitionToReadinessState(EditorReadinessState.FAILED)
                            MaterialAlertDialogBuilder(this@VideoEditingActivity)
                                .setTitle(R.string.str_unsupported_resolution_title)
                                .setMessage("Playback initialization failed for imported media.")
                                .setCancelable(false)
                                .setPositiveButton("Go Back") { _, _ -> cancelAllProcessingAndExit() }
                                .show()
                        }
                    }
                }
            })

            if (projectUri == null && importViewModel.importState.value !is ImportState.Loading) {
                val displayName = videoUri!!.lastPathSegment ?: "video"
                val fastDur = initialTimelineClips?.firstOrNull()?.durationMs
                    ?: (extractMediaDurationFast(videoUri!!).takeIf { it > 0L } ?: 5000L)
                originalMainVideoDurationMs = fastDur
                viewModel.initializeProject(videoUri!!, displayName)
                viewModel.updateMainVideoTrim(0L, fastDur)

                if (!initialTimelineClips.isNullOrEmpty() && initialTimelineClips!!.size > 1) {
                    processMergeClips(initialTimelineClips!!.drop(1))
                } else {
                    val mediaUris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, "MEDIA_URIS", Uri::class.java)
                    if (mediaUris != null && mediaUris.size > 1) {
                        processMergeVideoUris(mediaUris.drop(1))
                    }
                }
            }
            initializeVideoData()
        }
    }

    private var isProgressUpdating = false
    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isProgressUpdating && ::player.isInitialized && !isFinishing && !isDestroyed) {
                val shouldContinue = (player.playWhenReady || player.isPlaying || player.playbackState == Player.STATE_BUFFERING)
                    && player.playbackState != Player.STATE_ENDED
                if (shouldContinue) {
                    syncUiWithPlayer()
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                } else {
                    isProgressUpdating = false
                }
            } else {
                isProgressUpdating = false
            }
        }
    }

    private fun startProgressUpdater() {
        isProgressUpdating = true
        syncUiWithPlayer()
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopProgressUpdater() {
        isProgressUpdating = false
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
        syncUiWithPlayer()
    }

    private fun getSequenceItems(): List<com.eclip.rup.leb.models.EditOperation.MergeItem> {
        val items = mutableListOf<com.eclip.rup.leb.models.EditOperation.MergeItem>()
        val sourceUri = if (::tempInputFile.isInitialized) {
            android.net.Uri.fromFile(tempInputFile)
        } else {
            videoUri ?: return items
        }
        
        val trimOp = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Trim>()?.lastOrNull()
        val sourceDuration = if (originalMainVideoDurationMs > 0L) {
            originalMainVideoDurationMs
        } else {
            trimOp?.endMs ?: 5000L
        }
        val sTrimStart = trimOp?.startMs ?: 0L
        val sTrimEnd = trimOp?.endMs ?: sourceDuration
        val speedOp = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.SpeedMain>()?.lastOrNull()
        val speed = speedOp?.speed ?: 1.0f
        val reverseOp = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.ReverseMain>()?.lastOrNull()
        val isReversed = reverseOp?.isReversed ?: false
        val proxyUri = reverseOp?.proxyUri ?: speedOp?.proxyUri
        val mirrorOp = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.MirrorMain>()?.lastOrNull()
        val isMirrored = mirrorOp?.isMirrored ?: false
        val maskMainOp = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.MaskMain>()?.lastOrNull()
        val maskConfig = maskMainOp?.maskConfig ?: com.eclip.rup.leb.models.EditOperation.MaskConfig()
        val rotationOp = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.RotationMain>()?.lastOrNull()
        val rotation = rotationOp?.rotation ?: 0
        val opacityOp = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.OpacityMain>()?.lastOrNull()
        val opacity = opacityOp?.opacity ?: 1.0f
        val chromaOp = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.ChromaMain>()?.lastOrNull()
        val chromaColor = chromaOp?.color
        val chromaSimilarity = chromaOp?.similarity ?: 0.1f
        val animOp = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.AnimationMain>()?.lastOrNull()
        val inAnim = animOp?.inAnimation ?: "NONE"
        val outAnim = animOp?.outAnimation ?: "NONE"
        val animDurationMs = animOp?.animationDurationMs ?: 500L

        val isMainImg = isImageUri(sourceUri)
        items.add(com.eclip.rup.leb.models.EditOperation.MergeItem(
            uri = sourceUri,
            durationMs = sourceDuration,
            trimStartMs = sTrimStart,
            trimEndMs = sTrimEnd,
            speed = speed,
            isReversed = isReversed,
            isMirrored = isMirrored,
            proxyUri = proxyUri,
            scrubProxyUri = viewModel.project.value?.scrubProxyUri,
            maskConfig = maskConfig,
            isImage = isMainImg,
            rotation = rotation,
            opacity = opacity,
            chromaKeyColor = chromaColor,
            chromaKeySimilarity = chromaSimilarity,
            inAnimation = inAnim,
            outAnimation = outAnim,
            animationDurationMs = animDurationMs
        ))
        
        val mergeOp = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Merge>()?.firstOrNull()
        if (mergeOp != null) {
            items.addAll(mergeOp.items)
        }
        return items
    }

    private fun getGlobalPosition(): Long {
        if (!::player.isInitialized) return 0L
        val totalDuration = getTotalSequenceDuration()
        val index = player.currentMediaItemIndex
        var pos = 0L
        for (i in 0 until minOf(index, chunkDurationsMs.size)) {
            pos += chunkDurationsMs[i]
        }
        val currentChunkDur = chunkDurationsMs.getOrNull(index) ?: 0L
        val rawChunkPos = player.currentPosition
        val chunkPos = if (currentChunkDur > 0L) {
            rawChunkPos.coerceIn(0L, currentChunkDur)
        } else {
            rawChunkPos.coerceAtLeast(0L)
        }
        val result = pos + chunkPos
        return if (totalDuration > 0L) result.coerceIn(0L, totalDuration) else result
    }

    private fun getTotalSequenceDuration(): Long {
        return getSequenceItems().sumOf { it.trimmedDurationMs }
    }

    private fun applyColorFilterToPlayer(filterName: String) {
        val idx = selectedVideoIndex ?: 0
        val activeAdjust = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Adjust>()
            ?.find { it.index == idx }
        applyColorFilterAndAdjustToPlayer(filterName, activeAdjust)
    }

    private fun applyColorFilterAndAdjustToPlayer(
        filterName: String,
        adjust: com.eclip.rup.leb.models.EditOperation.Adjust?
    ) {
        val filterMatrix = when (filterName.lowercase()) {
            "vintage" -> floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f,     0f,     0f,     1f, 0f
            )
            "warm" -> floatArrayOf(
                1.1f, 0f, 0f, 0f, 10f,
                0f, 1.0f, 0f, 0f, 5f,
                0f, 0f, 0.9f, 0f, -10f,
                0f, 0f, 0f, 1f, 0f
            )
            "cool" -> floatArrayOf(
                0.9f, 0f, 0f, 0f, -10f,
                0f, 1.0f, 0f, 0f, 0f,
                0f, 0f, 1.2f, 0f, 15f,
                0f, 0f, 0f, 1f, 0f
            )
            "contrast" -> floatArrayOf(
                1.4f, 0f, 0f, 0f, -50f,
                0f, 1.4f, 0f, 0f, -50f,
                0f, 0f, 1.4f, 0f, -50f,
                0f, 0f, 0f, 1f, 0f
            )
            "monochrome" -> floatArrayOf(
                0.33f, 0.59f, 0.11f, 0f, 0f,
                0.33f, 0.59f, 0.11f, 0f, 0f,
                0.33f, 0.59f, 0.11f, 0f, 0f,
                0f,    0f,    0f,    1f, 0f
            )
            "vignette" -> floatArrayOf(
                0.8f, 0f, 0f, 0f, 0f,
                0f, 0.8f, 0f, 0f, 0f,
                0f, 0f, 0.8f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            "negative" -> floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
            "crossprocess" -> floatArrayOf(
                1.2f, 0f, 0f, 0f, 0f,
                0f, 1.0f, 0f, 0f, 10f,
                0f, 0f, 1.4f, 0f, -20f,
                0f, 0f, 0f, 1f, 0f
            )
            else -> null
        }

        val baseMatrix = if (filterMatrix != null) {
            android.graphics.ColorMatrix(filterMatrix)
        } else {
            android.graphics.ColorMatrix()
        }

        if (adjust != null && !adjust.isDefault()) {
            if (adjust.saturation != 0) {
                val satFactor = 1.0f + (adjust.saturation / 100f)
                val satMatrix = android.graphics.ColorMatrix()
                satMatrix.setSaturation(satFactor)
                baseMatrix.postConcat(satMatrix)
            }
            if (adjust.contrast != 0) {
                val scale = 1.0f + (adjust.contrast / 100f)
                val translate = 127.5f * (1.0f - scale)
                val contrastMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
                baseMatrix.postConcat(contrastMatrix)
            }
            if (adjust.brightness != 0) {
                val bOffset = (adjust.brightness / 100f) * 255f
                val brightMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    1f, 0f, 0f, 0f, bOffset,
                    0f, 1f, 0f, 0f, bOffset,
                    0f, 0f, 1f, 0f, bOffset,
                    0f, 0f, 0f, 1f, 0f
                ))
                baseMatrix.postConcat(brightMatrix)
            }
            if (adjust.exposure != 0) {
                val ev = (adjust.exposure / 100f) * 3.0f
                val scale = Math.pow(2.0, ev.toDouble()).toFloat()
                val expMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, 0f,
                    0f, scale, 0f, 0f, 0f,
                    0f, 0f, scale, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                baseMatrix.postConcat(expMatrix)
            }
            if (adjust.warmth != 0) {
                val rOffset = (adjust.warmth / 100f) * 30f
                val bOffset = -(adjust.warmth / 100f) * 30f
                val warmthMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    1f, 0f, 0f, 0f, rOffset,
                    0f, 1f, 0f, 0f, rOffset / 2f,
                    0f, 0f, 1f, 0f, bOffset,
                    0f, 0f, 0f, 1f, 0f
                ))
                baseMatrix.postConcat(warmthMatrix)
            }
        }

        val hasAdjustments = (adjust != null && !adjust.isDefault())
        if (filterMatrix != null || hasAdjustments) {
            val paint = android.graphics.Paint()
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(baseMatrix)
            playerView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            playerView.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun syncUiWithPlayer() {
        if (!::player.isInitialized || !::timelineHorizontalScroll.isInitialized) return
        if (isFinishing || isDestroyed) return
        val currentGlobalPos = getGlobalPosition()
        val cache = timelineCache
        val totalDuration = cache?.totalDuration?.takeIf { it > 0L } ?: getTotalSequenceDuration()

        if (totalDuration > 0) {
            updateDurationDisplay(currentGlobalPos.toInt(), totalDuration.toInt())
            if (::timeRulerView.isInitialized) {
                timeRulerView.invalidate()
            }
            
            if (!isUserScrollingTimeline && !isTrackDragging) {
                val targetScrollX = (currentGlobalPos * pixelsPerMs).toInt()
                try {
                    isProgrammaticScroll = true
                    timelineHorizontalScroll.scrollTo(targetScrollX, 0)
                } finally {
                    isProgrammaticScroll = false
                }
            }

            textOverlayView?.currentPositionMs = currentGlobalPos
            imageOverlayView?.currentPositionMs = currentGlobalPos
            mosaicOverlayView?.currentPositionMs = currentGlobalPos

            // Sync dynamic playback speed if chunk speed differs
            val currentChunkIndex = player.currentMediaItemIndex
            val expectedSpeed = chunkSpeeds.getOrNull(currentChunkIndex) ?: 1.0f
            if (player.playbackParameters.speed != expectedSpeed) {
                player.playbackParameters = com.google.android.exoplayer2.PlaybackParameters(expectedSpeed)
            }

            // Predictive conveyor-belt prefetching for upcoming timeline thumbnails
            if (::timelinePrefetchPipeline.isInitialized) {
                val isPlaying = player.isPlaying
                val speed = player.playbackParameters.speed
                timelinePrefetchPipeline.onPlayheadAdvanced(
                    currentGlobalPos,
                    isPlaying,
                    speed,
                    videoSpatialIndex
                )
            }

            // Apply color filter corresponding to the active clip via cached lookup
            val sequenceItems = cache?.sequenceItems ?: getSequenceItems()
            val activeClipIndex = findActiveClipIndex(currentGlobalPos)
            val accumulatedStartMs = cache?.clipBoundaries?.getOrNull(activeClipIndex) ?: 0L

            val activeFilterName = cache?.colorFilterMap?.get(activeClipIndex) ?: "none"
            val activeAdjust = cache?.adjustMap?.get(activeClipIndex)
            applyColorFilterAndAdjustToPlayer(activeFilterName, activeAdjust)
            
            if (activeClipIndex >= 0 && activeClipIndex < sequenceItems.size) {
                val relTimeMs = currentGlobalPos - accumulatedStartMs
                val evaluatedMask = sequenceItems[activeClipIndex].maskConfig.evaluatedAt(relTimeMs)
                mainVideoMaskContainer?.maskConfig = evaluatedMask

                val activeItem = sequenceItems[activeClipIndex]
                if (activeItem.isImage || isImageUri(activeItem.uri)) {
                    photoSequencePreviewImageView?.visibility = View.VISIBLE
                    playerView.visibility = View.INVISIBLE
                    if (lastPhotoSequenceUri != activeItem.uri) {
                        lastPhotoSequenceUri = activeItem.uri
                        photoSequencePreviewImageView?.setImageURI(activeItem.uri)
                    }
                } else {
                    lastPhotoSequenceUri = null
                    photoSequencePreviewImageView?.visibility = View.GONE
                    playerView.visibility = View.VISIBLE
                }
            }
            
            if (activeClipIndex != lastActiveClipIndexForBlur) {
                lastActiveClipIndexForBlur = activeClipIndex
                updateCanvasBackgroundPreview(activeClipIndex)
            }

            val isMirrored = sequenceItems.getOrNull(activeClipIndex)?.isMirrored ?: false
            val videoSurface = playerView.videoSurfaceView
            if (videoSurface != null) {
                videoSurface.scaleX = if (isMirrored) -1f else 1f
            } else {
                findTextureView(playerView)?.scaleX = if (isMirrored) -1f else 1f
            }

            // Real-time transition preview overlay update via cached transitions
            val transitionMap = cache?.transitionMap
            var transitionActiveThisFrame = false

            if (transitionMap != null && transitionMap.isNotEmpty() && sequenceItems.size > 1) {
                val boundaries = cache.clipBoundaries
                for (i in 0 until sequenceItems.size - 1) {
                    val accumBoundaryMs = boundaries.getOrNull(i + 1) ?: continue
                    val transOp = transitionMap[i]
                    if (transOp != null && transOp.type.lowercase() != "none") {
                        val windowMs = 1000L
                        val halfWindow = windowMs / 2
                        val transStart = accumBoundaryMs - halfWindow
                        val transEnd = accumBoundaryMs + halfWindow

                        if (currentGlobalPos in transStart..transEnd) {
                            transitionActiveThisFrame = true
                            val prog = (currentGlobalPos - transStart).toFloat() / windowMs.toFloat()

                            if (activeTransitionIndex != i || cachedTransitionBitmap == null || cachedTransitionBitmap?.isRecycled == true) {
                                if (activeTransitionIndex != i && activeTransitionIndex != -1) {
                                    transitionPreviewOverlayView?.clearSnapshot()
                                    if (cachedTransitionBitmap != null) {
                                        val oldBmp = cachedTransitionBitmap
                                        cachedTransitionBitmap = null
                                        BitmapPool.release(oldBmp)
                                    }
                                }
                                activeTransitionIndex = i
                                val textureView = findTextureView(playerView)
                                if (textureView != null && textureView.width > 0 && textureView.height > 0) {
                                    try {
                                        val targetW = (textureView.width / 2).coerceAtLeast(1)
                                        val targetH = (textureView.height / 2).coerceAtLeast(1)
                                        val pooled = BitmapPool.acquireOrCreate(targetW, targetH, Bitmap.Config.ARGB_8888)
                                        cachedTransitionBitmap = textureView.getBitmap(pooled)
                                    } catch (e: Exception) {
                                        cachedTransitionBitmap = null
                                    }
                                }
                            }

                            transitionPreviewOverlayView?.visibility = View.VISIBLE
                            transitionPreviewOverlayView?.updateTransition(transOp.type, prog, cachedTransitionBitmap)
                            break
                        }
                    }
                }
            }

            if (!transitionActiveThisFrame) {
                if (activeTransitionIndex != -1) {
                    activeTransitionIndex = -1
                    transitionPreviewOverlayView?.clearSnapshot()
                    transitionPreviewOverlayView?.visibility = View.GONE
                    if (cachedTransitionBitmap != null) {
                        val bitmapToRelease = cachedTransitionBitmap
                        cachedTransitionBitmap = null
                        BitmapPool.release(bitmapToRelease)
                    }
                }
            }
        }
    }

    private var lastSoughtGlobalPos = -1L
    private var pendingSeekRunnable: Runnable? = null
    private val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun handleTimelineDragStart() {
        if (::adaptiveTimelineController.isInitialized && adaptiveTimelineController.scrollState == TimelineScrollState.IDLE) {
            wasPlayingBeforeDrag = (::player.isInitialized && (player.isPlaying || player.playWhenReady))
        } else if (!::adaptiveTimelineController.isInitialized) {
            wasPlayingBeforeDrag = (::player.isInitialized && (player.isPlaying || player.playWhenReady))
        }
        pausePlaybackOnTimelineInteraction()
    }

    /**
     * Throttles seek requests during active timeline dragging/scrubbing to prevent ExoPlayer overload.
     * Guaranteed:
     * - Timeline UI / thumbnail movement is NEVER throttled.
     * - ExoPlayer seek is throttled to [SEEK_THROTTLE_MS] (80ms) using SystemClock.uptimeMillis().
     * - Uses SeekParameters.CLOSEST_SYNC for fast keyframe seeking during drag.
     * - Coalesces intermediate seek requests: never queues stale positions.
     */
    private fun onTimelineScrubbed(targetMs: Long, isScrubbing: Boolean = true) {
        pendingSeekPositionMs = targetMs
        val now = android.os.SystemClock.uptimeMillis()

        if (now - lastSeekUptimeMs >= SEEK_THROTTLE_MS) {
            lastSeekUptimeMs = now
            pendingSeekRunnable?.let { seekHandler.removeCallbacks(it) }
            pendingSeekRunnable = null

            if (::player.isInitialized) {
                val seekParams = if (isScrubbing) {
                    com.google.android.exoplayer2.SeekParameters.CLOSEST_SYNC
                } else {
                    com.google.android.exoplayer2.SeekParameters.EXACT
                }
                if (player.seekParameters != seekParams) {
                    player.setSeekParameters(seekParams)
                }
                performDirectPlayerSeek(targetMs)
            }
        } else {
            // Seek Request Coalescing: cancel previous pending runnable and schedule a single trailing seek
            val remainingDelay = (SEEK_THROTTLE_MS - (now - lastSeekUptimeMs)).coerceAtLeast(1L)
            pendingSeekRunnable?.let { seekHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (!isFinishing && !isDestroyed && ::player.isInitialized) {
                    val state = if (::adaptiveTimelineController.isInitialized) adaptiveTimelineController.scrollState else TimelineScrollState.IDLE
                    if (state == TimelineScrollState.DRAGGING || state == TimelineScrollState.IDLE) {
                        lastSeekUptimeMs = android.os.SystemClock.uptimeMillis()
                        val seekParams = if (isScrubbing) {
                            com.google.android.exoplayer2.SeekParameters.CLOSEST_SYNC
                        } else {
                            com.google.android.exoplayer2.SeekParameters.EXACT
                        }
                        if (player.seekParameters != seekParams) {
                            player.setSeekParameters(seekParams)
                        }
                        performDirectPlayerSeek(pendingSeekPositionMs)
                    }
                }
            }
            pendingSeekRunnable = runnable
            seekHandler.postDelayed(runnable, remainingDelay)
        }
    }

    /**
     * Executes authoritative final seek when user drag finishes (IDLE state).
     * Applies SeekParameters.EXACT and resumes playback ONLY if it was playing before drag.
     */
    private fun onTimelineDragFinished() {
        if (!::player.isInitialized) return
        pendingSeekRunnable?.let { seekHandler.removeCallbacks(it) }
        pendingSeekRunnable = null

        val finalPosition = pendingSeekPositionMs
        showCentralizedLoading("seeking")
        player.setSeekParameters(com.google.android.exoplayer2.SeekParameters.EXACT)
        performDirectPlayerSeek(finalPosition)

        if (wasPlayingBeforeDrag) {
            wasPlayingBeforeDrag = false
            player.play()
            btnPlayPause.setImageResource(R.drawable.ic_pause_24)
            if (::btnFullscreenPlayPause.isInitialized) {
                btnFullscreenPlayPause.setImageResource(R.drawable.ic_pause_24)
            }
            startProgressUpdater()
        }
    }

    private fun seekToGlobalPosition(
        globalPos: Long,
        force: Boolean = false,
        isScrubbing: Boolean = false,
        generation: Long = if (::adaptiveTimelineController.isInitialized) adaptiveTimelineController.currentGeneration else 0L
    ) {
        if (!::player.isInitialized) return

        pendingSeekPositionMs = globalPos

        // Suppress seeking during fling / SETTLING state unless forced
        if (::adaptiveTimelineController.isInitialized && !force) {
            if (!adaptiveTimelineController.shouldSeekVideoPipeline()) {
                com.eclip.rup.leb.utils.TimelineLifecycleLogger.logPreviewSeekDiscard(
                    requestGeneration = generation,
                    currentGeneration = adaptiveTimelineController.currentGeneration,
                    positionMs = globalPos,
                    reason = "Suppressed: Timeline state is ${adaptiveTimelineController.scrollState}"
                )
                return
            }
        }

        if (force) {
            pendingSeekRunnable?.let { seekHandler.removeCallbacks(it) }
            pendingSeekRunnable = null
            showCentralizedLoading("seeking")
            player.setSeekParameters(com.google.android.exoplayer2.SeekParameters.EXACT)
            performDirectPlayerSeek(globalPos)
        } else {
            if (!isScrubbing) {
                showCentralizedLoading("seeking")
            }
            onTimelineScrubbed(globalPos, isScrubbing = isScrubbing)
        }
    }

    private fun performDirectPlayerSeek(globalPos: Long) {
        if (!::player.isInitialized) return

        val state = if (::adaptiveTimelineController.isInitialized) adaptiveTimelineController.scrollState else TimelineScrollState.IDLE
        val curGen = if (::adaptiveTimelineController.isInitialized) adaptiveTimelineController.currentGeneration else 0L
        com.eclip.rup.leb.utils.TimelineLifecycleLogger.logPreviewSeekDispatch(
            generation = curGen,
            positionMs = globalPos,
            scrollState = state,
            isScrubbing = (state == TimelineScrollState.DRAGGING),
            force = false
        )

        lastSoughtGlobalPos = globalPos
        val totalDuration = getTotalSequenceDuration()
        val clampedPos = globalPos.coerceIn(0L, if (totalDuration > 0L) totalDuration else Long.MAX_VALUE)
        var remainingPos = clampedPos
        var index = 0
        while (index < chunkDurationsMs.size && remainingPos >= chunkDurationsMs[index] && index < chunkDurationsMs.size - 1) {
            remainingPos -= chunkDurationsMs[index]
            index++
        }
        if (index < chunkDurationsMs.size) {
            val chunkTargetMs = remainingPos.coerceIn(0L, chunkDurationsMs[index])
            player.seekTo(index, chunkTargetMs)
        } else if (chunkDurationsMs.isNotEmpty()) {
            val lastIndex = chunkDurationsMs.size - 1
            player.seekTo(lastIndex, chunkDurationsMs[lastIndex])
        }
        updateDurationDisplay(clampedPos.toInt(), totalDuration.toInt())
        textOverlayView?.currentPositionMs = clampedPos
        imageOverlayView?.currentPositionMs = clampedPos
        mosaicOverlayView?.currentPositionMs = clampedPos
    }

    private fun isImageUri(uri: Uri): Boolean {
        val mimeType = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        if (mimeType != null && mimeType.startsWith("image/")) {
            return true
        }
        val path = uri.path ?: uri.toString()
        val lower = path.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
               lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
               lower.endsWith(".heic") || lower.endsWith(".heif")
    }

    private fun isUriFileValid(uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            if (uri.scheme == "file" || uri.scheme == null) {
                val path = uri.path ?: return false
                val f = File(path)
                f.exists() && f.length() > 0L
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fast header-only duration extraction using MediaMetadataRetriever.
     * Does NOT decode any video frames and does NOT require CodecInstancePool permits.
     * Completes in milliseconds directly from file descriptor or path.
     */
    private fun extractMediaDurationFast(uri: Uri): Long {
        if (isImageUri(uri)) return 3000L
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "content") {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } ?: 0L
            } else {
                val path = uri.path ?: uri.toString()
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fast duration extraction failed for $uri: ${e.message}")
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun initializeVideoData() {
        val targetUri = videoUri ?: return
        
        viewModel.updateClipState(targetUri.toString(), com.eclip.rup.leb.models.ClipState.PENDING)
        
        val initialDur = if (originalMainVideoDurationMs > 0L) {
            originalMainVideoDurationMs
        } else {
            val fastDur = extractMediaDurationFast(targetUri).takeIf { it > 0L } ?: 5000L
            originalMainVideoDurationMs = fastDur
            fastDur
        }
        
        val currentProj = viewModel.project.value ?: com.eclip.rup.leb.models.VideoProject(
            sourceUri = targetUri,
            sourceName = "Pending Video",
            operations = listOf(com.eclip.rup.leb.models.EditOperation.Trim(0L, initialDur))
        )
        
        isVideoLoaded = true
        renderTracks(currentProj, immediate = false)
        
        val totalDuration = getTotalSequenceDuration()
        if (totalDuration > 0L) {
            updateDurationDisplay(0, totalDuration.toInt())
            if (::customVideoSeeker.isInitialized) {
                customVideoSeeker.setVideoDuration(totalDuration)
            }
            if (::timeRulerView.isInitialized) {
                timeRulerView.setVideoDuration(totalDuration)
            }
        }
        
        startBackgroundClipProcessing(targetUri, isMain = true)
        checkEditorReadiness()
    }

    private fun cancelAllProcessingAndExit() {
        backgroundProcessingJobs.values.forEach { it.cancel() }
        backgroundProcessingJobs.clear()
        cancelAllProcessingAndReleaseResources()
        finish()
    }

    private fun transitionToReadinessState(newState: EditorReadinessState) {
        editorReadinessState = newState
        when (newState) {
            EditorReadinessState.INITIALIZING,
            EditorReadinessState.PROCESSING -> {
                isImportLoading = true
                showCentralizedLoading("readiness")
            }
            EditorReadinessState.ALL_REQUIRED_CLIPS_READY -> {
                val totalDuration = getTotalSequenceDuration()
                if (totalDuration > 0L) {
                    updateDurationDisplay(getGlobalPosition().toInt(), totalDuration.toInt())
                    if (::customVideoSeeker.isInitialized) {
                        customVideoSeeker.setVideoDuration(totalDuration)
                    }
                    if (::timeRulerView.isInitialized) {
                        timeRulerView.setVideoDuration(totalDuration)
                    }
                }
            }
            EditorReadinessState.READY -> {
                isImportLoading = false
                hideCentralizedLoading("readiness")
                hideCentralizedLoading("initial_loading")
                hideCentralizedLoading("import")
                hideCentralizedLoading("general")
                hideCentralizedLoading("Loading...")
                hideCentralizedLoading(null)
                if (activeSegmentViews.isEmpty()) {
                    viewModel.project.value?.let { renderTracks(it, immediate = false) }
                }
            }
            EditorReadinessState.FAILED -> {
                isImportLoading = false
                hideCentralizedLoading("readiness")
                hideCentralizedLoading("initial_loading")
                hideCentralizedLoading("import")
                hideCentralizedLoading("general")
                hideCentralizedLoading(null)
            }
        }
    }

    private fun checkEditorReadiness() {
        if (editorReadinessState == EditorReadinessState.READY) return
        if (editorReadinessState == EditorReadinessState.FAILED) return

        if (requiredClipUris.isEmpty()) {
            transitionToReadinessState(EditorReadinessState.READY)
            return
        }

        val clipStates = viewModel.clipStates.value

        // Check if any clip has failed
        val anyFailed = requiredClipUris.any { uriStr ->
            clipStates[uriStr] == com.eclip.rup.leb.models.ClipState.FAILED
        }
        if (anyFailed) {
            transitionToReadinessState(EditorReadinessState.FAILED)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.str_unsupported_resolution_title)
                .setMessage("One or more imported video clips could not be processed or have an unsupported format.")
                .setCancelable(false)
                .setPositiveButton("Go Back") { _, _ ->
                    cancelAllProcessingAndExit()
                }
                .show()
            return
        }

        // Check if all required clips have completed validation, caching, and waveforms (ClipState.READY)
        val allClipsReady = requiredClipUris.all { uriStr ->
            clipStates[uriStr] == com.eclip.rup.leb.models.ClipState.READY
        }

        if (!allClipsReady) {
            return
        }

        // Check player and timeline readiness
        val isPlayerReady = ::player.isInitialized && (isVideoLoaded || player.playbackState == Player.STATE_READY)
        if (!isPlayerReady) {
            return
        }

        transitionToReadinessState(EditorReadinessState.ALL_REQUIRED_CLIPS_READY)
        transitionToReadinessState(EditorReadinessState.READY)
    }

    private val processingSemaphore = kotlinx.coroutines.sync.Semaphore(
        com.eclip.rup.leb.utils.DeviceCapabilityManager.importConcurrency
    )
    private val materializationSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private val backgroundProcessingJobs = mutableMapOf<String, Job>()

    private fun startBackgroundClipProcessing(uri: Uri, isMain: Boolean = false) {
        val uriStr = uri.toString()
        if (backgroundProcessingJobs.containsKey(uriStr)) return

        backgroundProcessingJobs[uriStr] = lifecycleScope.launch(com.eclip.rup.leb.utils.AppDispatchers.ImportWorker) {
            processingSemaphore.withPermit {
                try {
                    viewModel.updateClipState(uriStr, com.eclip.rup.leb.models.ClipState.PENDING)
                    val isVideo = !isImageUri(uri)
                    var effectiveUri = uri

                    // SAF/content URIs yield instant editor entry. Real file path resolution
                    // is cached in the background without blocking editor readiness or tearing down player.
                    val requiresDeferredMaterialize = uri.scheme == "content"

                    // Lightweight container validation via FileDescriptor. This reads ONLY the
                    // moov/metadata atoms — no full-file copy and no frame decode.
                    var validation = com.eclip.rup.leb.utils.MediaValidator.validateMedia(this@VideoEditingActivity, effectiveUri)
                    if (validation is com.eclip.rup.leb.utils.MediaValidator.ValidationResult.RequiresDownscale) {
                        val downscaled = com.eclip.rup.leb.utils.DownscaleTranscoder.downscaleVideoTo1080p(
                            context = this@VideoEditingActivity,
                            inputUri = effectiveUri,
                            targetWidth = validation.targetWidth,
                            targetHeight = validation.targetHeight,
                            durationMs = validation.durationMs
                        )
                        if (downscaled?.path != null) {
                            effectiveUri = Uri.fromFile(File(downscaled.path!!))
                            validation = com.eclip.rup.leb.utils.MediaValidator.validateMedia(this@VideoEditingActivity, effectiveUri)
                        }
                    }

                    if (validation is com.eclip.rup.leb.utils.MediaValidator.ValidationResult.UnsupportedResolution ||
                        validation is com.eclip.rup.leb.utils.MediaValidator.ValidationResult.Invalid) {
                        viewModel.updateClipState(uriStr, com.eclip.rup.leb.models.ClipState.FAILED)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            checkEditorReadiness()
                        }
                        return@withPermit
                    }

                    val validMedia = validation as? com.eclip.rup.leb.utils.MediaValidator.ValidationResult.Valid
                    if (validMedia == null) {
                        viewModel.updateClipState(uriStr, com.eclip.rup.leb.models.ClipState.FAILED)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            checkEditorReadiness()
                        }
                        return@withPermit
                    }

                    // Reject videos that exceed hardware decoder capabilities
                    if (validMedia.isExceedingHardwareLimits) {
                        viewModel.updateClipState(uriStr, com.eclip.rup.leb.models.ClipState.FAILED)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (isMain) {
                                showUnsupportedResolutionDialog()
                            }
                            checkEditorReadiness()
                        }
                        return@withPermit
                    }

                    if (isMain) {
                        val dur = if (validMedia.durationMs > 0L) validMedia.durationMs else 5000L
                        originalMainVideoDurationMs = dur
                        if (validMedia.rotation == 90 || validMedia.rotation == 270) {
                            primaryVideoAspectRatio = validMedia.height.toFloat() / validMedia.width.toFloat()
                        } else {
                            primaryVideoAspectRatio = validMedia.width.toFloat() / validMedia.height.toFloat()
                        }
                        viewModel.updateMainVideoTrim(0L, dur)
                    } else if (isVideo) {
                        val duration = if (validMedia.durationMs > 0L) validMedia.durationMs else 0L

                        if (duration > 0L) {
                            val proj = viewModel.project.value
                            if (proj != null) {
                                val newOps = proj.operations.map { op ->
                                    if (op is com.eclip.rup.leb.models.EditOperation.Merge) {
                                        op.copy(items = op.items.map { item ->
                                            if (item.uri == uri) item.copy(durationMs = duration, trimEndMs = duration) else item
                                        })
                                    } else op
                                }
                            }
                        }
                    }

                    // If downscaling occurred, update the project with the downscaled URI
                    if (effectiveUri != uri) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            viewModel.swapClipUri(uri, effectiveUri)
                            checkEditorReadiness()
                        }
                    }

                    // INSTANT READY: the editor unlocks immediately for ANY media duration.
                    viewModel.updateClipState(uriStr, com.eclip.rup.leb.models.ClipState.READY)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        checkEditorReadiness()
                    }

                    // Background low-priority resolution/materialization of content source
                    if (requiresDeferredMaterialize) {
                        val originalUri = uri
                        val targetUri = effectiveUri
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            materializationSemaphore.withPermit {
                                try {
                                    val cachedPath = getFilePathFromUri(targetUri)
                                    if (cachedPath != null) {
                                        uriToFilePathCache[originalUri.toString()] = cachedPath
                                        uriToFilePathCache[targetUri.toString()] = cachedPath
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Deferred file materialization failed for $uri: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    viewModel.updateClipState(uriStr, com.eclip.rup.leb.models.ClipState.FAILED)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        checkEditorReadiness()
                    }
                } finally {
                    backgroundProcessingJobs.remove(uriStr)
                }
            }
        }
    }
    private val uriToFilePathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun getFilePathFromUri(uri: Uri): String? {
        val cacheKey = uri.toString()
        val cached = uriToFilePathCache[cacheKey]
        if (cached != null) {
            val cachedFile = File(cached)
            if (cachedFile.exists() && cachedFile.length() > 0L) {
                return cached
            }
        }
        var filePath: String? = null
        when (uri.scheme) {
            "content" -> {
                filePath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // 1. Zero-copy: check if direct MediaStore DATA column provides a readable file path
                    try {
                        val proj = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
                        contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                            val colIdx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                            if (colIdx != -1 && cursor.moveToFirst()) {
                                val directPath = cursor.getString(colIdx)
                                if (!directPath.isNullOrBlank()) {
                                    val f = File(directPath)
                                    if (f.exists() && f.canRead() && f.length() > 0L) {
                                        return@withContext f.absolutePath
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    // 2. Fallback: Copy to cache only when direct file access is restricted
                    try {
                        val ext = getExtensionFromUri(uri) ?: (
                            if (isImageUri(uri)) ".jpg"
                            else if (contentResolver.getType(uri)?.startsWith("audio/") == true) getAudioExtension(uri)
                            else ".mp4"
                        )
                        val isAudio = contentResolver.getType(uri)?.startsWith("audio/") == true ||
                                      ext.equals(".mp3", true) || ext.equals(".m4a", true) ||
                                      ext.equals(".aac", true) || ext.equals(".ogg", true) ||
                                      ext.equals(".flac", true) || ext.equals(".wav", true) ||
                                      ext.equals(".opus", true)
                        val prefix = when {
                            isImageUri(uri) -> "imported_image"
                            isAudio -> "imported_audio"
                            else -> "imported_video"
                        }
                        val tempFile = File(cacheDir, "${prefix}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}$ext")
                        val inputStream = contentResolver.openInputStream(uri)
                        if (inputStream == null) {
                            Log.e("PathError", "openInputStream returned null for URI: $uri")
                            return@withContext null
                        }
                        inputStream.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            com.eclip.rup.leb.utils.AppCachePolicyManager.registerActiveFile(tempFile)
                            tempFile.absolutePath
                        } else {
                            Log.e("PathError", "File copy failed or file is empty")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e("PathError", "Failed to copy file from URI: ${e.message}")
                        null
                    }
                }
            }
            "file" -> filePath = uri.path
            else -> Log.e("PathError", "Unsupported URI scheme: ${uri.scheme}")
        }
        Log.d("PathInfo", "File path: $filePath")
        if (filePath != null) {
            uriToFilePathCache[cacheKey] = filePath
            com.eclip.rup.leb.utils.AppCachePolicyManager.registerActivePath(filePath)
        }
        return filePath
    }

    private fun renderTracks(project: VideoProject, immediate: Boolean = false) {
        if (!::sequenceTrackContainer.isInitialized) return
        if (isFinishing || isDestroyed) return
        pendingRenderRunnable?.let { sequenceTrackContainer.removeCallbacks(it) }
        pendingRenderRunnable = null
        if (immediate && sequenceTrackContainer.isAttachedToWindow) {
            performRenderTracks(project)
            return
        }
        val runnable = Runnable {
            if (!isFinishing && !isDestroyed && ::sequenceTrackContainer.isInitialized) {
                performRenderTracks(project)
            }
        }
        pendingRenderRunnable = runnable
        sequenceTrackContainer.post(runnable)
    }

    private fun startDragSession(view: View, index: Int, rawX: Float) {
        pausePlaybackOnTimelineInteraction()
        if (isDraggingSegment) return
        isDraggingSegment = true
        draggedIndex = index
        draggedView = view
        initialTouchX = rawX
        initialScrollX = timelineHorizontalScroll.scrollX

        timelineHorizontalScroll.requestDisallowInterceptTouchEvent(true)
        view.performAppHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        view.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .alpha(0.8f)
            .setDuration(100)
            .start()
        view.elevation = 20f * resources.displayMetrics.density

        segmentViews = activeSegmentViews.toMutableList()
        originalLefts.clear()
        for (i in 0 until segmentViews.size) {
            val lp = segmentViews[i].layoutParams as FrameLayout.LayoutParams
            originalLefts.add(lp.leftMargin)
        }
        currentDragOrder = (0 until segmentViews.size).toList()
    }

    private fun updateDragPosition(rawX: Float) {
        val draggedView = draggedView ?: return
        
        // Auto-scroll logic when dragging near screen edges
        val screenWidth = resources.displayMetrics.widthPixels
        val edgeThreshold = 100f * resources.displayMetrics.density
        if (rawX < edgeThreshold) {
            timelineHorizontalScroll.scrollBy(-15, 0)
        } else if (rawX > screenWidth - edgeThreshold) {
            timelineHorizontalScroll.scrollBy(15, 0)
        }
        
        val scrollDelta = timelineHorizontalScroll.scrollX - initialScrollX
        val dx = rawX - initialTouchX + scrollDelta
        draggedView.translationX = dx

        val draggedCenter = originalLefts[draggedIndex] + draggedView.width / 2f + dx
        val centers = List(segmentViews.size) { i ->
            if (i == draggedIndex) {
                draggedCenter
            } else {
                originalLefts[i] + segmentViews[i].width / 2f
            }
        }

        val newOrder = (0 until segmentViews.size).sortedBy { centers[it] }
        if (newOrder != currentDragOrder) {
            currentDragOrder = newOrder
            var currentLeft = 0f
            for (index in newOrder) {
                val view = segmentViews[index]
                if (index == draggedIndex) {
                    currentLeft += view.width
                } else {
                    val targetTranslationX = currentLeft - originalLefts[index]
                    view.animate()
                        .translationX(targetTranslationX)
                        .setDuration(150)
                        .start()
                    currentLeft += view.width
                }
            }
        }
    }

    private fun endDragSession() {
        if (!isDraggingSegment) return
        isDraggingSegment = false
        val draggedView = draggedView ?: return

        timelineHorizontalScroll.requestDisallowInterceptTouchEvent(false)

        var targetLeft = 0f
        for (index in currentDragOrder) {
            if (index == draggedIndex) {
                break
            }
            targetLeft += segmentViews[index].width
        }
        val targetTranslationX = targetLeft - originalLefts[draggedIndex]

        draggedView.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .translationX(targetTranslationX)
            .setDuration(150)
            .withEndAction {
                draggedView.elevation = 0f
                val sequenceItems = getSequenceItems()
                val finalItems = currentDragOrder.map { sequenceItems[it] }

                for (view in segmentViews) {
                    view.translationX = 0f
                }

                val originalOrder = (0 until segmentViews.size).toList()
                if (currentDragOrder != originalOrder) {
                    viewModel.updateFullSequenceOrder(finalItems)
                } else {
                    viewModel.project.value?.let { renderTracks(it) }
                }

                this.draggedView = null
                draggedIndex = -1
            }
            .start()
    }

    private fun toggleMuteAction() {
        val sequenceItems = getSequenceItems()
        val videoIndices = sequenceItems.withIndex()
            .filter { !it.value.isImage && !isImageUri(it.value.uri) }
            .map { it.index }
            
        if (videoIndices.isEmpty()) return
        
        viewModel.project.value?.let { proj ->
            val isCurrentlyMuted = videoIndices.all { index ->
                proj.operations.filterIsInstance<EditOperation.MuteClip>().find { it.index == index }?.isMuted == true
            }

            val newState = !isCurrentlyMuted
            
            val muteOp = proj.operations.find { it is EditOperation.MuteAudio } as? EditOperation.MuteAudio
            if (muteOp != null) {
                viewModel.removeOperation(muteOp.id)
            }
            
            viewModel.setAllClipsMuteState(newState, videoIndices)
            updateMuteUI(newState)
        }
    }

    private fun updateMuteUI(isMuted: Boolean) {
        val btnMute = timelineMainHeaderView?.findViewById<ImageView>(R.id.btnMute) ?: findViewById<ImageView>(R.id.btnMute)
        val tvMuteClipLabel = timelineMainHeaderView?.findViewById<TextView>(R.id.tvMuteClipLabel) ?: findViewById<TextView>(R.id.tvMuteClipLabel)
        btnMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off_24 else R.drawable.ic_volume_up_24)
        tvMuteClipLabel?.text = if (isMuted) "Unmute" else "Mute"
    }

    fun setupTimelineVideoGuruStyle(clipsList: List<TimelineClip>) {
        if (clipsList.isEmpty()) return

        // 1. तुरंत टोटल ड्यूरेशन निकालें (यह मैथ ऑपरेशन है, 0.001ms लेगा)
        val totalSequenceDuration = clipsList.sumOf { it.durationMs }
        if (totalSequenceDuration > 0L) {
            pixelsPerMs = calculateAdaptivePixelsPerMs(totalSequenceDuration)
        }

        // 2. तुरंत टाइम काउंटर टेक्स्टव्यू को अपडेट करें (0:00 से सीधे 2h 20m)
        updateDurationDisplay(0, totalSequenceDuration.toInt())
        if (::customVideoSeeker.isInitialized) {
            customVideoSeeker.setVideoDuration(totalSequenceDuration)
        }
        if (::timeRulerView.isInitialized) {
            timeRulerView.customMsPerPixel = 1.0f / pixelsPerMs
            timeRulerView.setVideoDuration(totalSequenceDuration)
        }

        // 3. पूरी टाइमलाइन की चौड़ाई तुरंत सेट करें
        val timelineWidth = (totalSequenceDuration * pixelsPerMs).toInt()
        sequenceTrackContainer.layoutParams = sequenceTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        timeRulerView.layoutParams = timeRulerView.layoutParams.apply {
            width = timelineWidth
        }

        sequenceTrackContainer.removeAllViews()

        // 4. केवल खाली डिब्बे (Placeholders) ड्रा करें - NO RECYCLERVIEW ATTACHMENT HERE
        var accumulatedStartMs = 0L

        clipsList.forEach { item ->
            val segmentWidth = (item.durationMs * pixelsPerMs).toInt()
            val segmentLeft = (accumulatedStartMs * pixelsPerMs).toInt()

            // एक बहुत ही हल्का व्यू (बिना आंतरिक RecyclerView के) इन्फ्लेट करें
            // या सिर्फ एक सिंपल View() बना लें जिसमें बैकग्राउंड कलर हो
            val placeholderView = View(this).apply {
                setBackgroundColor(android.graphics.Color.DKGRAY)
                layoutParams = FrameLayout.LayoutParams(segmentWidth, 40.dpToPx()).apply {
                    leftMargin = segmentLeft
                }
            }
            sequenceTrackContainer.addView(placeholderView)
            accumulatedStartMs += item.durationMs
        }

        updateTimelineAddButtonPosition()
        // यहाँ तक का काम 10 मिलीसेकंड में हो जाएगा और टाइमलाइन स्क्रॉल होने लगेगी!
    }

    private fun updateTrackWidths(totalSequenceDuration: Long) {
        val timelineWidth = (totalSequenceDuration * pixelsPerMs).toInt()

        // Set explicit widths for ruler and track containers
        timeRulerView.layoutParams = timeRulerView.layoutParams.apply {
            width = timelineWidth
        }
        timeRulerView.customMsPerPixel = 1.0f / pixelsPerMs
        timeRulerView.setVideoDuration(totalSequenceDuration)

        customVideoSeeker.setVideoDuration(totalSequenceDuration)

        sequenceTrackContainer.layoutParams = sequenceTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        pipTrackContainer.layoutParams = pipTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        audioTrackContainer.layoutParams = audioTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        textTrackContainer.layoutParams = textTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        stickerTrackContainer.layoutParams = stickerTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        effectTrackContainer.layoutParams = effectTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        mosaicTrackContainer.layoutParams = mosaicTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        imageTrackContainer.layoutParams = imageTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
    }

    private fun updateTimelineZoomLayout(totalDuration: Long) {
        if (totalDuration <= 0L) return
        updateTrackWidths(totalDuration)

        val sequenceItems = getSequenceItems()
        var accumulatedStartMs = 0L
        for (i in 0 until activeSegmentViews.size) {
            val segmentView = activeSegmentViews.getOrNull(i) as? FrameLayout ?: continue
            val item = sequenceItems.getOrNull(i) ?: continue

            val segmentWidth = (item.trimmedDurationMs * pixelsPerMs).toInt()
            val segmentLeft = (accumulatedStartMs * pixelsPerMs).toInt()
            (segmentView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = segmentWidth
                lp.leftMargin = segmentLeft
                segmentView.layoutParams = lp
            }

            val isPhoto = item.isImage || isImageUri(item.uri)
            val effectiveMaxDur = if (isPhoto) maxOf(item.durationMs, maxOf(item.trimEndMs, 600000L)) else item.durationMs
            val stretchedMaxDurationMs = (effectiveMaxDur / item.speed).toLong()
            val stretchedTrimStartMs = (item.trimStartMs / item.speed).toLong()

            val rv = segmentView.findViewById<RecyclerView>(R.id.segmentFrameRecyclerView)
            (rv?.adapter as? FrameAdapter)?.let { adapter ->
                val frameSpanMs = if (isPhoto) stretchedMaxDurationMs else (item.durationMs / item.speed).toLong()
                val itemWidth = maxOf(1, ((frameSpanMs * pixelsPerMs) / 15).toInt())
                if (adapter.itemWidth != itemWidth) {
                    adapter.updateItemWidth(itemWidth)
                }
            }
            (rv?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, -(stretchedTrimStartMs * pixelsPerMs).toInt())
            val thumbStrip = segmentView.findViewById<com.eclip.rup.leb.customviews.timeline.ClipThumbStripView>(R.id.segmentClipThumbStrip)
            thumbStrip?.setPixelsPerUs(pixelsPerMs / 1000f)

            val trackTrimView = segmentView.findViewById<com.eclip.rup.leb.customviews.TrackTrimView>(R.id.segmentTrimTrack)
            if (trackTrimView != null) {
                trackTrimView.customMsPerPixel = 1.0f / pixelsPerMs
                val trackWidth = (stretchedMaxDurationMs * pixelsPerMs).toInt()
                (trackTrimView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.width = trackWidth
                    lp.leftMargin = -(stretchedTrimStartMs * pixelsPerMs).toInt()
                    trackTrimView.layoutParams = lp
                }
                trackTrimView.invalidate()
            }

            accumulatedStartMs += item.trimmedDurationMs
        }

        fun updateLaneTrimViews(container: ViewGroup) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i) as? com.eclip.rup.leb.customviews.TrackTrimView ?: continue
                child.customMsPerPixel = 1.0f / pixelsPerMs
                child.invalidate()
            }
        }
        if (::pipTrackContainer.isInitialized) updateLaneTrimViews(pipTrackContainer)
        if (::audioTrackContainer.isInitialized) updateLaneTrimViews(audioTrackContainer)
        if (::textTrackContainer.isInitialized) updateLaneTrimViews(textTrackContainer)
        if (::stickerTrackContainer.isInitialized) updateLaneTrimViews(stickerTrackContainer)
        if (::effectTrackContainer.isInitialized) updateLaneTrimViews(effectTrackContainer)
        if (::mosaicTrackContainer.isInitialized) updateLaneTrimViews(mosaicTrackContainer)

        updateTimelineAddButtonPosition()
    }

    private fun updateTrackSelectionState() {
        val selectedId = viewModel.selectedOperationId.value
        
        // 1. Main sequence track segments
        for (i in 0 until activeSegmentViews.size) {
            val segView = activeSegmentViews.getOrNull(i) ?: continue
            val trimTrack = segView.findViewById<com.eclip.rup.leb.customviews.TrackTrimView>(R.id.segmentTrimTrack)
            val isSelected = (selectedVideoIndex == i)
            if (trimTrack != null && (trimTrack.isSelectedTrack != isSelected || trimTrack.isTrimEnabled != isSelected)) {
                trimTrack.isSelectedTrack = isSelected
                trimTrack.isTrimEnabled = isSelected
                trimTrack.invalidate()
            }
        }

        // 2. Overlay tracks
        fun updateLaneSelection(container: ViewGroup) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i) as? com.eclip.rup.leb.customviews.TrackTrimView ?: continue
                val opId = child.tag as? String ?: continue
                val isSelected = (opId == selectedId)
                if (child.isSelectedTrack != isSelected || child.isTrimEnabled != isSelected) {
                    child.isSelectedTrack = isSelected
                    child.isTrimEnabled = isSelected
                    child.invalidate()
                }
            }
        }

        if (::pipTrackContainer.isInitialized) updateLaneSelection(pipTrackContainer)
        if (::audioTrackContainer.isInitialized) updateLaneSelection(audioTrackContainer)
        if (::textTrackContainer.isInitialized) updateLaneSelection(textTrackContainer)
        if (::effectTrackContainer.isInitialized) updateLaneSelection(effectTrackContainer)
        if (::mosaicTrackContainer.isInitialized) updateLaneSelection(mosaicTrackContainer)
    }

    private fun rebuildExoPlayerSource(project: VideoProject) {
        if (!::player.isInitialized) return
        val sequenceItems = getSequenceItems()
        val totalSequenceDuration = getTotalSequenceDuration()
        if (totalSequenceDuration <= 0L) return

        val exoAudioOps = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio>()
            .filter { it.audioUri != null && it.audioUri != Uri.EMPTY && it.audioUri.toString().isNotBlank() && it.audioUri.toString() != "null" }
        val fingerprint = computeMediaSourceFingerprint(sequenceItems, exoAudioOps, project)
        if (fingerprint == lastMediaSourceFingerprint && (player.mediaItemCount > 0 || player.playbackState != com.google.android.exoplayer2.Player.STATE_IDLE)) {
            return
        }
        lastMediaSourceFingerprint = fingerprint

        val mediaSourceFactory = com.google.android.exoplayer2.source.DefaultMediaSourceFactory(this)

        // Fast-path: Single unclipped video without background audio or muting
        val isMuted = project.operations.any { it is com.eclip.rup.leb.models.EditOperation.MuteClip }
        if (sequenceItems.size == 1 &&
            exoAudioOps.isEmpty() &&
            !isMuted &&
            !sequenceItems[0].isImage &&
            !isImageUri(sequenceItems[0].uri)
        ) {
            val singleItem = sequenceItems[0]
            val isUncut = singleItem.speed == 1.0f && singleItem.trimStartMs == 0L && singleItem.trimmedDurationMs == singleItem.durationMs
            if (isUncut) {
                val videoUri = singleItem.proxyUri?.takeIf { isUriFileValid(it) }
                    ?: singleItem.scrubProxyUri?.takeIf { isUriFileValid(it) }
                    ?: singleItem.uri

                com.eclip.rup.leb.utils.AppCachePolicyManager.registerActiveUri(videoUri)
                com.eclip.rup.leb.utils.AppCachePolicyManager.registerActiveUri(singleItem.uri)

                val videoMediaItem = com.google.android.exoplayer2.MediaItem.Builder()
                    .setUri(videoUri)
                    .build()

                val directSource = mediaSourceFactory.createMediaSource(videoMediaItem)
                chunkDurationsMs = listOf(totalSequenceDuration)
                chunkSpeeds = listOf(1.0f)

                val globalPos = getGlobalPosition()
                player.setMediaSource(directSource, false)
                if (player.playbackState == com.google.android.exoplayer2.Player.STATE_IDLE) {
                    player.prepare()
                }
                seekToGlobalPosition(globalPos, force = false)
                pausePlaybackOnTimelineInteraction()
                return
            }
        }

        val boundaries = mutableSetOf<Long>()
        boundaries.add(0L)
        boundaries.add(totalSequenceDuration)

        var currentGlobalPosMs = 0L
        sequenceItems.forEach { item ->
            boundaries.add(currentGlobalPosMs)
            currentGlobalPosMs += item.trimmedDurationMs
            boundaries.add(currentGlobalPosMs)
        }

        exoAudioOps.forEach { op ->
            boundaries.add(op.startTimeMs ?: 0L)
            boundaries.add(op.endTimeMs ?: totalSequenceDuration)
        }

        val sortedBoundaries = boundaries.filter { it in 0..totalSequenceDuration }.sorted()
        val chunkedSources = mutableListOf<com.google.android.exoplayer2.source.MediaSource>()
        val newChunkDurations = mutableListOf<Long>()
        val newChunkSpeeds = mutableListOf<Float>()

        for (i in 0 until sortedBoundaries.size - 1) {
            val chunkStartMs = sortedBoundaries[i]
            val chunkEndMs = sortedBoundaries[i + 1]
            val chunkDurationMs = chunkEndMs - chunkStartMs
            if (chunkDurationMs <= 0L) continue

            var videoSourceForChunk: com.google.android.exoplayer2.source.MediaSource? = null
            var chunkSpeed = 1.0f
            var vGlobalMs = 0L
            for ((index, item) in sequenceItems.withIndex()) {
                val vStart = vGlobalMs
                val vEnd = vGlobalMs + item.trimmedDurationMs
                if (chunkStartMs >= vStart && chunkEndMs <= vEnd) {
                    chunkSpeed = item.speed
                    val offsetInVideoMs = chunkStartMs - vStart
                    val rawOffsetInVideoMs = (offsetInVideoMs * item.speed).toLong()
                    val rawChunkDurationMs = (chunkDurationMs * item.speed).toLong()

                    val clipStartUs: Long
                    val videoUri: Uri
                    if (item.proxyUri != null && isUriFileValid(item.proxyUri)) {
                        clipStartUs = rawOffsetInVideoMs * 1000L
                        videoUri = item.proxyUri
                    } else if (item.scrubProxyUri != null && isUriFileValid(item.scrubProxyUri)) {
                        clipStartUs = (item.trimStartMs + rawOffsetInVideoMs) * 1000L
                        videoUri = item.scrubProxyUri
                    } else {
                        clipStartUs = (item.trimStartMs + rawOffsetInVideoMs) * 1000L
                        videoUri = item.uri
                    }
                    com.eclip.rup.leb.utils.AppCachePolicyManager.registerActiveUri(videoUri)
                    com.eclip.rup.leb.utils.AppCachePolicyManager.registerActiveUri(item.uri)

                    val safeClipStartUs = clipStartUs.coerceAtLeast(0L)
                    val safeClipEndUs = maxOf(safeClipStartUs + 1000L, clipStartUs + (rawChunkDurationMs * 1000L))

                    val videoMediaItem = com.google.android.exoplayer2.MediaItem.Builder()
                        .setUri(videoUri)
                        .build()

                    val isClipMuted = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.MuteClip>()
                        .find { it.index == index }?.isMuted ?: false

                    videoSourceForChunk = if (item.isImage || isImageUri(item.uri)) {
                        com.google.android.exoplayer2.source.SilenceMediaSource(chunkDurationMs * 1000L)
                    } else if (safeClipStartUs < safeClipEndUs) {
                        val baseVideoSource = mediaSourceFactory.createMediaSource(videoMediaItem)
                        val clippedSource = com.google.android.exoplayer2.source.ClippingMediaSource(
                            baseVideoSource,
                            safeClipStartUs,
                            safeClipEndUs,
                            /* enableInitialDiscontinuity = */ false,
                            /* allowDynamicClippingUpdates = */ true,
                            /* relativeToDefaultPosition = */ false
                        )
                        if (isClipMuted) {
                            com.google.android.exoplayer2.source.FilteringMediaSource(
                                clippedSource,
                                com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO
                            )
                        } else {
                            clippedSource
                        }
                    } else {
                        com.google.android.exoplayer2.source.SilenceMediaSource(chunkDurationMs * 1000L)
                    }
                    break
                }
                vGlobalMs += item.trimmedDurationMs
            }

            if (videoSourceForChunk == null) {
                videoSourceForChunk = com.google.android.exoplayer2.source.SilenceMediaSource(chunkDurationMs * 1000L)
            }

            val chunkSourcesToMerge = mutableListOf<com.google.android.exoplayer2.source.MediaSource>(videoSourceForChunk)

            for (op in exoAudioOps) {
                val aStart = op.startTimeMs ?: 0L
                val aEnd = op.endTimeMs ?: totalSequenceDuration

                if (chunkStartMs >= aStart && chunkEndMs <= aEnd) {
                    val offsetInAudioMs = chunkStartMs - aStart
                    val clipStartUs = (op.internalStartMs + offsetInAudioMs) * 1000L
                    val clipEndUs = clipStartUs + (chunkDurationMs * 1000L)

                    val actualAudioDurationUs = if (op.internalEndMs > 0) op.internalEndMs * 1000L else Long.MAX_VALUE
                    if (clipStartUs >= actualAudioDurationUs && actualAudioDurationUs != Long.MAX_VALUE) {
                        chunkSourcesToMerge.add(com.google.android.exoplayer2.source.SilenceMediaSource(chunkDurationMs * 1000L))
                    } else {
                        var safeClipEndUs = clipEndUs
                        if (actualAudioDurationUs != Long.MAX_VALUE && safeClipEndUs > actualAudioDurationUs) {
                            safeClipEndUs = actualAudioDurationUs
                        }

                        val safeAudioStartUs = clipStartUs.coerceAtLeast(0L)
                        val safeAudioEndUs = maxOf(safeAudioStartUs + 1000L, safeClipEndUs)

                        if (safeAudioStartUs < safeAudioEndUs) {
                            val audioMediaItem = com.google.android.exoplayer2.MediaItem.Builder()
                                .setUri(op.audioUri)
                                .build()
                            com.eclip.rup.leb.utils.AppCachePolicyManager.registerActiveUri(op.audioUri)
                            val baseAudioSource = mediaSourceFactory.createMediaSource(audioMediaItem)

                            try {
                                val audioSlice = com.google.android.exoplayer2.source.ClippingMediaSource(
                                    baseAudioSource,
                                    safeAudioStartUs,
                                    safeAudioEndUs,
                                    /* enableInitialDiscontinuity = */ true,
                                    /* allowDynamicClippingUpdates = */ true,
                                    /* relativeToDefaultPosition = */ true
                                )
                                chunkSourcesToMerge.add(audioSlice)
                            } catch (e: Exception) {
                                chunkSourcesToMerge.add(baseAudioSource)
                            }
                        } else {
                            chunkSourcesToMerge.add(com.google.android.exoplayer2.source.SilenceMediaSource(chunkDurationMs * 1000L))
                        }
                    }
                } else {
                    chunkSourcesToMerge.add(com.google.android.exoplayer2.source.SilenceMediaSource(chunkDurationMs * 1000L))
                }
            }

            val mergedChunk = if (chunkSourcesToMerge.size == 1) {
                chunkSourcesToMerge[0]
            } else {
                com.google.android.exoplayer2.source.MergingMediaSource(true, false, *chunkSourcesToMerge.toTypedArray())
            }
            chunkedSources.add(mergedChunk)
            newChunkDurations.add(chunkDurationMs)
            newChunkSpeeds.add(chunkSpeed)
        }

        chunkDurationsMs = newChunkDurations
        chunkSpeeds = newChunkSpeeds
        val finalSource = com.google.android.exoplayer2.source.ConcatenatingMediaSource(*chunkedSources.toTypedArray())

        val globalPos = getGlobalPosition()

        player.setMediaSource(finalSource, false)
        if (player.playbackState == com.google.android.exoplayer2.Player.STATE_IDLE) {
            player.prepare()
        }

        seekToGlobalPosition(globalPos, force = false)
        pausePlaybackOnTimelineInteraction()
    }

    private fun renderPipTracks(project: VideoProject, totalSequenceDuration: Long) {
        pipTrackContainer.removeAllViews()
        pipTrackContainer.visibility = View.VISIBLE
        val pipOps = project.operations.filterIsInstance<EditOperation.AddImageOverlay>()
        if (pipOps.isNotEmpty()) {
            for (op in pipOps) {
                val trackView = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                        topMargin = 3.dpToPx()
                    }
                    tag = op.id
                    trackColor = android.graphics.Color.parseColor("#0097A7") // Teal/cyan for PIP/Overlay
                    trackLabel = op.imageUri.lastPathSegment ?: "PIP Overlay"
                    trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_pip_24)
                    isSelectedTrack = (op.id == viewModel.selectedOperationId.value)
                    isTrimEnabled = isSelectedTrack
                    maxDurationMs = totalSequenceDuration
                    customMsPerPixel = 1.0f / pixelsPerMs
                    activeStartMs = op.startTimeMs ?: 0L
                    activeEndMs = op.endTimeMs ?: totalSequenceDuration
                    val actualDuration = op.fileDurationMs ?: 3000L
                    val isImageOverlay = isImageUri(op.imageUri)
                    minAllowedStartMs = 0L
                    maxAllowedEndMs = if (op.isLooping || isImageOverlay) totalSequenceDuration else (op.startTimeMs ?: 0L) + actualDuration
                    maxSelectionDurationMs = if (op.isLooping || isImageOverlay) null else actualDuration
                    setRange(totalSequenceDuration, op.startTimeMs ?: 0L, op.endTimeMs ?: totalSequenceDuration)
                    onTrackClicked = {
                        if (viewModel.selectedOperationId.value == op.id) {
                            viewModel.selectOperation(null)
                        } else {
                            viewModel.selectOperation(op.id)
                        }
                    }
                    onTrimChanged = { start, end, target ->
                        viewModel.updateOperation(op.copy(startTimeMs = start, endTimeMs = end))
                        val targetPos = if (target == com.eclip.rup.leb.customviews.TrackTrimView.DragTarget.RIGHT) end else start
                        seekToGlobalPosition(targetPos, force = true, isScrubbing = false)
                    }
                    onTrimAdjustingWithDelta = { start, end, deltaStart, deltaEnd ->
                        if (deltaStart != 0L) {
                            seekToGlobalPosition(start, isScrubbing = true)
                        } else if (deltaEnd != 0L) {
                            seekToGlobalPosition(end, isScrubbing = true)
                        } else {
                            seekToGlobalPosition(start, isScrubbing = true)
                        }
                    }
                    onDragStateChanged = { isDragging ->
                        isTrackDragging = isDragging
                        if (isDragging) {
                            handleTimelineDragStart()
                        } else {
                            onTimelineDragFinished()
                        }
                    }
                    
                    val viewRef = this
                    val imageJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val path = getFilePathFromUri(op.imageUri)
                        if (path != null) {
                            try {
                                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                                val bitmap = android.graphics.BitmapFactory.decodeFile(path, options)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    viewRef.trackThumbnail = bitmap
                                    viewRef.invalidate()
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    activeRenderJobs.add(imageJob)
                }
                pipTrackContainer.addView(trackView)
            }
        } else {
            // Full-length persistent placeholder track
            val placeholderView = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                    topMargin = 3.dpToPx()
                }
                isPlaceholderTrack = true
                isTrimEnabled = false
                maxDurationMs = totalSequenceDuration
                customMsPerPixel = 1.0f / pixelsPerMs
                activeStartMs = 0L
                activeEndMs = totalSequenceDuration
                setRange(totalSequenceDuration, 0L, totalSequenceDuration)
                trackLabel = "+ PIP / Overlay"
                trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_pip_24)
                onTrackClicked = {
                    setActiveToolButton(R.id.btnMediaOverlay)
                    mediaOverlayAction()
                }
            }
            pipTrackContainer.addView(placeholderView)
        }
    }

    private fun renderAudioTracks(project: VideoProject, totalSequenceDuration: Long) {
        audioTrackContainer.removeAllViews()
        audioTrackContainer.visibility = View.VISIBLE
        val audioOps = project.operations.filterIsInstance<EditOperation.AddBackgroundAudio>()
        if (audioOps.isNotEmpty()) {
            for (op in audioOps) {
                val trackView = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                        topMargin = 3.dpToPx()
                    }
                    tag = op.id
                    trackColor = android.graphics.Color.parseColor("#7B1FA2") // Rich Purple for audio
                    trackLabel = op.audioUri.lastPathSegment ?: "Audio Track"
                    isAudioTrack = true
                    isSelectedTrack = (op.id == viewModel.selectedOperationId.value)
                    isTrimEnabled = isSelectedTrack
                    trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_music_note_24)
                    onTrackClicked = {
                        if (viewModel.selectedOperationId.value == op.id) {
                            viewModel.selectOperation(null)
                        } else {
                            viewModel.selectOperation(op.id)
                        }
                    }
                    val audioStartTime = op.startTimeMs ?: 0L
                    val audioEndTime = op.endTimeMs ?: totalSequenceDuration
                    val minAudioStart = (audioStartTime - op.internalStartMs).coerceAtLeast(0L)
                    val maxAudioEnd = if (op.originalDurationMs > 0L) {
                        audioStartTime + (op.originalDurationMs - op.internalStartMs)
                    } else {
                        totalSequenceDuration
                    }
                    minAllowedStartMs = minAudioStart
                    maxAllowedEndMs = maxAudioEnd
                    maxDurationMs = totalSequenceDuration
                    activeStartMs = audioStartTime
                    activeEndMs = audioEndTime
                    customMsPerPixel = 1.0f / pixelsPerMs
                    beats = op.beats
                    internalStartMs = op.internalStartMs
                    setRange(totalSequenceDuration, audioStartTime, audioEndTime)
                    onTrimChanged = { start, end, target ->
                        val oldOp = viewModel.project.value?.operations?.find { 
                            (it as? com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio)?.id == op.id 
                        } as? com.eclip.rup.leb.models.EditOperation.AddBackgroundAudio
                        if (oldOp != null) {
                            val delta = start - (oldOp.startTimeMs ?: 0L)
                            val newInternalStart = (oldOp.internalStartMs + delta).coerceAtLeast(0L)
                            val newDuration = end - start
                            val newInternalEnd = newInternalStart + newDuration
                            viewModel.updateOperation(oldOp.copy(startTimeMs = start, endTimeMs = end, internalStartMs = newInternalStart, internalEndMs = newInternalEnd))
                        }
                        val targetPos = if (target == com.eclip.rup.leb.customviews.TrackTrimView.DragTarget.RIGHT) end else start
                        seekToGlobalPosition(targetPos, force = true, isScrubbing = false)
                    }
                    onTrimAdjustingWithDelta = { start, end, deltaStart, deltaEnd ->
                        if (deltaStart != 0L) {
                            seekToGlobalPosition(start, isScrubbing = true)
                        } else if (deltaEnd != 0L) {
                            seekToGlobalPosition(end, isScrubbing = true)
                        } else {
                            seekToGlobalPosition(start, isScrubbing = true)
                        }
                    }
                    onDragStateChanged = { isDragging ->
                        isTrackDragging = isDragging
                        if (isDragging) {
                            handleTimelineDragStart()
                        } else {
                            onTimelineDragFinished()
                        }
                    }
                    
                    val viewRef = this
                    val waveJob = lifecycleScope.launch(AppDispatchers.Waveform) {
                        waveformExtractionSemaphore.acquire()
                        try {
                            val path = getFilePathFromUri(op.audioUri)
                            if (path != null) {
                                val amps = com.eclip.rup.leb.utils.AudioWaveformExtractor.extractWaveform(this@VideoEditingActivity, path)
                                if (amps != null) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        viewRef.audioAmplitudes = amps
                                        viewRef.invalidate()
                                    }
                                }
                            }
                        } finally {
                            waveformExtractionSemaphore.release()
                        }
                    }
                    activeRenderJobs.add(waveJob)
                }
                audioTrackContainer.addView(trackView)
            }
        } else {
            // Full-length persistent placeholder track
            val placeholderView = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                    topMargin = 3.dpToPx()
                }
                isPlaceholderTrack = true
                isTrimEnabled = false
                maxDurationMs = totalSequenceDuration
                customMsPerPixel = 1.0f / pixelsPerMs
                activeStartMs = 0L
                activeEndMs = totalSequenceDuration
                setRange(totalSequenceDuration, 0L, totalSequenceDuration)
                trackLabel = "+ Music / Audio"
                trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_music_note_24)
                onTrackClicked = {
                    setActiveToolButton(R.id.btnAudio)
                    audioAction()
                }
            }
            audioTrackContainer.addView(placeholderView)
        }
    }

    private fun renderTextTracks(project: VideoProject, totalSequenceDuration: Long) {
        textTrackContainer.removeAllViews()
        textTrackContainer.visibility = View.VISIBLE
        val textOps = project.operations.filterIsInstance<EditOperation.AddText>()
        if (textOps.isNotEmpty()) {
            for (op in textOps) {
                val trackView = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                        topMargin = 3.dpToPx()
                    }
                    tag = op.id
                    isSelectedTrack = (op.id == viewModel.selectedOperationId.value)
                    isTrimEnabled = isSelectedTrack
                    maxDurationMs = totalSequenceDuration
                    customMsPerPixel = 1.0f / pixelsPerMs
                    trackColor = android.graphics.Color.parseColor("#689F38") // Sage/Lime Green for text
                    trackLabel = op.text
                    trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_text_24)
                    minAllowedStartMs = 0L
                    maxAllowedEndMs = totalSequenceDuration
                    minDurationMs = 200L
                    activeStartMs = op.startTimeMs ?: 0L
                    activeEndMs = op.endTimeMs ?: totalSequenceDuration
                    setRange(totalSequenceDuration, op.startTimeMs ?: 0L, op.endTimeMs ?: totalSequenceDuration)
                    onTrackClicked = {
                        if (viewModel.selectedOperationId.value == op.id) {
                            viewModel.selectOperation(null)
                        } else {
                            viewModel.selectOperation(op.id)
                        }
                    }
                    onTrimChanged = { start, end, target ->
                        viewModel.updateOperation(op.copy(startTimeMs = start, endTimeMs = end))
                        val targetPos = if (target == com.eclip.rup.leb.customviews.TrackTrimView.DragTarget.RIGHT) end else start
                        seekToGlobalPosition(targetPos, force = true, isScrubbing = false)
                    }
                    onTrimAdjustingWithDelta = { start, end, deltaStart, deltaEnd ->
                        if (deltaStart != 0L) {
                            seekToGlobalPosition(start, isScrubbing = true)
                        } else if (deltaEnd != 0L) {
                            seekToGlobalPosition(end, isScrubbing = true)
                        } else {
                            seekToGlobalPosition(start, isScrubbing = true)
                        }
                    }
                    onDragStateChanged = { isDragging ->
                        isTrackDragging = isDragging
                        if (isDragging) {
                            handleTimelineDragStart()
                        } else {
                            onTimelineDragFinished()
                        }
                    }
                }
                textTrackContainer.addView(trackView)
            }
        } else {
            // Full-length persistent placeholder track
            val placeholderView = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                    topMargin = 3.dpToPx()
                }
                isPlaceholderTrack = true
                isTrimEnabled = false
                maxDurationMs = totalSequenceDuration
                customMsPerPixel = 1.0f / pixelsPerMs
                activeStartMs = 0L
                activeEndMs = totalSequenceDuration
                setRange(totalSequenceDuration, 0L, totalSequenceDuration)
                trackLabel = "+ Text"
                trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_text_24)
                onTrackClicked = {
                    setActiveToolButton(R.id.btnText)
                    textAction()
                }
            }
            textTrackContainer.addView(placeholderView)
        }
    }

    private fun renderStickerTracks(totalSequenceDuration: Long) {
        stickerTrackContainer.removeAllViews()
        stickerTrackContainer.visibility = View.VISIBLE
        val placeholderSticker = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                topMargin = 3.dpToPx()
            }
            isPlaceholderTrack = true
            isTrimEnabled = false
            maxDurationMs = totalSequenceDuration
            customMsPerPixel = 1.0f / pixelsPerMs
            activeStartMs = 0L
            activeEndMs = totalSequenceDuration
            setRange(totalSequenceDuration, 0L, totalSequenceDuration)
            trackLabel = "+ Sticker"
            trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_sticker_24)
            onTrackClicked = {
                setActiveToolButton(R.id.btnSticker)
                mediaOverlayAction()
            }
        }
        stickerTrackContainer.addView(placeholderSticker)
    }

    private fun renderEffectTracks(project: VideoProject, totalSequenceDuration: Long) {
        effectTrackContainer.removeAllViews()
        effectTrackContainer.visibility = View.VISIBLE
        val filterOps = project.operations.filterIsInstance<EditOperation.ColorFilter>()
        if (filterOps.isNotEmpty()) {
            for (op in filterOps) {
                val trackView = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                        topMargin = 3.dpToPx()
                    }
                    tag = op.id
                    trackColor = android.graphics.Color.parseColor("#0288D1") // Sky Blue for filter
                    trackLabel = "Filter: ${op.filterName}"
                    trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_effect_24)
                    isSelectedTrack = (op.id == viewModel.selectedOperationId.value)
                    isTrimEnabled = isSelectedTrack
                    maxDurationMs = totalSequenceDuration
                    customMsPerPixel = 1.0f / pixelsPerMs
                    activeStartMs = 0L
                    activeEndMs = totalSequenceDuration
                    setRange(totalSequenceDuration, 0L, totalSequenceDuration)
                    onTrackClicked = {
                        setActiveToolButton(R.id.btnEffect)
                        showColorFilterSelectionDialog(selectedVideoIndex ?: 0)
                    }
                }
                effectTrackContainer.addView(trackView)
            }
        } else {
            // Full-length persistent placeholder track
            val placeholderView = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                    topMargin = 3.dpToPx()
                }
                isPlaceholderTrack = true
                isTrimEnabled = false
                maxDurationMs = totalSequenceDuration
                customMsPerPixel = 1.0f / pixelsPerMs
                activeStartMs = 0L
                activeEndMs = totalSequenceDuration
                setRange(totalSequenceDuration, 0L, totalSequenceDuration)
                trackLabel = "+ Filter / Effect"
                trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_effect_24)
                onTrackClicked = {
                    setActiveToolButton(R.id.btnEffect)
                    showColorFilterSelectionDialog(selectedVideoIndex ?: 0)
                }
            }
            effectTrackContainer.addView(placeholderView)
        }
    }

    private fun renderMosaicTracks(project: VideoProject, totalSequenceDuration: Long) {
        if (!::mosaicTrackContainer.isInitialized) return
        mosaicTrackContainer.removeAllViews()
        mosaicTrackContainer.visibility = View.VISIBLE
        val mosaicOps = project.operations.filterIsInstance<EditOperation.MosaicOverlay>()
        if (mosaicOps.isNotEmpty()) {
            for (op in mosaicOps) {
                val trackView = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                        topMargin = 3.dpToPx()
                    }
                    tag = op.id
                    isSelectedTrack = (op.id == viewModel.selectedOperationId.value)
                    isTrimEnabled = isSelectedTrack
                    maxDurationMs = totalSequenceDuration
                    customMsPerPixel = 1.0f / pixelsPerMs
                    trackColor = android.graphics.Color.parseColor("#00897B") // Distinct Teal for mosaic
                    trackLabel = "Mosaic (${if (op.shape == EditOperation.MaskShape.ELLIPSE) "Circle" else "Square"})"
                    trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_tool_mosaic)
                    val opStart = op.startTimeMs ?: 0L
                    val opEnd = op.endTimeMs ?: (opStart + 4000L).coerceAtMost(totalSequenceDuration)
                    minAllowedStartMs = 0L
                    maxAllowedEndMs = totalSequenceDuration
                    minDurationMs = 200L
                    activeStartMs = opStart
                    activeEndMs = opEnd
                    setRange(totalSequenceDuration, opStart, opEnd)
                    onTrackClicked = {
                        if (viewModel.selectedOperationId.value == op.id) {
                            viewModel.selectOperation(null)
                        } else {
                            viewModel.selectOperation(op.id)
                        }
                    }
                    onTrimChanged = { start, end, target ->
                        val currentOp = viewModel.project.value?.operations?.find {
                            (it as? EditOperation.MosaicOverlay)?.id == op.id
                        } as? EditOperation.MosaicOverlay
                        if (currentOp != null) {
                            viewModel.updateOperation(currentOp.copy(startTimeMs = start, endTimeMs = end))
                        }
                        val targetPos = if (target == com.eclip.rup.leb.customviews.TrackTrimView.DragTarget.RIGHT) end else start
                        seekToGlobalPosition(targetPos, force = true, isScrubbing = false)
                    }
                    onTrimAdjustingWithDelta = { start, end, deltaStart, deltaEnd ->
                        if (deltaStart != 0L) {
                            seekToGlobalPosition(start, isScrubbing = true)
                        } else if (deltaEnd != 0L) {
                            seekToGlobalPosition(end, isScrubbing = true)
                        } else {
                            seekToGlobalPosition(start, isScrubbing = true)
                        }
                    }
                    onDragStateChanged = { isDragging ->
                        isTrackDragging = isDragging
                        if (isDragging) {
                            handleTimelineDragStart()
                        } else {
                            onTimelineDragFinished()
                        }
                    }
                }
                mosaicTrackContainer.addView(trackView)
            }
        } else {
            // Full-length persistent placeholder track
            val placeholderView = com.eclip.rup.leb.customviews.TrackTrimView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dpToPx()).apply {
                    topMargin = 3.dpToPx()
                }
                isPlaceholderTrack = true
                isTrimEnabled = false
                maxDurationMs = totalSequenceDuration
                customMsPerPixel = 1.0f / pixelsPerMs
                activeStartMs = 0L
                activeEndMs = totalSequenceDuration
                setRange(totalSequenceDuration, 0L, totalSequenceDuration)
                trackLabel = "+ Mosaic"
                trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_tool_mosaic)
                onTrackClicked = {
                    setActiveToolButton(R.id.btnMosaic)
                    openMosaicEditingMode()
                }
            }
            mosaicTrackContainer.addView(placeholderView)
        }
    }

    private fun performRenderTracks(project: VideoProject) {
        if (isFinishing || isDestroyed || !::sequenceTrackContainer.isInitialized) {
            return
        }
        if (!sequenceTrackContainer.isAttachedToWindow) {
            sequenceTrackContainer.post {
                if (!isFinishing && !isDestroyed && ::sequenceTrackContainer.isInitialized) {
                    performRenderTracks(project)
                }
            }
            return
        }
        val currentUris = getSequenceItems().map { it.uri }.toSet()
        val toCancel = activeFrameExtractionJobs.keys.filter { it !in currentUris }
        for (uri in toCancel) {
            activeFrameExtractionJobs[uri]?.cancel()
            activeFrameExtractionJobs.remove(uri)
            activeExtractionUris.remove(uri)
        }

        activeRenderJobs.forEach { it.cancel() }
        activeRenderJobs.clear()
        activeSegmentViews.clear()

        val sequenceItems = getSequenceItems()
        val totalSequenceDuration = getTotalSequenceDuration()
        if (totalSequenceDuration <= 0L) {
            updateTimelineAddButtonPosition()
            return
        }

        val adaptiveScale = calculateAdaptivePixelsPerMs(totalSequenceDuration)
        if (pixelsPerMs <= 0f || pixelsPerMs > adaptiveScale) {
            pixelsPerMs = adaptiveScale
        }

        rebuildTimelineCache(project)

        // 1. Rebuild or verify ExoPlayer MediaSource
        rebuildExoPlayerSource(project)

        // 2. Update Layout Widths
        updateTrackWidths(totalSequenceDuration)

        // 3. Render Sequence Track
        sequenceTrackContainer.clipChildren = false
        sequenceTrackContainer.clipToPadding = false
        sequenceTrackContainer.removeAllViews()

        // Inflate and integrate Mute & Cover header directly preceding Clip 0 (on the left of the center playhead)
        val headerView = layoutInflater.inflate(R.layout.item_timeline_main_header, sequenceTrackContainer, false)
        timelineMainHeaderView = headerView
        val headerWidth = 88.dpToPx()
        val headerParams = FrameLayout.LayoutParams(headerWidth, 40.dpToPx()).apply {
            leftMargin = -headerWidth - 8.dpToPx()
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val headerBtnMute = headerView.findViewById<ImageView>(R.id.btnMute)
        val headerTvMute = headerView.findViewById<TextView>(R.id.tvMuteClipLabel)
        
        val videoIndices = sequenceItems.withIndex().filter { !it.value.isImage && !isImageUri(it.value.uri) }.map { it.index }
        
        val isCurrentlyMuted = if (videoIndices.isNotEmpty()) {
            val ops = viewModel.project.value?.operations?.filterIsInstance<EditOperation.MuteClip>() ?: emptyList()
            videoIndices.all { index ->
                ops.find { it.index == index }?.isMuted == true
            }
        } else {
            false
        }
        
        headerBtnMute?.setImageResource(if (isCurrentlyMuted) R.drawable.ic_volume_off_24 else R.drawable.ic_volume_up_24)
        headerTvMute?.text = if (isCurrentlyMuted) "Unmute" else "Mute"

        val headerMuteContainer = headerView.findViewById<View>(R.id.btnTimelineMuteContainer)
        headerMuteContainer?.setBounceClickListener {
            toggleMuteAction()
        }

        val headerBtnCover = headerView.findViewById<FrameLayout>(R.id.btnCover)
        val ivCover = headerView.findViewById<ImageView>(R.id.ivCoverThumbnail)
        val firstItem = sequenceItems.firstOrNull()
        if (firstItem != null) {
            val isFirstPhoto = firstItem.isImage || isImageUri(firstItem.uri)
            lifecycleScope.launch(Dispatchers.IO) {
                if (isFirstPhoto) {
                    val bmp = try {
                        val bounds = android.graphics.BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        contentResolver.openInputStream(firstItem.uri)?.use { input ->
                            android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                        }

                        val reqWidth = 160
                        val reqHeight = 160
                        var inSampleSize = 1
                        val rawHeight = bounds.outHeight
                        val rawWidth = bounds.outWidth
                        if (rawHeight > reqHeight || rawWidth > reqWidth) {
                            val halfHeight = rawHeight / 2
                            val halfWidth = rawWidth / 2
                            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                                inSampleSize *= 2
                            }
                        }

                        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                            this.inSampleSize = inSampleSize
                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        }
                        contentResolver.openInputStream(firstItem.uri)?.use { input ->
                            android.graphics.BitmapFactory.decodeStream(input, null, decodeOptions)
                        }
                    } catch (e: Exception) { null }
                    if (bmp != null) {
                        withContext(Dispatchers.Main) {
                            ivCover?.setImageBitmap(bmp)
                        }
                    }
                } else {
                    val path = getFilePathFromUri(firstItem.uri)
                    if (path != null) {
                        try {
                            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                            val bmp = android.graphics.BitmapFactory.decodeFile(path, options)
                            if (bmp != null) {
                                withContext(Dispatchers.Main) {
                                    ivCover?.setImageBitmap(bmp)
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        }
        headerBtnCover?.setBounceClickListener {
            Toast.makeText(this@VideoEditingActivity, "Cover frame updated from project", Toast.LENGTH_SHORT).show()
        }

        sequenceTrackContainer.addView(headerView, headerParams)

        var accumulatedStartMs = 0L
        val transitionViewsToLayout = mutableListOf<Pair<View, FrameLayout.LayoutParams>>()

        val metadataBuilder = PrecomputedTimelineMetadata.Builder()
        var accWorldX = 0f
        var accTime = 0L
        sequenceItems.forEachIndexed { index, item ->
            val segW = item.trimmedDurationMs * pixelsPerMs
            val isSelected = (selectedVideoIndex == index)
            var flags = TimelineClipFlags.FLAG_NONE
            if (isSelected) flags = flags or TimelineClipFlags.FLAG_SELECTED
            if (item.isImage || isImageUri(item.uri)) flags = flags or TimelineClipFlags.FLAG_IMAGE
            metadataBuilder.addClip(
                clipId = index.toLong(),
                startTimeMs = accTime,
                endTimeMs = accTime + item.trimmedDurationMs,
                leftPx = accWorldX,
                widthPx = segW,
                trackIndex = 0,
                heightPx = 40.dpToPx().toFloat(),
                clipFlags = flags
            )
            accWorldX += segW
            accTime += item.trimmedDurationMs
        }
        precomputedTimelineMetadata = metadataBuilder.build()

        sequenceItems.forEachIndexed { index, item ->
            val segmentView = layoutInflater.inflate(R.layout.item_sequence_segment, sequenceTrackContainer, false) as FrameLayout
            segmentView.clipChildren = false
            segmentView.clipToPadding = false
            val segmentWidth = (item.trimmedDurationMs * pixelsPerMs).toInt()
            val segmentLeft = (accumulatedStartMs * pixelsPerMs).toInt()
            val params = FrameLayout.LayoutParams(segmentWidth, 40.dpToPx()).apply {
                leftMargin = segmentLeft
            }
            segmentView.layoutParams = params
            activeSegmentViews.add(segmentView)

            val isPhoto = item.isImage || isImageUri(item.uri)
            val effectiveMaxDur = if (isPhoto) maxOf(item.durationMs, maxOf(item.trimEndMs, 600000L)) else item.durationMs
            val stretchedMaxDurationMs = (effectiveMaxDur / item.speed).toLong()
            val stretchedDurationMs = (item.durationMs / item.speed).toLong()
            val stretchedTrimStartMs = (item.trimStartMs / item.speed).toLong()
            val stretchedTrimEndMs = (item.trimEndMs / item.speed).toLong()

            val thumbStrip = segmentView.findViewById<com.eclip.rup.leb.customviews.timeline.ClipThumbStripView>(R.id.segmentClipThumbStrip)
            thumbStrip?.bindClip(
                uri = item.uri,
                durationUs = stretchedDurationMs * 1000L,
                inUs = stretchedTrimStartMs * 1000L,
                clipSpeed = item.speed,
                pxPerUs = pixelsPerMs / 1000f
            )

            val currentRenderMode = if (::adaptiveTimelineController.isInitialized) adaptiveTimelineController.currentMode else TimelineRenderMode.HIGH_FIDELITY

            // The visible ClipThumbStripView above renders strip frames through the scalable engine
            // with double-level virtualization. The legacy 15-frame RecyclerView inside this segment
            // stays hidden and is deliberately NOT fed frame decodes — decoding a fixed 15 frames
            // across an arbitrarily long media (e.g. 15 frames of a 12-hour video) is pure waste.
            val rv = segmentView.findViewById<RecyclerView>(R.id.segmentFrameRecyclerView)
            rv.setRecycledViewPool(sharedFrameViewPool)
            rv.setHasFixedSize(true)
            rv.setItemViewCacheSize(2)
            rv.isClickable = false
            rv.isFocusable = false
            rv.isNestedScrollingEnabled = false
            rv.setOnTouchListener { _, _ -> false }
            rv.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)

            val trackTrimView = segmentView.findViewById<com.eclip.rup.leb.customviews.TrackTrimView>(R.id.segmentTrimTrack)
            trackTrimView.isMainVideoTrack = true
            trackTrimView.trackColor = android.graphics.Color.TRANSPARENT
            trackTrimView.maxDurationMs = stretchedMaxDurationMs
            trackTrimView.minAllowedStartMs = 0L
            trackTrimView.maxAllowedEndMs = stretchedMaxDurationMs
            trackTrimView.minDurationMs = (200L / item.speed).toLong().coerceAtLeast(100L)
            trackTrimView.customMsPerPixel = 1.0f / pixelsPerMs
            trackTrimView.isSelectedTrack = (selectedVideoIndex == index)
            trackTrimView.isTrimEnabled = (selectedVideoIndex == index)
            trackTrimView.renderMode = currentRenderMode
            
            // Set the full untrimmed width on TrackTrimView and offset it
            val trackWidth = (stretchedMaxDurationMs * pixelsPerMs).toInt()
            trackTrimView.layoutParams = FrameLayout.LayoutParams(trackWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                leftMargin = -(stretchedTrimStartMs * pixelsPerMs).toInt()
            }
            
            trackTrimView.activeStartMs = stretchedTrimStartMs
            trackTrimView.activeEndMs = stretchedTrimEndMs
            trackTrimView.setRange(stretchedMaxDurationMs, stretchedTrimStartMs, stretchedTrimEndMs)
            
            // Selection highlight is drawn by TrackTrimView in the foreground
            segmentView.background = null
            
            val clipState = viewModel.clipStates.value[item.uri.toString()] ?: com.eclip.rup.leb.models.ClipState.READY
            if (clipState != com.eclip.rup.leb.models.ClipState.READY) {
                if (clipState == com.eclip.rup.leb.models.ClipState.FAILED) {
                    val overlay = TextView(this@VideoEditingActivity).apply {
                        text = "Failed"
                        setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
                        setTextColor(android.graphics.Color.WHITE)
                        gravity = android.view.Gravity.CENTER
                        textSize = 10f
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    segmentView.addView(overlay)
                } else {
                    val overlay = FrameLayout(this@VideoEditingActivity).apply {
                        setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        val indicator = com.eclip.rup.leb.customviews.ThreeDotLoadingView(this@VideoEditingActivity).apply {
                            setDotRadiusDp(3f)
                            setDotSpacingDp(9f)
                            setDotColor(android.graphics.Color.WHITE)
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.CENTER
                            )
                        }
                        addView(indicator)
                    }
                    segmentView.addView(overlay)
                }
                rv.alpha = 0.3f
                trackTrimView.alpha = 0.3f
            }
            
            if (!isPhoto && clipState == com.eclip.rup.leb.models.ClipState.READY) {
                val viewRef = trackTrimView
                val cachedAmps = uriToFilePathCache[item.uri.toString()]?.let {
                    com.eclip.rup.leb.utils.AudioWaveformExtractor.getCachedWaveform(it)
                }
                val clipHasAudio = initialTimelineClips?.find { it.uri == item.uri }?.hasAudio ?: true
                if (cachedAmps != null) {
                    viewRef.audioAmplitudes = cachedAmps
                    viewRef.isAudioTrack = true
                } else if (clipHasAudio && isVideoLoaded) {
                    val waveJob = lifecycleScope.launch(AppDispatchers.Waveform) {
                        val path = getFilePathFromUri(item.uri)
                        if (path != null) {
                            val knownHasAudio = com.eclip.rup.leb.utils.AudioWaveformExtractor.hasAudioStream(path)
                            if (knownHasAudio == false) return@launch

                            waveformExtractionSemaphore.acquire()
                            try {
                                val amps = com.eclip.rup.leb.utils.AudioWaveformExtractor.extractWaveform(this@VideoEditingActivity, path)
                                if (amps != null) {
                                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                                        viewRef.audioAmplitudes = amps
                                        viewRef.isAudioTrack = true 
                                        viewRef.invalidate()
                                    }
                                }
                            } finally {
                                waveformExtractionSemaphore.release()
                            }
                        }
                    }
                    activeRenderJobs.add(waveJob)
                }
            }
            
            trackTrimView.onTrimAdjustingWithDelta = { startMs, endMs, deltaL, deltaR ->
                val rawStart = (startMs * item.speed).toLong()
                val rawEnd = (endMs * item.speed).toLong()
                val seqItems = getSequenceItems()
                val clipStartGlobal = seqItems.take(index).sumOf { it.trimmedDurationMs }
                val currentTrimmedDur = (rawEnd - rawStart)
                val targetSeekPos = if (deltaL != 0L) {
                    clipStartGlobal
                } else {
                    clipStartGlobal + (currentTrimmedDur / item.speed).toLong()
                }
                seekToGlobalPosition(targetSeekPos, isScrubbing = true)

                trackTrimView.layoutParams = (trackTrimView.layoutParams as FrameLayout.LayoutParams).apply {
                    leftMargin = -(startMs * pixelsPerMs).toInt()
                }

                val segRv = segmentView.findViewById<RecyclerView>(R.id.segmentFrameRecyclerView)
                (segRv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, -(startMs * pixelsPerMs).toInt())

                val newSegmentWidth = (currentTrimmedDur * pixelsPerMs).toInt()
                
                segmentView.layoutParams = (segmentView.layoutParams as FrameLayout.LayoutParams).apply {
                    width = newSegmentWidth
                }
                segmentView.requestLayout()

                var runningLeft = (clipStartGlobal * pixelsPerMs).toInt() + newSegmentWidth
                for (i in (index + 1) until activeSegmentViews.size) {
                    val nextSegView = activeSegmentViews.getOrNull(i) ?: continue
                    val itemDur = seqItems.getOrNull(i)?.trimmedDurationMs ?: 0L
                    nextSegView.layoutParams = (nextSegView.layoutParams as FrameLayout.LayoutParams).apply {
                        leftMargin = runningLeft
                    }
                    nextSegView.requestLayout()
                    runningLeft += (itemDur * pixelsPerMs).toInt()
                }

                val newTotalDuration = seqItems.mapIndexed { idx, it ->
                    if (idx == index) currentTrimmedDur else it.trimmedDurationMs
                }.sum()
                val newTimelineWidth = (newTotalDuration * pixelsPerMs).toInt()
                
                timeRulerView.layoutParams = timeRulerView.layoutParams.apply {
                    width = newTimelineWidth
                }
                timeRulerView.setVideoDuration(newTotalDuration)
                timeRulerView.requestLayout()

                sequenceTrackContainer.layoutParams = sequenceTrackContainer.layoutParams.apply {
                    width = newTimelineWidth
                }
                sequenceTrackContainer.requestLayout()
            }

            trackTrimView.onTrimChanged = { startMs, endMs, target ->
                val rawStart = (startMs * item.speed).toLong()
                val rawEnd = (endMs * item.speed).toLong()
                if (index == 0) {
                    viewModel.updateMainVideoTrim(rawStart, rawEnd)
                } else {
                    viewModel.updateMergeItemTrim(index - 1, rawStart, rawEnd)
                }
                val seqItems = getSequenceItems()
                val clipStartGlobal = seqItems.take(index).sumOf { it.trimmedDurationMs }
                val targetPos = if (target == com.eclip.rup.leb.customviews.TrackTrimView.DragTarget.RIGHT) {
                    clipStartGlobal + ((rawEnd - rawStart) / item.speed).toLong()
                } else {
                    clipStartGlobal
                }
                seekToGlobalPosition(targetPos, force = true, isScrubbing = false)
                viewModel.project.value?.let { renderTracks(it, immediate = true) }
                updateUIInteractionState()
            }

            trackTrimView.onTrackClicked = {
                if (selectedVideoIndex == index) {
                    selectedVideoIndex = null
                    exitVideoEditingMode()
                } else {
                    selectedVideoIndex = index
                    enterVideoEditingMode()
                }
                updateTrackSelectionState()
            }
            
            segmentView.setBounceClickListener {
                if (selectedVideoIndex == index) {
                    selectedVideoIndex = null
                    exitVideoEditingMode()
                } else {
                    selectedVideoIndex = index
                    enterVideoEditingMode()
                }
                updateTrackSelectionState()
            }

            var lastTouchRawX = 0f
            val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: android.view.MotionEvent) {
                    startDragSession(segmentView, index, lastTouchRawX)
                }
            })

            val touchListener = View.OnTouchListener { v, event ->
                lastTouchRawX = event.rawX
                gestureDetector.onTouchEvent(event)
                if (isDraggingSegment && draggedView != null) {
                    if (event.action == android.view.MotionEvent.ACTION_MOVE) {
                        updateDragPosition(event.rawX)
                    } else if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                        endDragSession()
                    }
                    true
                } else {
                    false
                }
            }
            
            segmentView.setOnTouchListener(touchListener)
            trackTrimView.setOnTouchListener(touchListener)

            if (index > 0) {
                val transitionView = layoutInflater.inflate(R.layout.item_transition_button, sequenceTrackContainer, false)
                val btnTransition = transitionView.findViewById<ImageView>(R.id.btnTransition)
                
                // Highlight if a transition is selected
                val transitionOp = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Transition>().find { it.index == index - 1 }
                if (transitionOp != null && transitionOp.type != "none") {
                    btnTransition.setImageResource(R.drawable.ic_check_24)
                    btnTransition.setColorFilter(android.graphics.Color.WHITE)
                    transitionView.background = null
                } else {
                    btnTransition.setImageResource(R.drawable.ic_transition_divider_12)
                    btnTransition.colorFilter = null
                }
                
                val transitionWidth = 18.dpToPx()
                val transParams = FrameLayout.LayoutParams(transitionWidth, 18.dpToPx()).apply {
                    leftMargin = segmentLeft - transitionWidth / 2
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                transitionViewsToLayout.add(Pair(transitionView, transParams))
                transitionView.setOnClickListener {
                    showTransitionDialog(index - 1)
                }
            }

            sequenceTrackContainer.addView(segmentView)
            accumulatedStartMs += item.trimmedDurationMs
        }

        // Add transition buttons on top of segment views
        for ((view, params) in transitionViewsToLayout) {
            sequenceTrackContainer.addView(view, params)
        }

        // 4. Render Overlay Lanes
        renderPipTracks(project, totalSequenceDuration)
        renderAudioTracks(project, totalSequenceDuration)
        renderTextTracks(project, totalSequenceDuration)
        renderStickerTracks(totalSequenceDuration)
        renderEffectTracks(project, totalSequenceDuration)
        renderMosaicTracks(project, totalSequenceDuration)

        if (editorReadinessState != EditorReadinessState.READY) {
            checkEditorReadiness()
        }
        updateTimelineAddButtonPosition()
    }

    private fun applyAdaptiveRenderMode(mode: TimelineRenderMode) {
        for (segView in activeSegmentViews) {
            val rv = segView.findViewById<RecyclerView>(R.id.segmentFrameRecyclerView)
            (rv?.adapter as? FrameAdapter)?.setRenderMode(mode)
            val trimView = segView.findViewById<com.eclip.rup.leb.customviews.TrackTrimView>(R.id.segmentTrimTrack)
            trimView?.renderMode = mode
        }
        updateTrackContainerRenderModes(audioTrackContainer, mode)
        updateTrackContainerRenderModes(pipTrackContainer, mode)
        updateTrackContainerRenderModes(textTrackContainer, mode)
        updateTrackContainerRenderModes(stickerTrackContainer, mode)
        updateTrackContainerRenderModes(effectTrackContainer, mode)
        if (::mosaicTrackContainer.isInitialized) updateTrackContainerRenderModes(mosaicTrackContainer, mode)

        if (mode == TimelineRenderMode.HIGH_FIDELITY) {
            restoreVisibleViewportThumbnails()
        }
    }

    private fun updateTrackContainerRenderModes(container: ViewGroup, mode: TimelineRenderMode) {
        for (i in 0 until container.childCount) {
            (container.getChildAt(i) as? com.eclip.rup.leb.customviews.TrackTrimView)?.renderMode = mode
        }
    }

    private var lastRestoredVisibleRange: IntRange = IntRange.EMPTY
    private var lastRestoredGeneration: Long = -1L

    private fun restoreVisibleViewportThumbnails(
        generation: Long = if (::adaptiveTimelineController.isInitialized) adaptiveTimelineController.currentGeneration else 0L,
        force: Boolean = false
    ) {
        if (!::timelineHorizontalScroll.isInitialized || activeSegmentViews.isEmpty()) return
        val sequenceItems = getSequenceItems()
        if (sequenceItems.isEmpty()) return

        val scrollX = timelineHorizontalScroll.scrollX
        val viewportWidth = timelineHorizontalScroll.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

        // Zero-Layout Fast Viewport Range Lookup via Precomputed Metadata
        val visibleRange = if (precomputedTimelineMetadata.size == sequenceItems.size && precomputedTimelineMetadata.size > 0) {
            precomputedTimelineMetadata.findVisibleRange(scrollX.toFloat(), (scrollX + viewportWidth).toFloat())
        } else {
            var accLeft = 0
            val bounds = mutableListOf<Pair<Int, Int>>()
            sequenceItems.forEach { item ->
                val segWidth = (item.trimmedDurationMs * pixelsPerMs).toInt()
                bounds.add(Pair(accLeft, accLeft + segWidth))
                accLeft += segWidth
            }
            val list = adaptiveTimelineController.getVisibleSegmentIndices(scrollX, viewportWidth, bounds)
            if (list.isNotEmpty()) list.first()..list.last() else IntRange.EMPTY
        }

        if (visibleRange.isEmpty()) return
        if (!force && visibleRange == lastRestoredVisibleRange && generation == lastRestoredGeneration) return
        lastRestoredVisibleRange = visibleRange
        lastRestoredGeneration = generation

        // Feed the thumbnail priority scheduler with visible vs cushion clip keys. The scheduler
        // prunes stale in-flight thumbnail jobs and keeps priority ordering; actual frame decoding
        // is owned by ClipThumbStripView through ScalableThumbnailEngine.
        if (::thumbnailPriorityScheduler.isInitialized) {
            val visibleKeys = HashSet<String>()
            val cushionKeys = HashSet<String>()
            val cushionRange = (visibleRange.first - 2).coerceAtLeast(0)..(visibleRange.last + 2).coerceAtMost(sequenceItems.size - 1)

            for (idx in cushionRange) {
                val item = sequenceItems.getOrNull(idx) ?: continue
                val uriKey = item.uri.toString()
                if (idx in visibleRange) {
                    visibleKeys.add(uriKey)
                } else {
                    cushionKeys.add(uriKey)
                }
            }
            thumbnailPriorityScheduler.updateViewport(visibleKeys, cushionKeys, generation)
        }
    }

    private fun updateTimelineAddButtonPosition() {
        if (!::btnTimelineAdd.isInitialized) return
        if (::emptyProjectState.isInitialized && emptyProjectState.visibility == View.VISIBLE) {
            btnTimelineAdd.visibility = View.GONE
            return
        }
        if (!::timeRulerView.isInitialized || !::sequenceTrackContainer.isInitialized || !::timelineVerticalScroll.isInitialized) return

        val timelineWidth = timelineHorizontalScroll.width
        if (timelineWidth <= 0) return

        val halfWidth = timelineWidth / 2
        val totalDurationMs = timelineCache?.totalDuration?.takeIf { it > 0L } ?: getTotalSequenceDuration()
        val totalTrackWidthPx = halfWidth + (totalDurationMs * pixelsPerMs).toInt()

        val scrollX = timelineHorizontalScroll.scrollX

        // Position the button 8dp to the right of the end of the last clip
        val snapX = totalTrackWidthPx - scrollX + 8.dpToPx()

        val btnWidth = btnTimelineAdd.width.takeIf { it > 0 } ?: 32.dpToPx()
        val rightMargin = 16.dpToPx()
        val maxBtnX = timelineWidth - btnWidth - rightMargin

        // The button floats at the right end of the screen, unless the end of the last clip scrolls into view,
        // in which case it snaps to the end of the clip (snapX).
        val btnX = Math.min(snapX.toFloat(), maxBtnX.toFloat())

        // If the button is scrolled off-screen to the left, hide it
        if (btnX + btnWidth < 0) {
            btnTimelineAdd.visibility = View.GONE
        } else {
            btnTimelineAdd.visibility = View.VISIBLE
            btnTimelineAdd.translationX = btnX
        }

        // Vertically center on the main video track (sequenceTrackContainer)
        // Taking into account the timeRulerView and vertical scroll of the tracks
        val rulerHeight = timeRulerView.height.takeIf { it > 0 } ?: 24.dpToPx()
        val seqTrackTop = sequenceTrackContainer.top
        val seqTrackHeight = sequenceTrackContainer.height.takeIf { it > 0 } ?: 40.dpToPx()
        val btnHeight = btnTimelineAdd.height.takeIf { it > 0 } ?: 32.dpToPx()

        val btnY = rulerHeight + seqTrackTop - timelineVerticalScroll.scrollY + (seqTrackHeight - btnHeight) / 2

        // Check if the button is within the visible bounds of the timeline container
        val visibleTop = rulerHeight
        val visibleBottom = timelineContainer.height
        val btnCenterY = btnY + btnHeight / 2
        if (btnCenterY < visibleTop || btnCenterY > visibleBottom) {
            btnTimelineAdd.visibility = View.GONE
        } else {
            btnTimelineAdd.translationY = btnY.toFloat()
        }
    }

    private fun showVideoSegmentTrimDialog(index: Int, item: com.eclip.rup.leb.models.EditOperation.MergeItem) {
        if (isShowingPreview) dismissPreview()

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.trim_bottom_sheet_dialog, null)
        bottomSheet.setContentView(view)

        val tvDurationDisplay = view.findViewById<TextView>(R.id.tvTrimDuration)
        val trimTrackView = view.findViewById<com.eclip.rup.leb.customviews.TrackTrimView>(R.id.trimTrackView)
        val recyclerView = view.findViewById<RecyclerView>(R.id.trimRecyclerView)
        val btnDone = view.findViewById<Button>(R.id.btnDoneTrim)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseTrimSheet)

        // Configure RecyclerView for 15 dynamic tiles
        recyclerView.setRecycledViewPool(sharedFrameViewPool)
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(2)
        recyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        val dialogRecyclerViewWidth = resources.displayMetrics.widthPixels - 80.dpToPx()
        val dialogItemWidth = maxOf(1, dialogRecyclerViewWidth / 15)
        val adapter = FrameAdapter(emptyList(), dialogItemWidth)
        recyclerView.adapter = adapter

        val isPhoto = item.isImage || isImageUri(item.uri)
        val maxDur = if (isPhoto) maxOf(item.durationMs, 600000L) else item.durationMs

        if (isPhoto) {
            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = try {
                    contentResolver.openInputStream(item.uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input)
                    }
                } catch (e: Exception) { null }
                if (bmp != null) {
                    withContext(Dispatchers.Main) {
                        adapter.updateFrames(List(15) { bmp })
                    }
                }
            }
        } else {
            val job = extractFramesForSegment(item.uri, item.durationMs, adapter)
            if (job != null) {
                activeRenderJobs.add(job)
                recyclerView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) {
                        job.cancel()
                        activeRenderJobs.remove(job)
                    }
                })
            }
        }

        // Configure custom TrackTrimView as the premium Range Slider
        trimTrackView.isMainVideoTrack = true
        trimTrackView.isTrimEnabled = true
        trimTrackView.trackColor = android.graphics.Color.TRANSPARENT
        trimTrackView.maxDurationMs = maxDur
        trimTrackView.customMsPerPixel = maxDur.toFloat() / dialogRecyclerViewWidth

        trimTrackView.activeStartMs = item.trimStartMs
        trimTrackView.activeEndMs = item.trimEndMs
        trimTrackView.setRange(maxDur, item.trimStartMs, item.trimEndMs)
        
        if (!isPhoto) {
            val waveJob = lifecycleScope.launch(AppDispatchers.Waveform) {
                val path = getFilePathFromUri(item.uri)
                if (path != null) {
                    val knownHasAudio = com.eclip.rup.leb.utils.AudioWaveformExtractor.hasAudioStream(path)
                    if (knownHasAudio == false) return@launch

                    val amps = com.eclip.rup.leb.utils.AudioWaveformExtractor.extractWaveform(this@VideoEditingActivity, path)
                    if (amps != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            trimTrackView.audioAmplitudes = amps
                            trimTrackView.isAudioTrack = true
                            trimTrackView.invalidate()
                        }
                    }
                }
            }
            activeRenderJobs.add(waveJob)
        }

        var currentStart = item.trimStartMs
        var currentEnd = item.trimEndMs

        fun updateTimeText(start: Long, end: Long) {
            val duration = end - start
            tvDurationDisplay.text = "${formatDuration(start.toInt())} - ${formatDuration(end.toInt())} (${formatDuration(duration.toInt())})"
        }
        updateTimeText(item.trimStartMs, item.trimEndMs)

        trimTrackView.onTrimAdjustingWithDelta = { start, end, deltaL, deltaR ->
            currentStart = start
            currentEnd = end
            updateTimeText(start, end)

            val sequenceItems = getSequenceItems()
            val clipStartGlobal = sequenceItems.take(index).sumOf { it.trimmedDurationMs }
            if (deltaL != 0L) {
                seekToGlobalPosition(clipStartGlobal + start)
            } else if (deltaR != 0L) {
                seekToGlobalPosition(clipStartGlobal + end)
            }
        }

        trimTrackView.onTrimChanged = { start, end, _ ->
            currentStart = start
            currentEnd = end
            updateTimeText(start, end)
        }

        btnDone.setBounceClickListener {
            if (index == 0) {
                viewModel.updateMainVideoTrim(currentStart, currentEnd)
            } else {
                viewModel.updateMergeItemTrim(index - 1, currentStart, currentEnd)
            }
            bottomSheet.dismiss()
        }

        btnClose.setBounceClickListener {
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    private fun updateVideoMuteButtonState(toolbar: View, index: Int) {
        val isMuted = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.MuteClip>()
            ?.find { it.index == index }?.isMuted ?: false
        
        val btnMute = toolbar.findViewById<ImageButton>(R.id.btnVideoMute)
        val tvMuteLabel = toolbar.findViewById<TextView>(R.id.tvVideoMuteLabel)
        
        if (isMuted) {
            btnMute?.setImageResource(R.drawable.ic_volume_off_24)
            tvMuteLabel?.text = "Unmute"
        } else {
            btnMute?.setImageResource(R.drawable.ic_volume_up_24)
            tvMuteLabel?.text = "Mute"
        }
    }

    private fun showColorFilterSelectionDialog(clipIndex: Int) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_color_filters, null)
        bottomSheet.setContentView(view)

        val filtersList = view.findViewById<LinearLayout>(R.id.colorFiltersList)
        val project = viewModel.project.value ?: return
        val existingOp = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.ColorFilter>().find { it.index == clipIndex }
        var activeFilterName = existingOp?.filterName ?: "none"

        val filters = listOf(
            Pair("none", "None"),
            Pair("vintage", "Vintage"),
            Pair("warm", "Warm"),
            Pair("cool", "Cool"),
            Pair("contrast", "Contrast"),
            Pair("monochrome", "B&W"),
            Pair("vignette", "Vignette"),
            Pair("negative", "Negative"),
            Pair("crossprocess", "Cross P")
        )

        val itemBgs = mutableMapOf<String, FrameLayout>()
        val itemTvShorts = mutableMapOf<String, TextView>()

        fun refreshSelectionStates() {
            for ((id, bgFrame) in itemBgs) {
                val tvShort = itemTvShorts[id]
                val isSelected = (id == activeFilterName)
                if (isSelected) {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                    bgFrame.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_transition_border_selected)
                    tvShort?.setTextColor(android.graphics.Color.WHITE)
                } else {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                    bgFrame.foreground = null
                    tvShort?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textColor))
                }
            }
        }

        for ((filterId, displayName) in filters) {
            val itemView = layoutInflater.inflate(R.layout.item_color_filter_option, filtersList, false)
            val tvName = itemView.findViewById<TextView>(R.id.filterName)
            val bg = itemView.findViewById<FrameLayout>(R.id.filterIconBg)
            val previewImage = itemView.findViewById<ImageView>(R.id.filterPreviewImage)
            val tvShort = itemView.findViewById<TextView>(R.id.filterShortName)

            itemBgs[filterId] = bg
            itemTvShorts[filterId] = tvShort

            tvName.text = displayName
            bg.clipToOutline = true

            if (filterId == "none") {
                previewImage.visibility = View.GONE
                tvShort.visibility = View.VISIBLE
                tvShort.text = "✖"
            } else {
                previewImage.visibility = View.VISIBLE
                tvShort.visibility = View.GONE
                val resName = "filter_preview_${filterId.lowercase()}"
                val resId = resources.getIdentifier(resName, "drawable", packageName)
                if (resId != 0) {
                    previewImage.setImageResource(resId)
                }
            }

            itemView.setOnClickListener {
                activeFilterName = filterId
                refreshSelectionStates()
                viewModel.setColorFilter(clipIndex, filterId)
                applyColorFilterToPlayer(filterId)
            }
            
            filtersList.addView(itemView)
        }

        refreshSelectionStates()

        view.findViewById<View>(R.id.btnApplyToAll)?.setOnClickListener {
            val chunkCount = chunkDurationsMs.size
            for (i in 0 until chunkCount) {
                viewModel.setColorFilter(i, activeFilterName)
            }
            applyColorFilterToPlayer(activeFilterName)
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
        }

        view.findViewById<View>(R.id.btnDone)?.setOnClickListener {
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    private fun showTransitionDialog(transitionIndex: Int) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_transitions, null)
        bottomSheet.setContentView(view)

        val transitionsList = view.findViewById<LinearLayout>(R.id.transitionsList)
        val project = viewModel.project.value ?: return
        val existingOp = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Transition>().find { it.index == transitionIndex }
        var activeTransitionType = existingOp?.type ?: "none"
        var activeDurationMs = existingOp?.durationMs ?: 1000L

        fun applyTransitionToIndex(idx: Int, transType: String, durationMs: Long = activeDurationMs) {
            val proj = viewModel.project.value ?: return
            val existing = proj.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Transition>().find { it.index == idx }
            if (transType == "none") {
                if (existing != null) {
                    viewModel.removeOperation(existing.id)
                }
            } else {
                if (existing != null) {
                    viewModel.updateOperation(existing.copy(type = transType, durationMs = durationMs))
                } else {
                    viewModel.addTransitionOperation(idx, transType, durationMs)
                }
            }
        }

        val sliderDuration = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderTransitionDuration)
        val tvDurationVal = view.findViewById<TextView>(R.id.tvTransitionDurationValue)

        val durationSec = (activeDurationMs / 1000.0f).coerceIn(0.1f, 3.0f)
        sliderDuration?.value = (Math.round(durationSec * 10f) / 10f).coerceIn(0.1f, 3.0f)
        tvDurationVal?.text = String.format(java.util.Locale.US, "%.1fs", durationSec)

        sliderDuration?.addOnChangeListener { _, value, fromUser ->
            activeDurationMs = (value * 1000f).toLong()
            tvDurationVal?.text = String.format(java.util.Locale.US, "%.1fs", value)
            if (fromUser && activeTransitionType != "none") {
                applyTransitionToIndex(transitionIndex, activeTransitionType, activeDurationMs)
            }
        }

        val transitions = listOf(
            Pair("none", "None"),
            Pair("fade", "Fade"),
            Pair("fadeblack", "Fade Black"),
            Pair("fadewhite", "Fade White"),
            Pair("dissolve", "Dissolve"),
            Pair("wipeleft", "Wipe L"),
            Pair("wiperight", "Wipe R"),
            Pair("wipeup", "Wipe Up"),
            Pair("wipedown", "Wipe Down"),
            Pair("slideleft", "Slide L"),
            Pair("slideright", "Slide R"),
            Pair("slideup", "Slide Up"),
            Pair("slidedown", "Slide Down"),
            Pair("coverleft", "Cover L"),
            Pair("coverright", "Cover R"),
            Pair("circlecrop", "Circle"),
            Pair("rectcrop", "Box"),
            Pair("zoomin", "Zoom In"),
            Pair("pixelize", "Pixelize"),
            Pair("hlslice", "H-Slice"),
            Pair("vuslice", "V-Slice")
        )

        val itemBgs = mutableMapOf<String, FrameLayout>()
        val itemTvShorts = mutableMapOf<String, TextView>()

        fun refreshSelectionStates() {
            for ((typeKey, bgFrame) in itemBgs) {
                val tvShort = itemTvShorts[typeKey]
                val isSelected = (typeKey == activeTransitionType)
                if (isSelected) {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                    bgFrame.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_transition_border_selected)
                    tvShort?.setTextColor(android.graphics.Color.WHITE)
                } else {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                    bgFrame.foreground = null
                    tvShort?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textColor))
                }
            }
        }

        for ((type, name) in transitions) {
            val itemView = layoutInflater.inflate(R.layout.item_transition_option, transitionsList, false)
            val tvName = itemView.findViewById<TextView>(R.id.transitionName)
            val tvShort = itemView.findViewById<TextView>(R.id.transitionShortName)
            val bg = itemView.findViewById<FrameLayout>(R.id.transitionIconBg)

            itemBgs[type] = bg
            itemTvShorts[type] = tvShort

            tvName.text = name
            bg.clipToOutline = true

            val ivPreview = itemView.findViewById<ImageView>(R.id.transitionPreviewImage)
            val startDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.trans_frame_start)
            val endDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.trans_frame_end)

            if (startDrawable != null && endDrawable != null) {
                if (type == "none") {
                    ivPreview.setImageDrawable(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_close_24))
                    ivPreview.visibility = View.VISIBLE
                    tvShort.visibility = View.GONE
                } else {
                    val animation = android.graphics.drawable.AnimationDrawable().apply {
                        isOneShot = false
                    }
                    val frame1Res = resources.getIdentifier("trans_preview_${type}_1", "drawable", packageName)
                    val frame2Res = resources.getIdentifier("trans_preview_${type}_2", "drawable", packageName)
                    val frame3Res = resources.getIdentifier("trans_preview_${type}_3", "drawable", packageName)

                    if (frame1Res != 0 && frame2Res != 0 && frame3Res != 0) {
                        val f1 = androidx.core.content.ContextCompat.getDrawable(this, frame1Res)
                        val f2 = androidx.core.content.ContextCompat.getDrawable(this, frame2Res)
                        val f3 = androidx.core.content.ContextCompat.getDrawable(this, frame3Res)

                        if (f1 != null && f2 != null && f3 != null) {
                            animation.addFrame(startDrawable, 600)
                            animation.addFrame(f1, 100)
                            animation.addFrame(f2, 100)
                            animation.addFrame(f3, 100)
                            animation.addFrame(endDrawable, 600)

                            ivPreview.setImageDrawable(animation)
                            ivPreview.visibility = View.VISIBLE
                            tvShort.visibility = View.GONE
                            ivPreview.post { animation.start() }
                        } else {
                            ivPreview.visibility = View.GONE
                            tvShort.visibility = View.VISIBLE
                            tvShort.text = name.substring(0, minOf(2, name.length)).uppercase()
                        }
                    } else {
                        ivPreview.visibility = View.GONE
                        tvShort.visibility = View.VISIBLE
                        tvShort.text = name.substring(0, minOf(2, name.length)).uppercase()
                    }
                }
            } else {
                ivPreview.visibility = View.GONE
                tvShort.visibility = View.VISIBLE
                tvShort.text = name.substring(0, minOf(2, name.length)).uppercase()
            }

            itemView.setOnClickListener {
                activeTransitionType = type
                refreshSelectionStates()
                applyTransitionToIndex(transitionIndex, type, activeDurationMs)
            }

            transitionsList.addView(itemView)
        }

        refreshSelectionStates()

        view.findViewById<View>(R.id.btnApplyToAll)?.setOnClickListener {
            val transitionsCount = chunkDurationsMs.size - 1
            for (i in 0 until transitionsCount) {
                applyTransitionToIndex(i, activeTransitionType, activeDurationMs)
            }
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
        }

        view.findViewById<View>(R.id.btnDone)?.setOnClickListener {
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    private fun setupCustomSeeker() {
        customVideoSeeker.onSeekListener = { seekPosition ->
            // Dismiss preview when user manually seeks
            if (isShowingPreview) dismissPreview()

            val totalDuration = getTotalSequenceDuration()
            val newSeekTime = (totalDuration * seekPosition).toLong()
            if (newSeekTime >= 0 && newSeekTime <= totalDuration) {
                seekToGlobalPosition(newSeekTime)
                updateDurationDisplay(newSeekTime.toInt(), totalDuration.toInt())
                
                textOverlayView?.currentPositionMs = newSeekTime
                imageOverlayView?.currentPositionMs = newSeekTime
                mosaicOverlayView?.currentPositionMs = newSeekTime
            } else {
                Log.d("SeekError", "Seek position out of bounds.")
            }
        }
    }

    private fun setupTrackInitializers() {
        // Nothing here anymore since sequence track is dynamic
    }

    // Inside VideoEditingActivity

    private fun extractFramesForSegment(uri: Uri, durationMs: Long, adapter: FrameAdapter): kotlinx.coroutines.Job? {
        if (durationMs <= 0) return null
        
        val uriKey = uri.toString()
        // Tier 1: Instant Memory Cache (LruCache) check on Main Thread
        val memCached = ThumbnailCacheManager.getMemoryFrameSequence(uriKey, 15, 120, 90)
        if (memCached != null) {
            if (adapter.hasObservers()) {
                adapter.updateFrames(memCached)
            }
            updateCoverThumbnail(memCached.firstOrNull())
            return null
        }

        if (activeExtractionUris.contains(uri)) {
            return lifecycleScope.launch {
                var waitCount = 0
                while (activeExtractionUris.contains(uri) && ThumbnailCacheManager.getMemoryFrameSequence(uriKey, 15, 120, 90) == null && waitCount < 100) {
                    kotlinx.coroutines.delay(300)
                    waitCount++
                }
                val frames = ThumbnailCacheManager.getMemoryFrameSequence(uriKey, 15, 120, 90)
                    ?: ThumbnailCacheManager.getFrameSequenceAsync(this@VideoEditingActivity, uriKey, 15, 120, 90)
                if (frames != null && adapter.hasObservers() && isActive && !isFinishing && !isDestroyed) {
                    adapter.updateFrames(frames)
                    updateCoverThumbnail(frames.firstOrNull())
                }
            }
        }

        activeExtractionUris.add(uri)
        activeExtractionCount++
        if (isImportLoading) {
            showLoading(getString(R.string.loading), getString(R.string.loading_tag))
        }

        val job = lifecycleScope.launch(AppDispatchers.Thumbnail) {
            // Tier 2: Asynchronous Disk Cache check on worker thread
            val diskCached = ThumbnailCacheManager.getFrameSequence(this@VideoEditingActivity, uriKey, 15, 120, 90)
            if (diskCached != null) {
                withContext(Dispatchers.Main) {
                    if (adapter.hasObservers() && coroutineContext.isActive && !isFinishing && !isDestroyed) {
                        adapter.updateFrames(diskCached)
                        updateCoverThumbnail(diskCached.firstOrNull())
                    }
                }
                return@launch
            }

            // Tier 3: Fresh Background Frame Extraction via MediaMetadataRetriever
            frameExtractionSemaphore.acquire()
            val retriever = MediaMetadataRetriever()
            val tempFrames = mutableListOf<Bitmap>()
            try {
                coroutineContext.ensureActive()
                val path = getFilePathFromUri(uri)
                if (path != null) {
                    retriever.setDataSource(path)
                } else {
                    retriever.setDataSource(this@VideoEditingActivity, uri)
                }

                val frameCount = 15
                val intervalUs = (durationMs * 1000) / frameCount

                for (i in 0 until frameCount) {
                    coroutineContext.ensureActive()
                    if (!coroutineContext.isActive) break

                    // Yield and pause during user scrolling or dragging to prioritize UI gesture and ExoPlayer seek
                    while (isUserScrollingTimeline && coroutineContext.isActive) {
                        kotlinx.coroutines.delay(100)
                    }

                    val timeUs = i * intervalUs
                    val frameKey = "${uriKey}_idx${i}_120x90"
                    val cachedBmp = ThumbnailCacheManager.getThumbnail(this@VideoEditingActivity, frameKey)
                    val finalBitmap = if (cachedBmp != null && !cachedBmp.isRecycled) {
                        cachedBmp
                    } else {
                        val bitmap = com.eclip.rup.leb.utils.CodecInstancePool.withHardwareCodecPermit {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 90)
                            } else {
                                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            }
                        }
                        val processed = bitmap?.let {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) it else processBitmap(it)
                        }
                        if (processed != null) {
                            ThumbnailCacheManager.putThumbnail(this@VideoEditingActivity, frameKey, processed)
                        }
                        processed
                    }

                    finalBitmap?.let {
                        tempFrames.add(it)
                        val progressPercent = ((i + 1) * 100) / frameCount
                        withContext(Dispatchers.Main) {
                            if (adapter.hasObservers() && coroutineContext.isActive && !isFinishing && !isDestroyed) {
                                adapter.addFrame(it)
                                if (i == 0) {
                                    updateCoverThumbnail(it)
                                }
                                if (isImportLoading) {
                                    updateLoadingProgress(progressPercent)
                                }
                            }
                        }
                    }

                    // Yield breathing room between frames on long media to avoid saturating Binder and MediaServer IPC
                    if (durationMs > 60_000L) {
                        kotlinx.coroutines.delay(40)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (tempFrames.isNotEmpty()) {
                        ThumbnailCacheManager.putFrameSequence(this@VideoEditingActivity, uriKey, tempFrames, 120, 90)
                        updateCoverThumbnail(tempFrames.firstOrNull())
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Extraction error for segment: ${e.message}")
                }
            } finally {
                retriever.release()
                frameExtractionSemaphore.release()
                withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.Main) {
                        activeExtractionUris.remove(uri)
                        activeFrameExtractionJobs.remove(uri)
                        activeExtractionCount--
                        if (activeExtractionCount <= 0) {
                            activeExtractionCount = 0
                        }
                    }
                }
            }
        }
        activeFrameExtractionJobs[uri] = job
        return job
    }

    private fun extractPhotoForSegment(uri: Uri, adapter: FrameAdapter): kotlinx.coroutines.Job? {
        val uriKey = uri.toString()
        val memCached = ThumbnailCacheManager.getMemoryThumbnail(uriKey)
        if (memCached != null && !memCached.isRecycled) {
            if (adapter.hasObservers()) {
                adapter.updateFrames(List(15) { memCached })
            }
            return null
        }

        if (activeExtractionUris.contains(uri)) {
            return lifecycleScope.launch {
                while (activeExtractionUris.contains(uri) && ThumbnailCacheManager.getMemoryThumbnail(uriKey) == null) {
                    kotlinx.coroutines.delay(100)
                }
                val bmp = ThumbnailCacheManager.getMemoryThumbnail(uriKey)
                    ?: ThumbnailCacheManager.getThumbnailAsync(this@VideoEditingActivity, uriKey)
                if (bmp != null && !bmp.isRecycled && adapter.hasObservers() && isActive && !isFinishing && !isDestroyed) {
                    adapter.updateFrames(List(15) { bmp })
                }
            }
        }

        activeExtractionUris.add(uri)
        val job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cachedBmp = ThumbnailCacheManager.getThumbnail(this@VideoEditingActivity, uriKey)
                val bmp = if (cachedBmp != null && !cachedBmp.isRecycled) {
                    cachedBmp
                } else {
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, boundsOptions)
                    }

                    val reqW = 120
                    val reqH = 90
                    var sampleSize = 1
                    if (boundsOptions.outHeight > reqH || boundsOptions.outWidth > reqW) {
                        val halfHeight = boundsOptions.outHeight / 2
                        val halfWidth = boundsOptions.outWidth / 2
                        while ((halfHeight / sampleSize) >= reqH && (halfWidth / sampleSize) >= reqW) {
                            sampleSize *= 2
                        }
                    }

                    val candidate = BitmapPool.acquire(reqW, reqH, Bitmap.Config.ARGB_8888)
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inMutable = true
                        if (candidate != null) inBitmap = candidate
                    }

                    val decoded = contentResolver.openInputStream(uri)?.use { input ->
                        try {
                            BitmapFactory.decodeStream(input, null, decodeOptions)
                        } catch (e: IllegalArgumentException) {
                            BitmapPool.release(candidate)
                            val cleanOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize; inMutable = true }
                            contentResolver.openInputStream(uri)?.use { stream ->
                                BitmapFactory.decodeStream(stream, null, cleanOpts)
                            }
                        }
                    }
                    if (decoded != null) {
                        ThumbnailCacheManager.putThumbnail(this@VideoEditingActivity, uriKey, decoded)
                    }
                    decoded
                }
                if (bmp != null && !bmp.isRecycled) {
                    withContext(Dispatchers.Main) {
                        if (adapter.hasObservers() && coroutineContext.isActive && !isFinishing && !isDestroyed) {
                            adapter.updateFrames(List(15) { bmp })
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore extraction exceptions
            } finally {
                withContext(Dispatchers.Main) {
                    activeExtractionUris.remove(uri)
                    activeFrameExtractionJobs.remove(uri)
                }
            }
        }
        activeFrameExtractionJobs[uri] = job
        return job
    }

    // Helper to keep scaling logic clean and maintain aspect ratio
    private fun processBitmap(source: Bitmap): Bitmap {
        val aspectRatio = source.width.toFloat() / source.height.toFloat()
        val targetHeight = 90
        val targetWidth = (targetHeight * aspectRatio).toInt()
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun updateCoverThumbnail(bitmap: Bitmap?) {
        val ivCover = findViewById<ImageView>(R.id.ivCoverThumbnail) ?: return
        if (bitmap != null && !bitmap.isRecycled) {
            ivCover.setImageBitmap(bitmap)
        }
    }

    private fun formatDuration(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatDurationPrecise(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val tenths = (milliseconds % 1000) / 100
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d.%d", hours, minutes, seconds, tenths)
        } else {
            String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDurationDisplay(current: Int, total: Int) {
        if (total <= 0) return
        tvDuration.text = "${formatDuration(current)} / ${formatDuration(total)}"
        if (::tvFullscreenCurrentTime.isInitialized) {
            tvFullscreenCurrentTime.text = formatDuration(current)
        }
        if (::tvFullscreenTotalTime.isInitialized) {
            tvFullscreenTotalTime.text = formatDuration(total)
        }
        if (::sbFullscreenSeeker.isInitialized && !isUserScrubbingFullscreenSeeker) {
            val progress = ((current.toFloat() / total.toFloat()) * 1000).toInt().coerceIn(0, 1000)
            sbFullscreenSeeker.progress = progress
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFullscreenAndPipControls() {
        btnFullscreen.setBounceClickListener {
            enterFullscreenMode()
        }

        btnExitFullscreen.setBounceClickListener {
            exitFullscreenMode()
        }
        btnExitFullscreenTop.setBounceClickListener {
            exitFullscreenMode()
        }

        btnFullscreenPlayPause.setBounceClickListener {
            if (::player.isInitialized && isVideoLoaded) {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    val totalDuration = getTotalSequenceDuration()
                    val currentGlobalPos = getGlobalPosition()
                    if (currentGlobalPos >= totalDuration - 100L || player.playbackState == Player.STATE_ENDED) {
                        seekToGlobalPosition(0L)
                        timelineHorizontalScroll.scrollTo(0, 0)
                        updateDurationDisplay(0, totalDuration.toInt())
                    }
                    player.play()
                }
            }
            showFullscreenControlsTemporarily()
        }

        fullscreenOverlay.setOnClickListener {
            if (fullscreenControls.visibility == View.VISIBLE && fullscreenControls.alpha > 0.5f) {
                fullscreenHideHandler.removeCallbacks(hideFullscreenControlsRunnable)
                fullscreenControls.animate().alpha(0f).setDuration(250).withEndAction {
                    fullscreenControls.visibility = View.GONE
                }.start()
            } else {
                showFullscreenControlsTemporarily()
            }
        }

        sbFullscreenSeeker.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val totalMs = getTotalSequenceDuration()
                    if (totalMs > 0) {
                        val targetMs = ((progress.toFloat() / 1000f) * totalMs).toLong()
                        seekToGlobalPosition(targetMs)
                        try {
                            isProgrammaticScroll = true
                            timelineHorizontalScroll.scrollTo((targetMs * pixelsPerMs).toInt(), 0)
                        } finally {
                            isProgrammaticScroll = false
                        }
                        tvFullscreenCurrentTime.text = formatDuration(targetMs.toInt())
                        tvDuration.text = "${formatDuration(targetMs.toInt())} / ${formatDuration(totalMs.toInt())}"
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserScrubbingFullscreenSeeker = true
                fullscreenHideHandler.removeCallbacks(hideFullscreenControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserScrubbingFullscreenSeeker = false
                showFullscreenControlsTemporarily()
            }
        })

        btnExpandTimeline.setBounceClickListener {
            toggleTimelineExpandedMode()
        }

        var pipInitialX = 0f
        var pipInitialY = 0f
        var touchStartX = 0f
        var touchStartY = 0f

        pipPlayerContainer.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pipInitialX = view.x
                    pipInitialY = view.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    val parent = view.parent as? View ?: return@setOnTouchListener false
                    val maxW = (parent.width - view.width).toFloat()
                    val maxH = (parent.height - view.height).toFloat()
                    val newX = (pipInitialX + dx).coerceIn(0f, maxW)
                    val newY = (pipInitialY + dy).coerceIn(0f, maxH)
                    view.x = newX
                    view.y = newY
                    true
                }
                else -> false
            }
        }
    }

    private fun reparentCanvasContainer(targetContainer: ViewGroup) {
        val parent = canvasContainer.parent as? ViewGroup
        if (parent != targetContainer) {
            parent?.removeView(canvasContainer)
            targetContainer.addView(canvasContainer, 0, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        targetContainer.post {
            triggerCanvasLayoutUpdate()
        }
    }

    private fun triggerCanvasLayoutUpdate() {
        val cropOps = viewModel.project.value?.operations?.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Crop>()
        if (cropOps != null && cropOps.isNotEmpty() && cropEditingToolbar?.visibility != View.VISIBLE) {
            applyCropPreview(cropOps.last().aspectRatio)
        } else {
            resetCropPreview()
        }
    }

    private fun showFullscreenControlsTemporarily() {
        fullscreenHideHandler.removeCallbacks(hideFullscreenControlsRunnable)
        fullscreenControls.visibility = View.VISIBLE
        fullscreenControls.animate().cancel()
        fullscreenControls.alpha = 1f
        fullscreenHideHandler.postDelayed(hideFullscreenControlsRunnable, 3500)
    }

    private fun enterFullscreenMode() {
        isFullscreenMode = true
        fullscreenOverlay.visibility = View.VISIBLE
        fullscreenControls.visibility = View.VISIBLE
        fullscreenControls.alpha = 1f
        reparentCanvasContainer(fullscreenCanvasHolder)
        showFullscreenControlsTemporarily()
    }

    private fun exitFullscreenMode() {
        isFullscreenMode = false
        fullscreenHideHandler.removeCallbacks(hideFullscreenControlsRunnable)
        fullscreenOverlay.visibility = View.GONE
        if (isTimelineExpanded) {
            reparentCanvasContainer(pipPlayerContainer)
        } else {
            reparentCanvasContainer(playerContainer)
        }
    }

    private fun toggleTimelineExpandedMode() {
        isTimelineExpanded = !isTimelineExpanded
        val seekerContainer = findViewById<LinearLayout>(R.id.seekerContainer)

        if (isTimelineExpanded) {
            btnExpandTimeline.setImageResource(R.drawable.ic_collapse_24)
            playerContainer.visibility = View.GONE
            pipPlayerContainer.visibility = View.VISIBLE
            reparentCanvasContainer(pipPlayerContainer)

            val lpSeeker = seekerContainer.layoutParams as LinearLayout.LayoutParams
            lpSeeker.height = 0
            lpSeeker.weight = 1.0f
            seekerContainer.layoutParams = lpSeeker

            val lpTimeline = timelineContainer.layoutParams as LinearLayout.LayoutParams
            lpTimeline.height = 0
            lpTimeline.weight = 1.0f
            timelineContainer.layoutParams = lpTimeline
        } else {
            btnExpandTimeline.setImageResource(R.drawable.ic_expand_24)
            pipPlayerContainer.visibility = View.GONE
            playerContainer.visibility = View.VISIBLE
            reparentCanvasContainer(playerContainer)

            val lpSeeker = seekerContainer.layoutParams as LinearLayout.LayoutParams
            lpSeeker.height = LinearLayout.LayoutParams.WRAP_CONTENT
            lpSeeker.weight = 0f
            seekerContainer.layoutParams = lpSeeker

            val lpTimeline = timelineContainer.layoutParams as LinearLayout.LayoutParams
            lpTimeline.height = 225.dpToPx()
            lpTimeline.weight = 0f
            timelineContainer.layoutParams = lpTimeline
        }
    }

    private fun showError(error: String) {
        Log.e(TAG, error)
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
    }

    private fun saveVideoToGallery(videoFile: File): Uri? {
        val isAudioOnly = videoFile.name.endsWith(".mp3")
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

        // Fallback or Default to Movies/Eclip
        return try {
            val contentValues = android.content.ContentValues().apply {
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

    private fun processConcatList(concatList: String): String {
        val lines = concatList.trim().split("\n")
        val processedLines = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith("file")) {
                val pathStart = line.indexOf("'") + 1
                val pathEnd = line.lastIndexOf("'")
                if (pathStart > 0 && pathEnd > pathStart) {
                    val filePath = line.substring(pathStart, pathEnd)
                    val processedPath = if (filePath.startsWith("content://")) {
                        copyContentUriToTempFile(Uri.parse(filePath))?.absolutePath ?: filePath
                    } else {
                        filePath
                    }
                    processedLines.add("file '$processedPath'")
                }
            } else if (line.isNotEmpty()) {
                processedLines.add(line)
            }
        }

        return processedLines.joinToString("\n").trim() + "\n"
    }

    private fun getAudioExtension(uri: Uri): String {
        val mimeType = contentResolver.getType(uri)
        if (mimeType != null) {
            val mime = android.webkit.MimeTypeMap.getSingleton()
            val extension = mime.getExtensionFromMimeType(mimeType)
            if (extension != null) {
                return ".$extension"
            }
        }
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        val lastDot = name.lastIndexOf('.')
                        if (lastDot != -1) {
                            return name.substring(lastDot)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ".mp3"
    }

    private fun copyContentUriToTempFile(contentUri: Uri): File? {
        val ext = getExtensionFromUri(contentUri) ?: ".mp4"
        return copyContentUriToTempFile(contentUri, "merge_video", ext)
    }

    private fun copyContentUriToTempFile(contentUri: Uri, prefix: String, extension: String = ".mp4"): File? {
        return try {
            val ext = if (prefix == "audio") {
                getAudioExtension(contentUri)
            } else {
                extension
            }
            val assetsDir = DraftManager.getProjectAssetsDirectory(this)
            val tempFile = File(assetsDir, "${prefix}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}$ext")
            val inputStream = if (contentUri.scheme == "file") {
                // file:// URIs (from fallback directory scanner) must be opened directly;
                // ContentResolver cannot open them on Android 7+.
                val filePath = contentUri.path
                if (filePath != null) java.io.FileInputStream(filePath) else null
            } else {
                contentResolver.openInputStream(contentUri)
            }
            if (inputStream == null) {
                Log.e(TAG, "openInputStream returned null for URI: $contentUri")
                return null
            }
            inputStream.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (tempFile.length() == 0L) {
                Log.e(TAG, "Copied file is empty for URI: $contentUri")
                tempFile.delete()
                return null
            }
            AppCachePolicyManager.registerActiveFile(tempFile)
            Log.d(TAG, "Copied content URI to temp file: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying content URI to temp file: ${e.message}", e)
            null
        }
    }

    // ── Segmented Preview Rendering ────────────────────────────────────────────

    /**
     * Render a fast 3-second preview segment around the current playhead.
     * Swaps ExoPlayer's source to the preview clip for immediate visual feedback.
     */
    private fun renderSegmentedPreview() {
        if (!::tempInputFile.isInitialized || !isVideoLoaded) return

        previewJob?.cancel()
        isRenderingPreview = true
        showCentralizedLoading("preview_render")

        previewJob = lifecycleScope.launch {
            val seekPos = if (::player.isInitialized) player.currentPosition else 0L
            val currentOps = viewModel.project.value?.operations ?: emptyList()
            val currentFingerprint = currentOps.filter { 
                it is EditOperation.Crop || it is EditOperation.AddText || it is EditOperation.AddImageOverlay || it is EditOperation.MosaicOverlay 
            }.joinToString(";") { it.id } + "@$seekPos"

            // Fixed single-slot preview file to prevent unbounded disk writes
            val previewOutput = File(cacheDir, "preview_active_slot.mp4")

            // Re-use active preview if already showing exact same state
            if (isShowingPreview && currentFingerprint == lastPreviewFingerprint && previewOutput.exists()) {
                isRenderingPreview = false
                hideCentralizedLoading("preview_render")
                return@launch
            }

            try {
                val cmd = viewModel.buildPreviewCommand(
                    sourceFilePath = tempInputFile.absolutePath,
                    previewOutputPath = previewOutput.absolutePath,
                    seekPositionMs = seekPos,
                    fontFilePath = fontFilePath,
                    density = resources.displayMetrics.density
                ) ?: run {
                    return@launch
                }

                Log.d(TAG, "Rendering segmented preview...")

                // Purge any stale preview files from cacheDir
                cacheDir.listFiles { file -> 
                    file.name.startsWith("preview_segment_") 
                }?.forEach { it.delete() }

                val result = withContext(Dispatchers.IO) {
                    ffmpegEngine.executeCommand(cmd)
                }

                if (result is FFmpegRenderEngine.RenderResult.Success && previewOutput.exists()) {
                    isShowingPreview = true
                    lastPreviewFingerprint = currentFingerprint
                    AppCachePolicyManager.unregisterAndDeleteFile(previewFile)
                    previewFile = previewOutput
                    AppCachePolicyManager.registerActiveFile(previewOutput)

                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(previewOutput)))
                    player.prepare()
                    player.play()

                    // Show preview badge
                    try {
                        tvPreviewBadge?.visibility = View.VISIBLE
                    } catch (_: Exception) {}
                } else {
                    Log.w(TAG, "Preview render failed or was cancelled")
                    previewOutput.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Preview render error: ${e.message}", e)
                previewOutput.delete()
            } finally {
                isRenderingPreview = false
                hideCentralizedLoading("preview_render")
            }
        }
    }

    /**
     * Dismiss the preview and restore the original video source.
     */
    private fun dismissPreview() {
        if (!isShowingPreview) return
        isShowingPreview = false
        lastPreviewFingerprint = null
        previewJob?.cancel()

        videoUri?.let {
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
        }

        try {
            tvPreviewBadge?.visibility = View.GONE
        } catch (_: Exception) {}

        AppCachePolicyManager.unregisterAndDeleteFile(previewFile)
        previewFile?.delete()
        previewFile = null
        val slotFile = File(cacheDir, "preview_active_slot.mp4")
        if (slotFile.exists()) {
            slotFile.delete()
        }
        updateUIInteractionState()
    }

    override fun onPause() {
        super.onPause()
        com.eclip.rup.leb.utils.RAMFrameBufferManager.clearAll()
        // Safety reset: clear drag/scroll stuck flags so they don't suppress syncUiWithPlayer()
        // after the activity resumes (e.g. if ACTION_CANCEL was missed while going to background).
        isTrackDragging = false
        isUserScrollingTimeline = false
        pendingSeekRunnable?.let { seekHandler.removeCallbacks(it) }
        pendingSeekRunnable = null
        if (::sequenceTrackContainer.isInitialized) {
            pendingRenderRunnable?.let { sequenceTrackContainer.removeCallbacks(it) }
            pendingRenderRunnable = null
        }
        super.onPause()
        saveDraftToStorage()
        stopProgressUpdater()
        if (::player.isInitialized) {
            player.pause()
        }
        if (audioPreviewPlayer?.isPlaying == true) {
            try {
                audioPreviewPlayer?.stop()
            } catch (e: Exception) {}
        }
        try {
            audioPreviewPlayer?.release()
        } catch (e: Exception) {}
        audioPreviewPlayer = null
        audioEditingToolbar?.findViewById<ImageButton>(R.id.btnAudioPreviewPlay)?.setImageResource(R.drawable.ic_play_24)
        if (isRecordingVoiceOver) {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (e: Exception) {}
            mediaRecorder = null
            isRecordingVoiceOver = false
        }
        activeRenderJobs.forEach { it.cancel() }
        activeRenderJobs.clear()
        frameExtractionJob?.cancel()
        previewJob?.cancel()
        AppCachePolicyManager.unregisterAndDeleteFile(previewFile)
        previewFile = null
    }

    override fun onStop() {
        // Safety reset: guarantee stuck flags are cleared even if onPause() was somehow skipped
        // or the drag started between onPause() and onStop().
        isTrackDragging = false
        isUserScrollingTimeline = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.finishExport()
        isRenderingPreview = false
        hideCentralizedLoading("preview_render")
        // Ensure UI reflects actual player state after returning from background
        if (::player.isInitialized && isVideoLoaded && ::btnPlayPause.isInitialized) {
            btnPlayPause.setImageResource(
                if (player.isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24
            )
            // Resync timeline position with player
            val globalPos = getGlobalPosition()
            val totalDuration = getTotalSequenceDuration()
            if (totalDuration > 0) {
                updateDurationDisplay(globalPos.toInt(), totalDuration.toInt())
            }
        }
    }

    private fun cancelAllProcessingAndReleaseResources() {
        try {
            com.antonkarpenko.ffmpegkit.FFmpegKit.cancel()
            stopService(Intent(this, com.eclip.rup.leb.services.ExportService::class.java))
            if (::player.isInitialized) {
                player.stop()
                player.clearMediaItems()
            }
            com.eclip.rup.leb.utils.AppCachePolicyManager.setHeavyLoadActive(false)
            Log.i(TAG, "All background processing tasks, FFmpeg sessions, and media resources successfully released.")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing resources: ${e.message}")
        }
    }

    override fun onDestroy() {
        com.eclip.rup.leb.utils.RAMFrameBufferManager.clearAll()
        cancelAllProcessingAndReleaseResources()
        ThermalProtectionManager.removeThermalListener(thermalListener)

        // 1. Stop all pending callbacks first to prevent post-destroy execution
        pendingSeekRunnable?.let {
            seekHandler.removeCallbacks(it)
            if (::customVideoSeeker.isInitialized) customVideoSeeker.removeCallbacks(it)
        }
        pendingSeekRunnable = null
        seekHandler.removeCallbacksAndMessages(null)
        fullscreenHideHandler.removeCallbacksAndMessages(null)
        stopProgressUpdater()
        
        if (::sequenceTrackContainer.isInitialized) {
            pendingRenderRunnable?.let { sequenceTrackContainer.removeCallbacks(it) }
            pendingRenderRunnable = null
        }
        

        // 3. Cancel background jobs
        if (isRecordingVoiceOver) {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (e: Exception) {}
            mediaRecorder = null
            isRecordingVoiceOver = false
        }
        backgroundProcessingJobs.values.forEach { it.cancel() }
        backgroundProcessingJobs.clear()
        frameExtractionJob?.cancel()
        previewJob?.cancel()
        activeRenderJobs.forEach { it.cancel() }
        activeRenderJobs.clear()
        activeFrameExtractionJobs.values.forEach { it.cancel() }
        activeFrameExtractionJobs.clear()
        activeWaveformJobs.values.forEach { it.cancel() }
        activeWaveformJobs.clear()
        
        // 4. Release resources
        ThumbnailCacheManager.clearMemoryCache()
        uriToFilePathCache.clear()
        timelineCache = null
        extractedFrames.forEach { if (!it.isRecycled) it.recycle() }
        draggableTextOverlay?.deactivate()
        draggableImageOverlay?.deactivate()
        if (audioPreviewPlayer?.isPlaying == true) {
            try {
                audioPreviewPlayer?.stop()
            } catch (e: Exception) {}
        }
        try {
            audioPreviewPlayer?.release()
        } catch (e: Exception) {}
        audioPreviewPlayer = null
        AppCachePolicyManager.unregisterAndDeleteFile(previewFile)
        previewFile = null
        cachedTransitionBitmap?.recycle()
        cachedTransitionBitmap = null
        lastPhotoSequenceUri = null
        
        // 5. Release heavy resources BEFORE super.onDestroy()
        if (::timelinePrefetchPipeline.isInitialized) {
            timelinePrefetchPipeline.clear()
        }
        videoSpatialIndex.clear()
        overlaySpatialIndex.clear()
        if (::adaptiveTimelineController.isInitialized) {
            adaptiveTimelineController.release()
        }
        if (::player.isInitialized) {
            player.release()
        }
        ffmpegEngine.cleanup()
        
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ThumbnailCacheManager.trimMemory(level)
        AudioWaveformExtractor.clearCache()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ThumbnailCacheManager.clearMemoryCache()
        BitmapPool.clear()
        AudioWaveformExtractor.clearCache()
    }

    private fun updateActiveBoundaries(activeStart: Long, activeEnd: Long) {
        for (i in 0 until textTrackContainer.childCount) {
            val track = textTrackContainer.getChildAt(i) as? com.eclip.rup.leb.customviews.TrackTrimView
            track?.let {
                it.activeStartMs = activeStart
                it.activeEndMs = activeEnd
                it.invalidate()
            }
        }

        for (i in 0 until imageTrackContainer.childCount) {
            val track = imageTrackContainer.getChildAt(i) as? com.eclip.rup.leb.customviews.TrackTrimView
            track?.let {
                it.activeStartMs = activeStart
                it.activeEndMs = activeEnd
                it.invalidate()
            }
        }

        for (i in 0 until audioTrackContainer.childCount) {
            val track = audioTrackContainer.getChildAt(i) as? com.eclip.rup.leb.customviews.TrackTrimView
            track?.let {
                it.activeStartMs = activeStart
                it.activeEndMs = activeEnd
                it.invalidate()
            }
        }
    }

    private fun showAdjustSelectionDialog(clipIndex: Int) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_adjustments, null)
        bottomSheet.setContentView(view)

        val optionsList = view.findViewById<LinearLayout>(R.id.adjustOptionsList)
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.adjustSlider)
        val valueLabel = view.findViewById<TextView>(R.id.adjustValueLabel)

        val project = viewModel.project.value ?: return
        val existingOp = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.Adjust>().find { it.index == clipIndex }
        
        var localAdjust = existingOp ?: com.eclip.rup.leb.models.EditOperation.Adjust(index = clipIndex)
        var selectedOptionId = "brightness" // Default selection

        val options = listOf(
            Pair("reset", "Reset All"),
            Pair("brightness", "Brightness"),
            Pair("contrast", "Contrast"),
            Pair("warmth", "Warmth"),
            Pair("shadow", "Shadow"),
            Pair("highlights", "Highlights"),
            Pair("saturation", "Saturation"),
            Pair("exposure", "Exposure"),
            Pair("sharpen", "Sharpen"),
            Pair("vignette", "Vignette")
        )

        val itemBgs = mutableMapOf<String, FrameLayout>()
        val itemTvShorts = mutableMapOf<String, TextView>()
        val itemTvNames = mutableMapOf<String, TextView>()

        fun getValForOption(id: String): Int {
            return when (id) {
                "brightness" -> localAdjust.brightness
                "contrast" -> localAdjust.contrast
                "warmth" -> localAdjust.warmth
                "shadow" -> localAdjust.shadow
                "highlights" -> localAdjust.highlights
                "saturation" -> localAdjust.saturation
                "exposure" -> localAdjust.exposure
                "sharpen" -> localAdjust.sharpen
                "vignette" -> localAdjust.vignette
                else -> 0
            }
        }

        fun refreshSelectionStates() {
            for ((id, bgFrame) in itemBgs) {
                val tvName = itemTvNames[id]
                val isSelected = (id == selectedOptionId)
                val isModified = (id != "reset" && getValForOption(id) != 0)

                if (isSelected) {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                    bgFrame.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_transition_border_selected)
                    tvName?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary))
                } else if (isModified) {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                    bgFrame.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_transition_border_selected)
                    tvName?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textColor))
                } else {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                    bgFrame.foreground = null
                    tvName?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textColor))
                }
            }
        }

        fun updateSliderForSelection() {
            if (selectedOptionId == "reset") {
                slider.visibility = View.INVISIBLE
                valueLabel.text = "Reset All"
            } else {
                slider.visibility = View.VISIBLE
                val currentVal = getValForOption(selectedOptionId)
                slider.value = currentVal.toFloat()
                valueLabel.text = "${selectedOptionId.replaceFirstChar { it.uppercase() }}: $currentVal"
            }
        }

        val activeFilterName = project.operations.filterIsInstance<com.eclip.rup.leb.models.EditOperation.ColorFilter>()
            .find { it.index == clipIndex }?.filterName ?: "none"

        for ((id, displayName) in options) {
            val itemView = layoutInflater.inflate(R.layout.item_adjust_option, optionsList, false)
            val tvName = itemView.findViewById<TextView>(R.id.adjustName)
            val bg = itemView.findViewById<FrameLayout>(R.id.adjustIconBg)
            val iconImage = itemView.findViewById<ImageView>(R.id.adjustIconImage)
            val tvShort = itemView.findViewById<TextView>(R.id.adjustShortName)

            itemBgs[id] = bg
            itemTvShorts[id] = tvShort
            itemTvNames[id] = tvName

            tvName.text = displayName
            bg.clipToOutline = true

            val resName = "ic_${id.lowercase()}_24"
            val resId = resources.getIdentifier(resName, "drawable", packageName)
            if (resId != 0) {
                iconImage.setImageResource(resId)
                iconImage.visibility = View.VISIBLE
                tvShort.visibility = View.GONE
            } else {
                iconImage.visibility = View.GONE
                tvShort.visibility = View.VISIBLE
                tvShort.text = displayName.substring(0, minOf(2, displayName.length)).uppercase()
            }

            itemView.setOnClickListener {
                if (id == "reset") {
                    localAdjust = com.eclip.rup.leb.models.EditOperation.Adjust(index = clipIndex)
                    viewModel.setAdjust(
                        index = clipIndex,
                        brightness = 0,
                        contrast = 0,
                        warmth = 0,
                        shadow = 0,
                        highlights = 0,
                        saturation = 0,
                        exposure = 0,
                        sharpen = 0,
                        vignette = 0
                    )
                    applyColorFilterAndAdjustToPlayer(activeFilterName, localAdjust)
                    refreshSelectionStates()
                    updateSliderForSelection()
                } else {
                    selectedOptionId = id
                    refreshSelectionStates()
                    updateSliderForSelection()
                }
            }

            optionsList.addView(itemView)
        }

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && selectedOptionId != "reset_all") {
                val intVal = value.toInt()
                localAdjust = when (selectedOptionId) {
                    "brightness" -> localAdjust.copy(brightness = intVal)
                    "contrast" -> localAdjust.copy(contrast = intVal)
                    "warmth" -> localAdjust.copy(warmth = intVal)
                    "shadow" -> localAdjust.copy(shadow = intVal)
                    "highlights" -> localAdjust.copy(highlights = intVal)
                    "saturation" -> localAdjust.copy(saturation = intVal)
                    "exposure" -> localAdjust.copy(exposure = intVal)
                    "sharpen" -> localAdjust.copy(sharpen = intVal)
                    "vignette" -> localAdjust.copy(vignette = intVal)
                    else -> localAdjust
                }

                valueLabel.text = "${selectedOptionId.replaceFirstChar { it.uppercase() }}: $intVal"
                
                viewModel.setAdjust(
                    index = clipIndex,
                    brightness = localAdjust.brightness,
                    contrast = localAdjust.contrast,
                    warmth = localAdjust.warmth,
                    shadow = localAdjust.shadow,
                    highlights = localAdjust.highlights,
                    saturation = localAdjust.saturation,
                    exposure = localAdjust.exposure,
                    sharpen = localAdjust.sharpen,
                    vignette = localAdjust.vignette
                )
                applyColorFilterAndAdjustToPlayer(activeFilterName, localAdjust)
                refreshSelectionStates()
            }
        }

        refreshSelectionStates()
        updateSliderForSelection()

        view.findViewById<View>(R.id.btnApplyToAll)?.setOnClickListener {
            val chunkCount = chunkDurationsMs.size
            for (i in 0 until chunkCount) {
                viewModel.setAdjust(
                    index = i,
                    brightness = localAdjust.brightness,
                    contrast = localAdjust.contrast,
                    warmth = localAdjust.warmth,
                    shadow = localAdjust.shadow,
                    highlights = localAdjust.highlights,
                    saturation = localAdjust.saturation,
                    exposure = localAdjust.exposure,
                    sharpen = localAdjust.sharpen,
                    vignette = localAdjust.vignette
                )
            }
            applyColorFilterAndAdjustToPlayer(activeFilterName, localAdjust)
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
        }

        view.findViewById<View>(R.id.btnDone)?.setOnClickListener {
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    private fun showQuitConfirmationDialog() {
        if (!viewModel.hasUnsavedEdits.value) {
            finish()
            return
        }

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_unsaved_changes, null)

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveAndQuit).setBounceClickListener {
            bottomSheet.dismiss()
            shouldQuitAfterSave = true
            saveProjectLauncher.launch("project.lcprj")
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnKeepEditing).setBounceClickListener {
            bottomSheet.dismiss()
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDiscard).setBounceClickListener {
            bottomSheet.dismiss()
            finish()
        }

        bottomSheet.setContentView(view)
        bottomSheet.show()
    }

    private fun getSnapTargetsMs(nearPositionMs: Long? = null): List<Long> {
        val targets = mutableSetOf<Long>()
        targets.add(0L)

        // 1. Clip segment boundaries
        var cumulative = 0L
        for (item in getSequenceItems()) {
            cumulative += item.trimmedDurationMs
            targets.add(cumulative)
        }

        val refPos = nearPositionMs ?: if (::timelineHorizontalScroll.isInitialized && pixelsPerMs > 0) {
            (timelineHorizontalScroll.scrollX / pixelsPerMs).toLong()
        } else null

        // 2. Operations boundaries
        viewModel.project.value?.operations?.forEach { op ->
            when (op) {
                is EditOperation.AddBackgroundAudio -> {
                    op.startTimeMs?.let { targets.add(it) }
                    op.endTimeMs?.let { targets.add(it) }
                    val start = op.startTimeMs ?: 0L
                    val end = op.endTimeMs ?: getTotalSequenceDuration()
                    for (beat in op.beats) {
                        val relative = beat - op.internalStartMs
                        if (relative >= 0) {
                            val timelineMs = start + relative
                            if (timelineMs <= end) {
                                if (refPos == null || Math.abs(timelineMs - refPos) <= 2000L) {
                                    targets.add(timelineMs)
                                }
                            }
                        }
                    }
                }
                is EditOperation.AddText -> {
                    op.startTimeMs?.let { targets.add(it) }
                    op.endTimeMs?.let { targets.add(it) }
                }
                is EditOperation.AddImageOverlay -> {
                    op.startTimeMs?.let { targets.add(it) }
                    op.endTimeMs?.let { targets.add(it) }
                }
                else -> {}
            }
        }

        return targets.toList().sorted()
    }

    private fun showChromaKeyDialog() {
        val selectedId = viewModel.selectedOperationId.value
        val op = if (selectedId != null) {
            viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
        } else null

        if (op == null && draggableImageOverlay?.visibility != View.VISIBLE) return

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.chroma_key_bottom_sheet_dialog, null)
        bottomSheet.setContentView(view)

        val colorPreview = view.findViewById<View>(R.id.chromaColorPreview)
        val hsvPicker = view.findViewById<com.eclip.rup.leb.customviews.HSVColorPickerView>(R.id.hsvChromaColorPicker)
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.chromaIntensitySlider)
        val btnClear = view.findViewById<Button>(R.id.btnChromaClear)
        val btnApply = view.findViewById<Button>(R.id.btnChromaApply)
        val btnEyedropper = view.findViewById<ImageView>(R.id.btnChromaEyedropper)

        var selectedColor = op?.chromaKeyColor ?: draggableImageOverlay?.currentChromaColor ?: "#00FF00"
        slider.value = (op?.chromaKeySimilarity ?: draggableImageOverlay?.currentChromaSimilarity ?: 0.1f).coerceIn(0.01f, 0.5f)

        val originalColor = op?.chromaKeyColor ?: draggableImageOverlay?.currentChromaColor
        val originalSimilarity = op?.chromaKeySimilarity ?: draggableImageOverlay?.currentChromaSimilarity ?: 0.1f

        fun updatePreview() {
            try {
                colorPreview.setBackgroundColor(android.graphics.Color.parseColor(selectedColor))
                draggableImageOverlay?.setChromaKey(selectedColor, slider.value)
            } catch (e: Exception) {
                // Ignore parsing errors for safety
            }
        }
        
        try {
            colorPreview.setBackgroundColor(android.graphics.Color.parseColor(selectedColor))
            hsvPicker.setColor(android.graphics.Color.parseColor(selectedColor))
        } catch (e: Exception) {
            hsvPicker.setColor(android.graphics.Color.GREEN)
        }
        
        hsvPicker.onColorChanged = { newColor ->
            selectedColor = String.format("#%06X", (0xFFFFFF and newColor))
            updatePreview()
        }

        slider.addOnChangeListener { _, _, _ -> updatePreview() }

        btnEyedropper.setBounceClickListener {
            bottomSheet.hide()
            Toast.makeText(this@VideoEditingActivity, "Tap a color on the image to pick it", Toast.LENGTH_SHORT).show()
            draggableImageOverlay?.isColorPickingMode = true
            draggableImageOverlay?.onColorPicked = { hex ->
                selectedColor = hex
                try {
                    hsvPicker.setColor(android.graphics.Color.parseColor(selectedColor))
                } catch(e: Exception){}
                updatePreview()
                draggableImageOverlay?.isColorPickingMode = false
                bottomSheet.show()
            }
        }
        
        var applied = false

        btnClear.setOnClickListener {
            applied = true
            if (op != null) {
                viewModel.updateOperation(op.copy(chromaKeyColor = null))
            }
            draggableImageOverlay?.setChromaKey(null, 0.1f)
            bottomSheet.dismiss()
        }

        btnApply.setOnClickListener {
            applied = true
            if (op != null) {
                viewModel.updateOperation(op.copy(
                    chromaKeyColor = selectedColor,
                    chromaKeySimilarity = slider.value
                ))
            }
            bottomSheet.dismiss()
        }

        bottomSheet.setOnDismissListener {
            if (!applied) {
                draggableImageOverlay?.setChromaKey(originalColor, originalSimilarity)
            }
            draggableImageOverlay?.isColorPickingMode = false
        }
        bottomSheet.show()
    }

    private fun showCustomColorPicker(initialHex: String, onColorPicked: (String) -> Unit) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_custom_color_picker, null)
        bottomSheet.setContentView(view)

        val preview = view.findViewById<View>(R.id.colorPickerPreview)
        val hsvPicker = view.findViewById<com.eclip.rup.leb.customviews.HSVColorPickerView>(R.id.hsvColorPicker)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelCustomColor)
        val btnApply = view.findViewById<Button>(R.id.btnApplyCustomColor)

        var currentColor = android.graphics.Color.parseColor(initialHex)
        preview.setBackgroundColor(currentColor)

        hsvPicker.setColor(currentColor)
        hsvPicker.onColorChanged = { newColor ->
            currentColor = newColor
            preview.setBackgroundColor(currentColor)
        }

        btnCancel.setOnClickListener { bottomSheet.dismiss() }
        btnApply.setOnClickListener {
            val hex = String.format("#%06X", 0xFFFFFF and currentColor)
            onColorPicked(hex)
            bottomSheet.dismiss()
        }
        bottomSheet.show()
    }

    private fun commitActiveEditsIfAny() {
        if (draggableImageOverlay?.visibility == View.VISIBLE) {
            draggableImageOverlay?.commitImage()
            findViewById<View>(R.id.imageEditingToolbar)?.visibility = View.GONE
        }
        
        if (draggableTextOverlay?.visibility == View.VISIBLE) {
            draggableTextOverlay?.commitText()
            findViewById<View>(R.id.textEditingToolbar)?.visibility = View.GONE
        }
        
        findViewById<android.widget.HorizontalScrollView>(R.id.editingControlsScroll)?.visibility = View.VISIBLE
    }



    private fun showMaskBottomSheet() {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_mask, null)
        bottomSheet.setContentView(view)

        val btnNone = view.findViewById<android.widget.LinearLayout>(R.id.btnMaskNone)
        val btnSplit = view.findViewById<android.widget.LinearLayout>(R.id.btnMaskSplit)
        val btnShutter = view.findViewById<android.widget.LinearLayout>(R.id.btnMaskShutter)
        val btnEllipse = view.findViewById<android.widget.LinearLayout>(R.id.btnMaskEllipse)
        val btnRectangle = view.findViewById<android.widget.LinearLayout>(R.id.btnMaskRectangle)
        val btnHeart = view.findViewById<android.widget.LinearLayout>(R.id.btnMaskHeart)
        val btnStar = view.findViewById<android.widget.LinearLayout>(R.id.btnMaskStar)

        val bgNone = view.findViewById<android.widget.FrameLayout>(R.id.bgMaskNone)
        val bgSplit = view.findViewById<android.widget.FrameLayout>(R.id.bgMaskSplit)
        val bgShutter = view.findViewById<android.widget.FrameLayout>(R.id.bgMaskShutter)
        val bgEllipse = view.findViewById<android.widget.FrameLayout>(R.id.bgMaskEllipse)
        val bgRectangle = view.findViewById<android.widget.FrameLayout>(R.id.bgMaskRectangle)
        val bgHeart = view.findViewById<android.widget.FrameLayout>(R.id.bgMaskHeart)
        val bgStar = view.findViewById<android.widget.FrameLayout>(R.id.bgMaskStar)

        val imgNone = view.findViewById<android.widget.ImageView>(R.id.imgMaskNone)
        val imgSplit = view.findViewById<android.widget.ImageView>(R.id.imgMaskSplit)
        val imgShutter = view.findViewById<android.widget.ImageView>(R.id.imgMaskShutter)
        val imgEllipse = view.findViewById<android.widget.ImageView>(R.id.imgMaskEllipse)
        val imgRectangle = view.findViewById<android.widget.ImageView>(R.id.imgMaskRectangle)
        val imgHeart = view.findViewById<android.widget.ImageView>(R.id.imgMaskHeart)
        val imgStar = view.findViewById<android.widget.ImageView>(R.id.imgMaskStar)

        val switchInvert = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchInvertMask)
        val sliderFeather = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMaskFeather)
        val sliderSize = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMaskSize)
        val sliderRotation = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMaskRotation)
        
        val tvFeatherValue = view.findViewById<TextView>(R.id.tvFeatherValue)
        val tvSizeValue = view.findViewById<TextView>(R.id.tvSizeValue)
        val tvRotationValue = view.findViewById<TextView>(R.id.tvRotationValue)
        val btnResetRotation = view.findViewById<TextView>(R.id.btnResetRotation)
        
        val btnResetMask = view.findViewById<View>(R.id.btnResetMask)
        val btnDoneMask = view.findViewById<View>(R.id.btnDoneMaskSheet)

        val isMainVideo = selectedVideoIndex != null && videoEditingToolbar?.visibility == View.VISIBLE
        
        var currentMask = if (isMainVideo) {
            val idx = selectedVideoIndex!!
            val items = getSequenceItems()
            if (idx >= 0 && idx < items.size) items[idx].maskConfig else com.eclip.rup.leb.models.EditOperation.MaskConfig()
        } else {
            draggableImageOverlay?.maskConfig ?: com.eclip.rup.leb.models.EditOperation.MaskConfig()
        }
        
        switchInvert.isChecked = currentMask.isInverted
        sliderFeather?.value = currentMask.feather.coerceIn(0f, 100f)
        tvFeatherValue?.text = "${currentMask.feather.toInt()}%"

        val avgScale = ((currentMask.relativeWidth + currentMask.relativeHeight) / 2f * 200f).coerceIn(10f, 200f)
        sliderSize?.value = avgScale
        tvSizeValue?.text = "${avgScale.toInt()}%"

        sliderRotation?.value = currentMask.rotationAngle.coerceIn(-180f, 180f)
        tvRotationValue?.text = "${currentMask.rotationAngle.toInt()}°"

        fun highlightShape(selectedShape: com.eclip.rup.leb.models.EditOperation.MaskShape) {
            val shapeMap = mapOf(
                com.eclip.rup.leb.models.EditOperation.MaskShape.NONE to Pair(bgNone, imgNone),
                com.eclip.rup.leb.models.EditOperation.MaskShape.SPLIT to Pair(bgSplit, imgSplit),
                com.eclip.rup.leb.models.EditOperation.MaskShape.SHUTTER to Pair(bgShutter, imgShutter),
                com.eclip.rup.leb.models.EditOperation.MaskShape.ELLIPSE to Pair(bgEllipse, imgEllipse),
                com.eclip.rup.leb.models.EditOperation.MaskShape.RECTANGLE to Pair(bgRectangle, imgRectangle),
                com.eclip.rup.leb.models.EditOperation.MaskShape.HEART to Pair(bgHeart, imgHeart),
                com.eclip.rup.leb.models.EditOperation.MaskShape.STAR to Pair(bgStar, imgStar)
            )
            val activeColor = android.graphics.Color.WHITE
            val inactiveColor = getColor(R.color.toolTextInactive)

            for ((shape, views) in shapeMap) {
                val bg = views.first ?: continue
                val img = views.second ?: continue
                if (shape == selectedShape) {
                    bg.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                    img.setColorFilter(activeColor)
                } else {
                    bg.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                    img.setColorFilter(inactiveColor)
                }
            }
        }

        highlightShape(currentMask.shape)

        if (isMainVideo) {
            videoMaskOverlayView?.maskConfig = currentMask
            videoMaskOverlayView?.isEditingMode = true
        } else {
            draggableImageOverlay?.isMaskEditingMode = currentMask.shape != com.eclip.rup.leb.models.EditOperation.MaskShape.NONE
        }

        fun applyMaskToView(newMask: com.eclip.rup.leb.models.EditOperation.MaskConfig) {
            currentMask = newMask
            if (isMainVideo) {
                videoMaskOverlayView?.let { v ->
                    v.maskConfig = newMask
                    v.isEditingMode = true
                    v.invalidate()
                }
                mainVideoMaskContainer?.maskConfig = newMask
                selectedVideoIndex?.let { index ->
                    viewModel.updateMergeItemMask(index, newMask)
                }
            } else {
                draggableImageOverlay?.let { dio ->
                    dio.maskConfig = newMask
                    dio.isMaskEditingMode = newMask.shape != com.eclip.rup.leb.models.EditOperation.MaskShape.NONE
                    dio.invalidate()
                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
                        if (op != null) {
                            viewModel.updateOperation(op.copy(maskConfig = newMask))
                        }
                    }
                }
            }
        }

        fun updateMaskShape(shape: com.eclip.rup.leb.models.EditOperation.MaskShape) {
            val newMask = currentMask.copy(shape = shape)
            applyMaskToView(newMask)
            highlightShape(shape)
        }

        btnNone?.setOnClickListener { updateMaskShape(com.eclip.rup.leb.models.EditOperation.MaskShape.NONE) }
        btnSplit?.setOnClickListener { updateMaskShape(com.eclip.rup.leb.models.EditOperation.MaskShape.SPLIT) }
        btnShutter?.setOnClickListener { updateMaskShape(com.eclip.rup.leb.models.EditOperation.MaskShape.SHUTTER) }
        btnEllipse?.setOnClickListener { updateMaskShape(com.eclip.rup.leb.models.EditOperation.MaskShape.ELLIPSE) }
        btnRectangle?.setOnClickListener { updateMaskShape(com.eclip.rup.leb.models.EditOperation.MaskShape.RECTANGLE) }
        btnHeart?.setOnClickListener { updateMaskShape(com.eclip.rup.leb.models.EditOperation.MaskShape.HEART) }
        btnStar?.setOnClickListener { updateMaskShape(com.eclip.rup.leb.models.EditOperation.MaskShape.STAR) }

        sliderFeather?.addOnChangeListener { _, value, _ ->
            tvFeatherValue?.text = "${value.toInt()}%"
            val newMask = currentMask.copy(feather = value)
            applyMaskToView(newMask)
        }

        sliderSize?.addOnChangeListener { _, value, _ ->
            tvSizeValue?.text = "${value.toInt()}%"
            val relScale = value / 200f
            val newMask = currentMask.copy(relativeWidth = relScale, relativeHeight = relScale)
            applyMaskToView(newMask)
        }

        sliderRotation?.addOnChangeListener { _, value, _ ->
            tvRotationValue?.text = "${value.toInt()}°"
            val newMask = currentMask.copy(rotationAngle = value)
            applyMaskToView(newMask)
        }

        btnResetRotation?.setOnClickListener {
            sliderRotation?.value = 0f
        }

        switchInvert.setOnCheckedChangeListener { _, isChecked ->
            val newMask = currentMask.copy(isInverted = isChecked)
            applyMaskToView(newMask)
        }

        btnResetMask?.setOnClickListener {
            val resetMask = com.eclip.rup.leb.models.EditOperation.MaskConfig()
            switchInvert.isChecked = false
            sliderFeather?.value = 0f
            sliderSize?.value = 100f
            sliderRotation?.value = 0f
            tvFeatherValue?.text = "0%"
            tvSizeValue?.text = "100%"
            tvRotationValue?.text = "0°"
            updateMaskShape(com.eclip.rup.leb.models.EditOperation.MaskShape.NONE)
        }
        
        bottomSheet.setOnDismissListener {
            if (isMainVideo) {
                videoMaskOverlayView?.isEditingMode = false
                selectedVideoIndex?.let { index ->
                    viewModel.updateMergeItemMask(index, currentMask)
                    mainVideoMaskContainer?.maskConfig = currentMask
                }
            } else {
                draggableImageOverlay?.isMaskEditingMode = false
                draggableImageOverlay?.maskConfig = currentMask
                draggableImageOverlay?.invalidate()
                val selectedId = viewModel.selectedOperationId.value
                if (selectedId != null) {
                    val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
                    if (op != null) {
                        viewModel.updateOperation(op.copy(maskConfig = currentMask))
                    }
                }
            }
        }
        
        btnDoneMask?.setOnClickListener {
            bottomSheet.dismiss()
        }

        bottomSheet.setCanceledOnTouchOutside(false)
        bottomSheet.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        bottomSheet.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        bottomSheet.show()

        val touchOutside = bottomSheet.findViewById<View>(com.google.android.material.R.id.touch_outside)
        touchOutside?.setOnTouchListener { _, event ->
            findViewById<View>(android.R.id.content).dispatchTouchEvent(event)
            true
        }
    }

    fun pausePlaybackOnTimelineInteraction() {
        if (::player.isInitialized && (player.isPlaying || player.playWhenReady)) {
            player.pause()
            btnPlayPause.setImageResource(R.drawable.ic_play_24)
            if (::btnFullscreenPlayPause.isInitialized) {
                btnFullscreenPlayPause.setImageResource(R.drawable.ic_play_24)
            }
            stopProgressUpdater()
        }
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var downEventTime = 0L

    private fun hasActiveSelectionOrToolbar(): Boolean {
        return selectedVideoIndex != null ||
                viewModel.selectedOperationId.value != null ||
                isTextEditingActive ||
                isTextLayerEditingActive ||
                isImageEditingActive ||
                isMosaicEditingActive ||
                isHandwritingActive ||
                cropEditingToolbar?.visibility == View.VISIBLE ||
                backgroundEditingToolbar?.visibility == View.VISIBLE ||
                speedEditingToolbar?.visibility == View.VISIBLE ||
                audioEditingToolbar?.visibility == View.VISIBLE ||
                videoEditingToolbar?.visibility == View.VISIBLE ||
                textLayerToolbar?.visibility == View.VISIBLE ||
                textEditingToolbar?.visibility == View.VISIBLE ||
                imageEditingToolbar?.visibility == View.VISIBLE
    }

    private fun isTouchOnEmptyArea(rawX: Float, rawY: Float): Boolean {
        if (isFullscreenMode) return false

        // Check if editor is locked or overlays visible
        if (isEditorLocked) return false
        if (::previewLoadingOverlay.isInitialized && previewLoadingOverlay.visibility == View.VISIBLE) return false
        if (::exportScreen.isInitialized && exportScreen.visibility == View.VISIBLE) return false

        val hitRect = android.graphics.Rect()
        val rawXInt = rawX.toInt()
        val rawYInt = rawY.toInt()

        // 1. Top action bar buttons
        val topButtons = listOfNotNull(
            findViewById<View>(R.id.btnClose),
            findViewById<View>(R.id.btnHelp),
            findViewById<View>(R.id.btnDoneExport),
            findViewById<View>(R.id.btnHome)
        )
        for (btn in topButtons) {
            if (btn.visibility == View.VISIBLE && btn.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
        }

        // 2. Player control bar buttons
        val playerControls = listOfNotNull(
            findViewById<View>(R.id.btnPrevClip),
            findViewById<View>(R.id.btnPlayPause),
            findViewById<View>(R.id.btnNextClip),
            findViewById<View>(R.id.btnUndo),
            findViewById<View>(R.id.btnRedo),
            findViewById<View>(R.id.btnFullscreen),
            findViewById<View>(R.id.btnMute),
            findViewById<View>(R.id.btnTimelineMuteContainer),
            findViewById<View>(R.id.btnCaptureFrame),
            findViewById<View>(R.id.btnMagnet)
        )
        for (ctrl in playerControls) {
            if (ctrl.visibility == View.VISIBLE && ctrl.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
        }

        // 3. Bottom toolbars
        // If Main Toolbar is visible, check its interactive items
        if (::editingControlsScroll.isInitialized && editingControlsScroll.visibility == View.VISIBLE) {
            if (::editingControlsWrapper.isInitialized) {
                for (i in 0 until editingControlsWrapper.childCount) {
                    val item = editingControlsWrapper.getChildAt(i)
                    if (item.visibility == View.VISIBLE && item.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                        return false
                    }
                }
            }
        }

        // If any sub-toolbar is visible, check if touch is inside that sub-toolbar
        val activeSubToolbars = listOfNotNull(
            videoEditingToolbar,
            textLayerToolbar,
            textEditingToolbar,
            imageEditingToolbar,
            audioEditingToolbar,
            cropEditingToolbar,
            backgroundEditingToolbar,
            speedEditingToolbar,
            mosaicEditingToolbar
        ).filter { it.visibility == View.VISIBLE }

        for (toolbar in activeSubToolbars) {
            if (toolbar.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
        }

        // 4. Timeline interactive elements:
        // Add Media button
        if (::btnTimelineAdd.isInitialized && btnTimelineAdd.visibility == View.VISIBLE) {
            if (btnTimelineAdd.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
        }

        // Time Ruler
        if (::timeRulerView.isInitialized && timeRulerView.visibility == View.VISIBLE) {
            if (timeRulerView.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
        }

        // Empty Project State button
        if (::emptyProjectState.isInitialized && emptyProjectState.visibility == View.VISIBLE) {
            if (emptyProjectState.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
        }

        // Sequence track clips
        for (segView in activeSegmentViews) {
            if (segView.visibility == View.VISIBLE && segView.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
        }

        // Lane overlay tracks (PIP, Audio, Text, Sticker, Effect, Mosaic)
        val trackContainers = listOfNotNull(
            if (::pipTrackContainer.isInitialized) pipTrackContainer else null,
            if (::audioTrackContainer.isInitialized) audioTrackContainer else null,
            if (::textTrackContainer.isInitialized) textTrackContainer else null,
            if (::stickerTrackContainer.isInitialized) stickerTrackContainer else null,
            if (::effectTrackContainer.isInitialized) effectTrackContainer else null,
            if (::mosaicTrackContainer.isInitialized) mosaicTrackContainer else null
        )
        for (container in trackContainers) {
            if (container.visibility == View.VISIBLE) {
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)
                    if (child.visibility == View.VISIBLE && child.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                        return false
                    }
                }
            }
        }

        // 5. Video Viewport Overlays:
        draggableTextOverlay?.let {
            if (it.visibility == View.VISIBLE && it.isTouchOnBoxOrHandles(rawX, rawY)) {
                return false
            }
        }

        draggableImageOverlay?.let {
            if (it.visibility == View.VISIBLE && it.isTouchOnImageOrHandles(rawX, rawY)) {
                return false
            }
        }

        // Text overlays on TextOverlayView (tap on unselected text)
        textOverlayView?.let {
            if (it.visibility == View.VISIBLE) {
                val loc = IntArray(2)
                it.getLocationOnScreen(loc)
                val localX = rawX - loc[0]
                val localY = rawY - loc[1]
                if (it.findTextAtPosition(localX, localY) != null) {
                    return false
                }
            }
        }

        // Crop overlay
        cropOverlayView?.let {
            if (it.visibility == View.VISIBLE && it.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
        }

        // Mosaic overlay
        mosaicOverlayView?.let {
            if (it.visibility == View.VISIBLE && it.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
        }

        // Handwriting panel / canvas
        if (isHandwritingActive) {
            val handwritingPanel = findViewById<View>(R.id.handwritingPanel)
            if (handwritingPanel?.visibility == View.VISIBLE && handwritingPanel.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
            val hwCanvas = findViewById<View>(R.id.handwritingCanvasView)
            if (hwCanvas?.visibility == View.VISIBLE && hwCanvas.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return false
            }
        }

        return true
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (isEditorLocked) {
            val hitRect = android.graphics.Rect()
            val rawXInt = ev.rawX.toInt()
            val rawYInt = ev.rawY.toInt()

            val btnLoadingBack = if (::loadingScreen.isInitialized) loadingScreen.findViewById<View>(R.id.btnLoadingBack) else null
            if (btnLoadingBack != null && btnLoadingBack.visibility == View.VISIBLE &&
                btnLoadingBack.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return super.dispatchTouchEvent(ev)
            }

            val btnClose = findViewById<View>(R.id.btnClose)
            if (btnClose != null && btnClose.visibility == View.VISIBLE &&
                btnClose.getGlobalVisibleRect(hitRect) && hitRect.contains(rawXInt, rawYInt)) {
                return super.dispatchTouchEvent(ev)
            }

            // Strictly consume and block all other touch, click, scroll, drag, scrub, zoom, and tool events
            return true
        }

        if (ev.action == android.view.MotionEvent.ACTION_DOWN || ev.action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            val container = if (::timelineContainer.isInitialized) (findViewById<android.view.View>(R.id.seekerContainer) ?: timelineContainer) else null
            if (container != null && container.visibility == android.view.View.VISIBLE) {
                val timelineRect = android.graphics.Rect()
                container.getGlobalVisibleRect(timelineRect)
                if (timelineRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    pausePlaybackOnTimelineInteraction()
                }
            }
        }

        if (!isFullscreenMode) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downEventTime = android.os.SystemClock.uptimeMillis()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val upEventTime = android.os.SystemClock.uptimeMillis()
                    val dx = Math.abs(ev.rawX - downRawX)
                    val dy = Math.abs(ev.rawY - downRawY)
                    val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                    val isTap = dx <= touchSlop && dy <= touchSlop && (upEventTime - downEventTime) < 400L

                    if (isTap && hasActiveSelectionOrToolbar() && isTouchOnEmptyArea(ev.rawX, ev.rawY)) {
                        val result = super.dispatchTouchEvent(ev)
                        clearSelectionAndReturnToMainToolbar()
                        return result
                    }
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    companion object {
        private const val TAG = "VideoEditingActivity"
        private const val SEEK_THROTTLE_MS = 80L // Recommended: 80ms, acceptable range: 60ms - 100ms
    }
}