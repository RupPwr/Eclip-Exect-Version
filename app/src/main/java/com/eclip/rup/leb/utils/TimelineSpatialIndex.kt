package com.eclip.rup.leb.utils

/**
 * TimelineSpatialIndex partitions the timeline into fixed-size temporal buckets (e.g. 2000ms),
 * converting O(N) linear scans across cuts, overlays, and clips into instant O(1) mathematical
 * bucket queries and O(k) viewport range lookups.
 *
 * @param T The data type of the indexed timeline element.
 * @param bucketDurationMs The duration of each temporal bucket in milliseconds (default: 2000ms).
 */
class TimelineSpatialIndex<T>(
    val bucketDurationMs: Long = 2000L
) {

    data class SpatialEntry<T>(
        val item: T,
        val startMs: Long,
        val endMs: Long
    )

    private val buckets = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CopyOnWriteArrayList<SpatialEntry<T>>>()
    private val itemToBucketsMap = java.util.concurrent.ConcurrentHashMap<T, java.util.concurrent.CopyOnWriteArraySet<Long>>()

    val size: Int
        get() = itemToBucketsMap.size

    /**
     * Inserts an item spanning [startMs, endMs] into the spatial index.
     * The item is referenced in all temporal buckets it intersects.
     */
    fun insert(item: T, startMs: Long, endMs: Long) {
        if (endMs < startMs || startMs < 0) return

        // Remove previous indexing if item already exists
        remove(item)

        val entry = SpatialEntry(item, startMs, endMs)
        val startBucket = startMs / bucketDurationMs
        val endBucket = endMs / bucketDurationMs

        val bucketSet = itemToBucketsMap.getOrPut(item) { java.util.concurrent.CopyOnWriteArraySet() }

        for (b in startBucket..endBucket) {
            bucketSet.add(b)
            val bucketList = buckets.getOrPut(b) { java.util.concurrent.CopyOnWriteArrayList() }
            bucketList.add(entry)
        }
    }

    /**
     * Removes an item from all indexed buckets.
     */
    fun remove(item: T) {
        val assignedBuckets = itemToBucketsMap.remove(item) ?: return
        for (b in assignedBuckets) {
            buckets[b]?.removeAll { it.item == item }
            if (buckets[b].isNullOrEmpty()) {
                buckets.remove(b)
            }
        }
    }

    /**
     * Clears all items and buckets from the spatial index.
     */
    fun clear() {
        buckets.clear()
        itemToBucketsMap.clear()
    }

    /**
     * O(1) query returning all items active at the exact playhead position [timeMs].
     */
    fun queryPoint(timeMs: Long): List<T> {
        if (timeMs < 0) return emptyList()

        val bucketIndex = timeMs / bucketDurationMs
        val bucketList = buckets[bucketIndex] ?: return emptyList()
        val result = mutableListOf<T>()
        for (entry in bucketList) {
            if (timeMs in entry.startMs..entry.endMs) {
                result.add(entry.item)
            }
        }
        return result
    }

    /**
     * O(k) range query returning all items intersecting the viewport [startMs, endMs],
     * completely bypassing offscreen buckets.
     */
    fun queryRange(startMs: Long, endMs: Long): List<T> {
        if (endMs < startMs || startMs < 0) return emptyList()

        val startBucket = startMs / bucketDurationMs
        val endBucket = endMs / bucketDurationMs

        val seen = HashSet<T>()
        val result = mutableListOf<T>()

        for (b in startBucket..endBucket) {
            val bucketList = buckets[b] ?: continue
            for (entry in bucketList) {
                // Check if entry overlaps with the requested range
                if (entry.startMs <= endMs && entry.endMs >= startMs) {
                    if (seen.add(entry.item)) {
                        result.add(entry.item)
                    }
                }
            }
        }
        return result
    }

    /**
     * O(k) range query returning all SpatialEntries intersecting [startMs, endMs].
     */
    fun queryRangeEntries(startMs: Long, endMs: Long): List<SpatialEntry<T>> {
        if (endMs < startMs || startMs < 0) return emptyList()

        val startBucket = startMs / bucketDurationMs
        val endBucket = endMs / bucketDurationMs

        val seen = HashSet<T>()
        val result = mutableListOf<SpatialEntry<T>>()

        for (b in startBucket..endBucket) {
            val bucketList = buckets[b] ?: continue
            for (entry in bucketList) {
                if (entry.startMs <= endMs && entry.endMs >= startMs) {
                    if (seen.add(entry.item)) {
                        result.add(entry)
                    }
                }
            }
        }
        return result
    }
}
