package com.eclip.rup.leb.viewmodels

import android.net.Uri
import com.eclip.rup.leb.models.EditOperation
import com.eclip.rup.leb.models.TextPosition
import com.eclip.rup.leb.commands.*
import com.eclip.rup.leb.models.id

fun VideoEditingViewModel.updateMainVideoTrim(startMs: Long, endMs: Long) {
    executeCommand(ReplaceUniqueOperationCommand(EditOperation.Trim(startMs, endMs), "Trim Video"))
}

fun VideoEditingViewModel.updateMainVideoSpeed(speed: Float, proxyUri: Uri?) {
    executeCommand(ReplaceUniqueOperationCommand(EditOperation.SpeedMain(speed, proxyUri), "Change Speed"))
}

fun VideoEditingViewModel.updateMainVideoReverse(isReversed: Boolean, proxyUri: Uri?) {
    executeCommand(ReplaceUniqueOperationCommand(EditOperation.ReverseMain(isReversed, proxyUri), "Reverse Video"))
}

fun VideoEditingViewModel.updateMainVideoMirror(isMirrored: Boolean) {
    executeCommand(ReplaceUniqueOperationCommand(EditOperation.MirrorMain(isMirrored), "Mirror Video"))
}

fun VideoEditingViewModel.updateMainVideoRotation(rotation: Int) {
    executeCommand(ReplaceUniqueOperationCommand(EditOperation.RotationMain(rotation), "Rotate Video"))
}

fun VideoEditingViewModel.updateMainVideoOpacity(opacity: Float) {
    executeCommand(ReplaceUniqueOperationCommand(EditOperation.OpacityMain(opacity), "Clip Opacity"))
}

fun VideoEditingViewModel.toggleMergeItemMirror(index: Int) {
    executeCommand(MutateListCommand("Mirror Clip") { ops ->
        val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
        if (mergeIdx != -1) {
            val mergeOp = ops[mergeIdx] as EditOperation.Merge
            val items = mergeOp.items.toMutableList()
            if (index >= 0 && index < items.size) {
                items[index] = items[index].copy(isMirrored = !items[index].isMirrored)
            }
            val newOps = ops.toMutableList()
            newOps[mergeIdx] = mergeOp.copy(items = items)
            newOps
        } else ops
    })
}

fun VideoEditingViewModel.addCropOperation(
    aspectRatio: String, xFraction: Float = 0f, yFraction: Float = 0f,
    wFraction: Float = 1f, hFraction: Float = 1f
) {
    executeCommand(ReplaceUniqueOperationCommand(
        EditOperation.Crop(aspectRatio, xFraction, yFraction, wFraction, hFraction),
        "Crop Video"
    ))
}

fun VideoEditingViewModel.updateCanvasBackgroundOperation(
    type: EditOperation.CanvasBackground.BackgroundType, colorHex: String = "#000000",
    imageUri: Uri? = null, blurRadius: Int = 20
) {
    executeCommand(ReplaceUniqueOperationCommand(
        EditOperation.CanvasBackground(type, colorHex, imageUri, blurRadius),
        "Set Canvas Background"
    ))
}

fun VideoEditingViewModel.addTextOperation(
    text: String, fontSize: Int, position: String, relativeX: Float? = null,
    relativeY: Float? = null, relativeWidth: Float? = null, color: String = "#FFFFFF", startTimeMs: Long? = null,
    endTimeMs: Long? = null, fontPath: String? = null, opacity: Float = 1.0f,
    borderThickness: Int = 0, borderColor: String = "#000000", textAlign: String = "center",
    letterSpacing: Float = 0f, lineSpacing: Float = 0f,
    inAnimation: String = "NONE", outAnimation: String = "NONE", animationDurationMs: Long = 500L,
    labelColor: String = "NONE", shadowColor: String = "NONE",
    labelOpacity: Float = 1.0f, borderOpacity: Float = 1.0f, shadowOpacity: Float = 1.0f,
    shadowRadius: Float = 8f, shadowDx: Float = 4f, shadowDy: Float = 4f,
    isBold: Boolean = false, isItalic: Boolean = false, isUnderline: Boolean = false, caseMode: Int = 0
) {
    executeCommand(AddOperationCommand(EditOperation.AddText(
        text = text, fontSize = fontSize, position = TextPosition.fromLabel(position),
        relativeX = relativeX, relativeY = relativeY, relativeWidth = relativeWidth,
        color = color, startTimeMs = startTimeMs,
        endTimeMs = endTimeMs, fontPath = fontPath, opacity = opacity, borderThickness = borderThickness,
        borderColor = borderColor, textAlign = textAlign, letterSpacing = letterSpacing, lineSpacing = lineSpacing,
        inAnimation = inAnimation, outAnimation = outAnimation, animationDurationMs = animationDurationMs,
        labelColor = labelColor, shadowColor = shadowColor,
        labelOpacity = labelOpacity, borderOpacity = borderOpacity, shadowOpacity = shadowOpacity,
        shadowRadius = shadowRadius, shadowDx = shadowDx, shadowDy = shadowDy,
        isBold = isBold, isItalic = isItalic, isUnderline = isUnderline, caseMode = caseMode
    ), "Add Text"))
}

fun VideoEditingViewModel.updateMergeItemTrim(index: Int, startMs: Long, endMs: Long) {
    executeCommand(MutateListCommand("Trim Clip") { ops ->
        val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
        if (mergeIdx != -1) {
            val mergeOp = ops[mergeIdx] as EditOperation.Merge
            val items = mergeOp.items.toMutableList()
            if (index >= 0 && index < items.size) {
                items[index] = items[index].copy(trimStartMs = startMs, trimEndMs = endMs)
            }
            val newOps = ops.toMutableList()
            newOps[mergeIdx] = mergeOp.copy(items = items)
            newOps
        } else ops
    })
}

fun VideoEditingViewModel.addMergeOperation(items: List<EditOperation.MergeItem>) {
    executeCommand(MutateListCommand("Merge Videos") { ops ->
        val existingIdx = ops.indexOfFirst { it is EditOperation.Merge }
        val newOps = ops.toMutableList()
        if (existingIdx != -1) {
            val existing = newOps[existingIdx] as EditOperation.Merge
            newOps[existingIdx] = existing.copy(items = existing.items + items)
        } else {
            newOps.add(EditOperation.Merge(items))
        }
        newOps
    })
}

fun VideoEditingViewModel.addTransitionOperation(index: Int, type: String, durationMs: Long = 1000L) {
    executeCommand(MutateListCommand("Add Transition") { ops ->
        val existingIdx = ops.indexOfFirst { it is EditOperation.Transition && it.index == index }
        val newOps = ops.toMutableList()
        val newOp = EditOperation.Transition(index, type, durationMs)
        if (existingIdx != -1) newOps[existingIdx] = newOp else newOps.add(newOp)
        newOps
    })
}

fun VideoEditingViewModel.updateSequenceOrder(orderedItems: List<EditOperation.MergeItem>) {
    executeCommand(MutateListCommand("Reorder Sequence") { ops ->
        val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
        val newOps = ops.toMutableList()
        if (mergeIdx != -1) {
            val mergeOp = ops[mergeIdx] as EditOperation.Merge
            if (orderedItems.isEmpty()) {
                newOps.removeAt(mergeIdx)
            } else {
                newOps[mergeIdx] = mergeOp.copy(items = orderedItems)
            }
        } else if (orderedItems.isNotEmpty()) {
            newOps.add(EditOperation.Merge(orderedItems))
        }
        newOps
    })
}

fun VideoEditingViewModel.updateFullSequenceOrder(orderedItems: List<EditOperation.MergeItem>) {
    if (orderedItems.isEmpty()) return
    executeCommand(com.eclip.rup.leb.commands.MutateProjectCommand("Reorder Sequence") { project ->
        val ops = project.operations.toMutableList()

        // 1. Extract previous top-level main operations before removing them
        val oldSpeedOp = ops.filterIsInstance<EditOperation.SpeedMain>().lastOrNull()
        val oldReverseOp = ops.filterIsInstance<EditOperation.ReverseMain>().lastOrNull()
        val oldMirrorOp = ops.filterIsInstance<EditOperation.MirrorMain>().lastOrNull()
        val oldMaskOp = ops.filterIsInstance<EditOperation.MaskMain>().lastOrNull()
        val oldRotationOp = ops.filterIsInstance<EditOperation.RotationMain>().lastOrNull()
        val oldOpacityOp = ops.filterIsInstance<EditOperation.OpacityMain>().lastOrNull()
        val oldChromaOp = ops.filterIsInstance<EditOperation.ChromaMain>().lastOrNull()
        val oldAnimOp = ops.filterIsInstance<EditOperation.AnimationMain>().lastOrNull()

        // 2. Enrich orderedItems so that the old primary clip moving into the merge list retains its properties
        val enrichedItems = orderedItems.map { item ->
            if (project.sourceUri != null && item.uri == project.sourceUri) {
                item.copy(
                    speed = if (item.speed != 1.0f) item.speed else (oldSpeedOp?.speed ?: 1.0f),
                    proxyUri = item.proxyUri ?: oldSpeedOp?.proxyUri ?: oldReverseOp?.proxyUri,
                    isReversed = item.isReversed || (oldReverseOp?.isReversed == true),
                    isMirrored = item.isMirrored || (oldMirrorOp?.isMirrored == true),
                    maskConfig = if (item.maskConfig.shape != EditOperation.MaskShape.NONE) item.maskConfig else (oldMaskOp?.maskConfig ?: item.maskConfig),
                    rotation = if (item.rotation != 0) item.rotation else (oldRotationOp?.rotation ?: 0),
                    opacity = if (item.opacity < 1.0f) item.opacity else (oldOpacityOp?.opacity ?: 1.0f),
                    chromaKeyColor = item.chromaKeyColor ?: oldChromaOp?.color,
                    chromaKeySimilarity = if (item.chromaKeyColor != null) item.chromaKeySimilarity else (oldChromaOp?.similarity ?: item.chromaKeySimilarity),
                    inAnimation = if (item.inAnimation != "NONE") item.inAnimation else (oldAnimOp?.inAnimation ?: "NONE"),
                    outAnimation = if (item.outAnimation != "NONE") item.outAnimation else (oldAnimOp?.outAnimation ?: "NONE"),
                    animationDurationMs = if (item.inAnimation != "NONE" || item.outAnimation != "NONE") item.animationDurationMs else (oldAnimOp?.animationDurationMs ?: 500L)
                )
            } else {
                item
            }
        }

        val primaryItem = enrichedItems[0]
        val mergeItems = enrichedItems.drop(1)

        // 3. Remove all old top-level main operations
        ops.removeAll {
            it is EditOperation.Trim ||
            it is EditOperation.SpeedMain ||
            it is EditOperation.ReverseMain ||
            it is EditOperation.MirrorMain ||
            it is EditOperation.MaskMain ||
            it is EditOperation.RotationMain ||
            it is EditOperation.OpacityMain ||
            it is EditOperation.ChromaMain ||
            it is EditOperation.AnimationMain
        }

        // 4. Add top-level main operations for the new primary clip
        ops.add(0, EditOperation.Trim(primaryItem.trimStartMs, primaryItem.trimEndMs))
        if (primaryItem.speed != 1.0f) {
            ops.add(EditOperation.SpeedMain(primaryItem.speed, primaryItem.proxyUri))
        }
        if (primaryItem.isReversed) {
            ops.add(EditOperation.ReverseMain(true, primaryItem.proxyUri))
        }
        if (primaryItem.isMirrored) {
            ops.add(EditOperation.MirrorMain(true))
        }
        if (primaryItem.maskConfig.shape != EditOperation.MaskShape.NONE) {
            ops.add(EditOperation.MaskMain(primaryItem.maskConfig))
        }
        if (primaryItem.rotation != 0) {
            ops.add(EditOperation.RotationMain(primaryItem.rotation))
        }
        if (primaryItem.opacity < 1.0f) {
            ops.add(EditOperation.OpacityMain(primaryItem.opacity))
        }
        if (!primaryItem.chromaKeyColor.isNullOrBlank()) {
            ops.add(EditOperation.ChromaMain(primaryItem.chromaKeyColor, primaryItem.chromaKeySimilarity))
        }
        if (primaryItem.inAnimation != "NONE" || primaryItem.outAnimation != "NONE") {
            ops.add(EditOperation.AnimationMain(primaryItem.inAnimation, primaryItem.outAnimation, primaryItem.animationDurationMs))
        }

        // 5. Update or remove the Merge operation
        val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
        if (mergeItems.isNotEmpty()) {
            val newMergeOp = EditOperation.Merge(mergeItems)
            if (mergeIdx != -1) {
                ops[mergeIdx] = newMergeOp
            } else {
                ops.add(newMergeOp)
            }
        } else {
            if (mergeIdx != -1) {
                ops.removeAt(mergeIdx)
            }
        }

        project.copy(
            sourceUri = primaryItem.uri,
            operations = ops
        )
    })
}

fun VideoEditingViewModel.removeMergeVideo(index: Int) {
    executeCommand(MutateListCommand("Remove Clip") { ops ->
        val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
        if (mergeIdx != -1) {
            val mergeOp = ops[mergeIdx] as EditOperation.Merge
            val items = mergeOp.items.toMutableList()
            if (index >= 0 && index < items.size) {
                items.removeAt(index)
            }
            val newOps = ops.toMutableList()
            if (items.isEmpty()) {
                newOps.removeAt(mergeIdx)
            } else {
                newOps[mergeIdx] = mergeOp.copy(items = items)
            }
            newOps
        } else ops
    })
}

fun VideoEditingViewModel.toggleMuteClip(index: Int) {
    executeCommand(MutateListCommand("Mute Clip") { ops ->
        val existingIdx = ops.indexOfFirst { it is EditOperation.MuteClip && it.index == index }
        val newOps = ops.toMutableList()
        if (existingIdx != -1) {
            val current = newOps[existingIdx] as EditOperation.MuteClip
            newOps[existingIdx] = current.copy(isMuted = !current.isMuted)
        } else {
            newOps.add(EditOperation.MuteClip(index, true))
        }
        newOps
    })
}

fun VideoEditingViewModel.setAllClipsMuteState(isMuted: Boolean, videoIndices: List<Int>) {
    executeCommand(MutateListCommand(if (isMuted) "Mute All Clips" else "Unmute All Clips") { ops ->
        val newOps = ops.toMutableList()
        
        videoIndices.forEach { index ->
            val existingIdx = newOps.indexOfFirst { it is EditOperation.MuteClip && it.index == index }
            if (existingIdx != -1) {
                val current = newOps[existingIdx] as EditOperation.MuteClip
                newOps[existingIdx] = current.copy(isMuted = isMuted)
            } else {
                newOps.add(EditOperation.MuteClip(index, isMuted))
            }
        }
        newOps
    })
}

fun VideoEditingViewModel.setColorFilter(index: Int, filterName: String) {
    executeCommand(MutateListCommand("Color Filter") { ops ->
        val existingIdx = ops.indexOfFirst { it is EditOperation.ColorFilter && it.index == index }
        val newOps = ops.toMutableList()
        if (existingIdx != -1) newOps[existingIdx] = EditOperation.ColorFilter(index, filterName)
        else newOps.add(EditOperation.ColorFilter(index, filterName))
        newOps
    })
}

fun VideoEditingViewModel.updateMergeItemMask(index: Int, maskConfig: EditOperation.MaskConfig) {
    executeCommand(MutateListCommand("Mask Clip") { ops ->
        val newOps = ops.toMutableList()
        if (index == 0) {
            val maskMainIdx = newOps.indexOfFirst { it is EditOperation.MaskMain }
            if (maskMainIdx != -1) {
                newOps[maskMainIdx] = EditOperation.MaskMain(maskConfig)
            } else {
                newOps.add(EditOperation.MaskMain(maskConfig))
            }
        } else {
            val mergeIdx = newOps.indexOfFirst { it is EditOperation.Merge }
            if (mergeIdx != -1) {
                val mergeOp = newOps[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                val targetIndex = index - 1
                if (targetIndex >= 0 && targetIndex < items.size) {
                    items[targetIndex] = items[targetIndex].copy(maskConfig = maskConfig)
                    newOps[mergeIdx] = mergeOp.copy(items = items)
                }
            }
        }
        newOps
    })
}

fun VideoEditingViewModel.addMuteAudioOperation() {
    executeCommand(ReplaceUniqueOperationCommand(EditOperation.MuteAudio(), "Mute Original Audio"))
}

fun VideoEditingViewModel.addBackgroundAudioOperation(
    audioUri: Uri, removeOriginalAudio: Boolean = false, volume: Float = 1.0f,
    internalStartMs: Long = 0L, internalEndMs: Long = -1L, startTimeMs: Long? = null,
    endTimeMs: Long? = null, originalDurationMs: Long = 0L, extractedFromSegmentIndex: Int? = null,
    beats: List<Long> = emptyList(), ducking: Boolean = false, fadeInDurationMs: Long = 0L,
    fadeOutDurationMs: Long = 0L
) {
    executeCommand(AddOperationCommand(EditOperation.AddBackgroundAudio(
        audioUri, removeOriginalAudio, volume, internalStartMs, internalEndMs, startTimeMs,
        endTimeMs, originalDurationMs, extractedFromSegmentIndex, beats, ducking, fadeInDurationMs, fadeOutDurationMs
    ), "Add Background Audio"))
}

fun VideoEditingViewModel.addImageOverlayOperation(
    imageUri: Uri, relativeX: Float, relativeY: Float, relativeWidth: Float, relativeHeight: Float,
    rotationAngle: Float, startTimeMs: Long? = null, endTimeMs: Long? = null, fileDurationMs: Long? = null,
    isLooping: Boolean = true, chromaKeyColor: String? = null, chromaKeySimilarity: Float = 0.1f,
    opacity: Float = 1.0f, isMirrored: Boolean = false, maskConfig: EditOperation.MaskConfig = EditOperation.MaskConfig()
) {
    executeCommand(AddOperationCommand(EditOperation.AddImageOverlay(
        imageUri, relativeX, relativeY, relativeWidth, relativeHeight, rotationAngle, startTimeMs,
        endTimeMs, id = System.nanoTime().toString(), fileDurationMs, isLooping, chromaKeyColor,
        chromaKeySimilarity, opacity, isMirrored, maskConfig = maskConfig
    ), "Add Image Overlay"))
}

fun VideoEditingViewModel.addOperation(operation: EditOperation) {
    executeCommand(AddOperationCommand(operation, "Add Effect"))
}

fun VideoEditingViewModel.updateOperation(updatedOp: EditOperation) {
    executeCommand(UpdateOperationCommand(updatedOp.id, "Modify Effect") { updatedOp })
}

fun VideoEditingViewModel.removeOperation(operationId: String) {
    executeCommand(RemoveOperationCommand(operationId, "Remove Effect"))
}

fun VideoEditingViewModel.deleteOperation(operationId: String) {
    selectOperation(null)
    executeCommand(RemoveOperationCommand(operationId, "Delete Effect"))
}

fun VideoEditingViewModel.clearAllOperations() {
    executeCommand(MutateListCommand("Clear All") { emptyList() })
}

fun VideoEditingViewModel.splitVideoSegment(index: Int, localSplitTimeMs: Long, sourceUri: Uri, sourceDuration: Long) {
    executeCommand(MutateListCommand("Split Clip") { ops ->
        val newOps = ops.toMutableList()
        if (index == 0) {
            val trimIndex = newOps.indexOfFirst { it is EditOperation.Trim }
            val currentTrim = if (trimIndex != -1) newOps[trimIndex] as EditOperation.Trim else EditOperation.Trim(0L, sourceDuration)
            val oldEndMs = currentTrim.endMs
            if (trimIndex != -1) newOps[trimIndex] = currentTrim.copy(endMs = localSplitTimeMs)
            else newOps.add(EditOperation.Trim(0L, localSplitTimeMs))
            
            val speedOp = newOps.filterIsInstance<EditOperation.SpeedMain>().lastOrNull()
            val reverseOp = newOps.filterIsInstance<EditOperation.ReverseMain>().lastOrNull()
            val mirrorOp = newOps.filterIsInstance<EditOperation.MirrorMain>().lastOrNull()
            val maskOp = newOps.filterIsInstance<EditOperation.MaskMain>().lastOrNull()
            val rotationOp = newOps.filterIsInstance<EditOperation.RotationMain>().lastOrNull()
            val opacityOp = newOps.filterIsInstance<EditOperation.OpacityMain>().lastOrNull()
            val chromaOp = newOps.filterIsInstance<EditOperation.ChromaMain>().lastOrNull()
            val animOp = newOps.filterIsInstance<EditOperation.AnimationMain>().lastOrNull()
            
            val speed = speedOp?.speed ?: 1.0f
            val isReversed = reverseOp?.isReversed ?: false
            val isMirrored = mirrorOp?.isMirrored ?: false
            val maskConfig = maskOp?.maskConfig ?: EditOperation.MaskConfig()
            val rotation = rotationOp?.rotation ?: 0
            val opacity = opacityOp?.opacity ?: 1.0f
            val chromaColor = chromaOp?.color
            val chromaSimilarity = chromaOp?.similarity ?: 0.1f
            val inAnim = animOp?.inAnimation ?: "NONE"
            val outAnim = animOp?.outAnimation ?: "NONE"
            val animDurationMs = animOp?.animationDurationMs ?: 500L

            // Invalidate proxy on the left half because trim boundary changed
            if (reverseOp != null && reverseOp.proxyUri != null) {
                val rIdx = newOps.indexOf(reverseOp)
                if (rIdx != -1) newOps[rIdx] = reverseOp.copy(proxyUri = null)
            }
            if (speedOp != null && speedOp.proxyUri != null) {
                val sIdx = newOps.indexOf(speedOp)
                if (sIdx != -1) newOps[sIdx] = speedOp.copy(proxyUri = null)
            }
            
            val mergeIdx = newOps.indexOfFirst { it is EditOperation.Merge }
            val path = sourceUri.path?.lowercase() ?: sourceUri.toString().lowercase()
            val isImg = path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".bmp")
            val newItem = EditOperation.MergeItem(
                uri = sourceUri,
                durationMs = sourceDuration,
                trimStartMs = localSplitTimeMs,
                trimEndMs = oldEndMs,
                speed = speed,
                isReversed = isReversed,
                isMirrored = isMirrored,
                maskConfig = maskConfig,
                proxyUri = null,
                isImage = isImg,
                rotation = rotation,
                opacity = opacity,
                chromaKeyColor = chromaColor,
                chromaKeySimilarity = chromaSimilarity,
                inAnimation = inAnim,
                outAnimation = outAnim,
                animationDurationMs = animDurationMs
            )
            if (mergeIdx != -1) {
                val mergeOp = newOps[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                items.add(0, newItem)
                newOps[mergeIdx] = mergeOp.copy(items = items)
            } else {
                newOps.add(EditOperation.Merge(listOf(newItem)))
            }
        } else {
            val mergeIdx = newOps.indexOfFirst { it is EditOperation.Merge }
            if (mergeIdx != -1) {
                val mergeOp = newOps[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                val targetIndex = index - 1
                if (targetIndex >= 0 && targetIndex < items.size) {
                    val item = items[targetIndex]
                    val clearProxy = item.isReversed || item.speed != 1.0f
                    val newProxy = if (clearProxy) null else item.proxyUri
                    items[targetIndex] = item.copy(trimEndMs = localSplitTimeMs, proxyUri = newProxy)
                    items.add(targetIndex + 1, item.copy(trimStartMs = localSplitTimeMs, proxyUri = newProxy))
                    newOps[mergeIdx] = mergeOp.copy(items = items)
                }
            }
        }
        newOps
    })
}

fun VideoEditingViewModel.deleteSequenceSegment(index: Int) {
    executeCommand(com.eclip.rup.leb.commands.MutateProjectCommand("Delete Segment") { project ->
        val ops = project.operations.toMutableList()
        var newSourceUri = project.sourceUri
        
        if (index == 0) {
            val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
            if (mergeIdx != -1) {
                val mergeOp = ops[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                if (items.isNotEmpty()) {
                    val promotedItem = items.removeAt(0)
                    newSourceUri = promotedItem.uri
                    
                    ops.removeAll { it is EditOperation.Trim || it is EditOperation.SpeedMain || it is EditOperation.ReverseMain || it is EditOperation.MirrorMain || it is EditOperation.MaskMain }
                    
                    ops.add(0, EditOperation.Trim(promotedItem.trimStartMs, promotedItem.trimEndMs))
                    if (promotedItem.speed != 1.0f) {
                        ops.add(EditOperation.SpeedMain(promotedItem.speed, promotedItem.proxyUri))
                    }
                    if (promotedItem.isReversed) {
                        ops.add(EditOperation.ReverseMain(true, promotedItem.proxyUri))
                    }
                    if (promotedItem.isMirrored) {
                        ops.add(EditOperation.MirrorMain(true))
                    }
                    if (promotedItem.maskConfig.shape != EditOperation.MaskShape.NONE) {
                        ops.add(EditOperation.MaskMain(promotedItem.maskConfig))
                    }
                    
                    if (items.isEmpty()) {
                        ops.remove(mergeOp)
                    } else {
                        ops[ops.indexOf(mergeOp)] = mergeOp.copy(items = items)
                    }
                }
            }
        } else {
            val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
            if (mergeIdx != -1) {
                val mergeOp = ops[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                val targetIdx = index - 1
                if (targetIdx >= 0 && targetIdx < items.size) {
                    items.removeAt(targetIdx)
                    if (items.isEmpty()) {
                        ops.removeAt(mergeIdx)
                    } else {
                        ops[mergeIdx] = mergeOp.copy(items = items)
                    }
                }
            }
        }
        
        project.copy(sourceUri = newSourceUri, operations = ops)
    })
}

fun VideoEditingViewModel.setAdjust(
    index: Int, brightness: Int = 0, contrast: Int = 0, warmth: Int = 0, shadow: Int = 0,
    highlights: Int = 0, saturation: Int = 0, exposure: Int = 0, sharpen: Int = 0, vignette: Int = 0
) {
    executeCommand(MutateListCommand("Adjust Visuals") { ops ->
        val existingIdx = ops.indexOfFirst { it is EditOperation.Adjust && it.index == index }
        val newOp = EditOperation.Adjust(index, brightness, contrast, warmth, shadow, highlights, saturation, exposure, sharpen, vignette)
        val newOps = ops.toMutableList()
        if (existingIdx != -1) {
            if (newOp.isDefault()) newOps.removeAt(existingIdx) else newOps[existingIdx] = newOp
        } else if (!newOp.isDefault()) {
            newOps.add(newOp)
        }
        newOps
    })
}

fun VideoEditingViewModel.moveOverlayOperation(id: String, moveUp: Boolean) {
    executeCommand(MutateListCommand("Reorder Layers") { ops ->
        val newOps = ops.toMutableList()
        val targetIndex = newOps.indexOfFirst {
            (it is EditOperation.AddText && it.id == id) ||
            (it is EditOperation.AddImageOverlay && it.id == id) ||
            (it is EditOperation.MosaicOverlay && it.id == id)
        }
        
        if (targetIndex != -1) {
            val overlayIndices = newOps.indices.filter { idx ->
                val op = newOps[idx]
                op is EditOperation.AddText || op is EditOperation.AddImageOverlay || op is EditOperation.MosaicOverlay
            }
            
            val relativeIdx = overlayIndices.indexOf(targetIndex)
            if (relativeIdx != -1) {
                if (moveUp && relativeIdx < overlayIndices.size - 1) {
                    val swapWithGlobalIdx = overlayIndices[relativeIdx + 1]
                    val temp = newOps[targetIndex]
                    newOps[targetIndex] = newOps[swapWithGlobalIdx]
                    newOps[swapWithGlobalIdx] = temp
                } else if (!moveUp && relativeIdx > 0) {
                    val swapWithGlobalIdx = overlayIndices[relativeIdx - 1]
                    val temp = newOps[targetIndex]
                    newOps[targetIndex] = newOps[swapWithGlobalIdx]
                    newOps[swapWithGlobalIdx] = temp
                }
            }
        }
        newOps
    })
}

fun VideoEditingViewModel.setScrubProxyUri(dependencyId: String, proxyUri: Uri) {
    executeCommand(MutateProjectCommand("Proxy Generated") { project ->
        if (dependencyId == "primary") {
            project.copy(scrubProxyUri = proxyUri)
        } else if (dependencyId.startsWith("merge_")) {
            val parts = dependencyId.split("_")
            if (parts.size >= 3) {
                val operationId = parts[1]
                val mergeIndex = parts[2].toIntOrNull() ?: -1
                
                val newOps = project.operations.map { op ->
                    if (op is EditOperation.Merge && op.id == operationId) {
                        val newItems = op.items.toMutableList()
                        if (mergeIndex in newItems.indices) {
                            newItems[mergeIndex] = newItems[mergeIndex].copy(scrubProxyUri = proxyUri)
                        }
                        op.copy(items = newItems)
                    } else op
                }
                project.copy(operations = newOps)
            } else project
        } else {
            project
        }
    })
}

fun VideoEditingViewModel.addMosaicOverlay(
    relativeX: Float = 0.5f,
    relativeY: Float = 0.5f,
    relativeWidth: Float = 0.35f,
    relativeHeight: Float = 0.35f,
    intensity: Int = 15,
    shape: EditOperation.MaskShape = EditOperation.MaskShape.RECTANGLE,
    startTimeMs: Long? = null,
    endTimeMs: Long? = null
) {
    executeCommand(AddOperationCommand(EditOperation.MosaicOverlay(
        relativeX = relativeX,
        relativeY = relativeY,
        relativeWidth = relativeWidth,
        relativeHeight = relativeHeight,
        intensity = intensity,
        shape = shape,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs
    ), "Add Mosaic Effect"))
}

fun VideoEditingViewModel.updateMergeItemRotation(index: Int, rotation: Float) {
    if (index == 0) {
        updateMainVideoRotation(rotation.toInt())
    } else {
        executeCommand(MutateListCommand("Rotate Clip") { ops ->
            val newOps = ops.toMutableList()
            val mergeIdx = newOps.indexOfFirst { it is EditOperation.Merge }
            if (mergeIdx != -1) {
                val mergeOp = newOps[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                val targetIdx = index - 1
                if (targetIdx in items.indices) {
                    items[targetIdx] = items[targetIdx].copy(rotation = rotation.toInt())
                    newOps[mergeIdx] = mergeOp.copy(items = items)
                }
            }
            newOps
        })
    }
}

fun VideoEditingViewModel.updateMergeItemOpacity(index: Int, opacity: Float) {
    if (index == 0) {
        updateMainVideoOpacity(opacity)
    } else {
        executeCommand(MutateListCommand("Clip Opacity") { ops ->
            val newOps = ops.toMutableList()
            val mergeIdx = newOps.indexOfFirst { it is EditOperation.Merge }
            if (mergeIdx != -1) {
                val mergeOp = newOps[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                val targetIdx = index - 1
                if (targetIdx in items.indices) {
                    items[targetIdx] = items[targetIdx].copy(opacity = opacity)
                    newOps[mergeIdx] = mergeOp.copy(items = items)
                }
            }
            newOps
        })
    }
}

fun VideoEditingViewModel.updateMergeItemChroma(index: Int, color: String?, similarity: Float = 0.1f) {
    executeCommand(MutateListCommand("Clip Chroma Key") { ops ->
        val newOps = ops.toMutableList()
        if (index == 0) {
            val chromaIdx = newOps.indexOfFirst { it is EditOperation.ChromaMain }
            if (chromaIdx != -1) {
                newOps[chromaIdx] = EditOperation.ChromaMain(color, similarity)
            } else {
                newOps.add(EditOperation.ChromaMain(color, similarity))
            }
        } else {
            val mergeIdx = newOps.indexOfFirst { it is EditOperation.Merge }
            if (mergeIdx != -1) {
                val mergeOp = newOps[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                val targetIdx = index - 1
                if (targetIdx in items.indices) {
                    items[targetIdx] = items[targetIdx].copy(chromaKeyColor = color, chromaKeySimilarity = similarity)
                    newOps[mergeIdx] = mergeOp.copy(items = items)
                }
            }
        }
        newOps
    })
}

fun VideoEditingViewModel.updateMergeItemAnimation(index: Int, inAnim: String, outAnim: String, durationMs: Long) {
    executeCommand(MutateListCommand("Clip Animation") { ops ->
        val newOps = ops.toMutableList()
        if (index == 0) {
            val animIdx = newOps.indexOfFirst { it is EditOperation.AnimationMain }
            if (animIdx != -1) {
                newOps[animIdx] = EditOperation.AnimationMain(inAnim, outAnim, durationMs)
            } else {
                newOps.add(EditOperation.AnimationMain(inAnim, outAnim, durationMs))
            }
        } else {
            val mergeIdx = newOps.indexOfFirst { it is EditOperation.Merge }
            if (mergeIdx != -1) {
                val mergeOp = newOps[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                val targetIdx = index - 1
                if (targetIdx in items.indices) {
                    items[targetIdx] = items[targetIdx].copy(inAnimation = inAnim, outAnimation = outAnim, animationDurationMs = durationMs)
                    newOps[mergeIdx] = mergeOp.copy(items = items)
                }
            }
        }
        newOps
    })
}

fun VideoEditingViewModel.duplicateSequenceSegment(index: Int, sourceDurationMs: Long = 0L) {
    executeCommand(MutateListCommand("Duplicate Clip") { ops ->
        val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
        val newOps = ops.toMutableList()
        if (index == 0) {
            val sourceUri = project.value?.sourceUri
            if (sourceUri != null) {
                val trimOp = ops.filterIsInstance<EditOperation.Trim>().lastOrNull()
                val startMs = trimOp?.startMs ?: 0L
                val endMs = trimOp?.endMs ?: 5000L
                val totalDuration = if (sourceDurationMs > 0L) sourceDurationMs else endMs

                val speedOp = ops.filterIsInstance<EditOperation.SpeedMain>().lastOrNull()
                val reverseOp = ops.filterIsInstance<EditOperation.ReverseMain>().lastOrNull()
                val mirrorOp = ops.filterIsInstance<EditOperation.MirrorMain>().lastOrNull()
                val maskOp = ops.filterIsInstance<EditOperation.MaskMain>().lastOrNull()
                val rotationOp = ops.filterIsInstance<EditOperation.RotationMain>().lastOrNull()
                val opacityOp = ops.filterIsInstance<EditOperation.OpacityMain>().lastOrNull()
                val chromaOp = ops.filterIsInstance<EditOperation.ChromaMain>().lastOrNull()
                val animOp = ops.filterIsInstance<EditOperation.AnimationMain>().lastOrNull()

                val speed = speedOp?.speed ?: 1.0f
                val isReversed = reverseOp?.isReversed ?: false
                val isMirrored = mirrorOp?.isMirrored ?: false
                val maskConfig = maskOp?.maskConfig ?: EditOperation.MaskConfig()
                val rotation = rotationOp?.rotation ?: 0
                val opacity = opacityOp?.opacity ?: 1.0f
                val chromaColor = chromaOp?.color
                val chromaSimilarity = chromaOp?.similarity ?: 0.1f
                val inAnim = animOp?.inAnimation ?: "NONE"
                val outAnim = animOp?.outAnimation ?: "NONE"
                val animDurationMs = animOp?.animationDurationMs ?: 500L
                val path = sourceUri.path?.lowercase() ?: sourceUri.toString().lowercase()
                val isImg = path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".bmp")

                val dup = EditOperation.MergeItem(
                    uri = sourceUri,
                    durationMs = totalDuration,
                    trimStartMs = startMs,
                    trimEndMs = endMs,
                    speed = speed,
                    proxyUri = null,
                    isReversed = isReversed,
                    isMirrored = isMirrored,
                    maskConfig = maskConfig,
                    isImage = isImg,
                    rotation = rotation,
                    opacity = opacity,
                    chromaKeyColor = chromaColor,
                    chromaKeySimilarity = chromaSimilarity,
                    inAnimation = inAnim,
                    outAnimation = outAnim,
                    animationDurationMs = animDurationMs
                )

                if (mergeIdx != -1) {
                    val mergeOp = newOps[mergeIdx] as EditOperation.Merge
                    val items = mergeOp.items.toMutableList()
                    items.add(0, dup)
                    newOps[mergeIdx] = mergeOp.copy(items = items)
                } else {
                    newOps.add(EditOperation.Merge(listOf(dup)))
                }
            }
        } else {
            if (mergeIdx != -1) {
                val mergeOp = newOps[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                val targetIdx = index - 1
                if (targetIdx in items.indices) {
                    val dup = items[targetIdx].copy()
                    items.add(targetIdx + 1, dup)
                    newOps[mergeIdx] = mergeOp.copy(items = items)
                }
            }
        }
        newOps
    })
}

