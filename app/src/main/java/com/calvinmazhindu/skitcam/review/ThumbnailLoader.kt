package com.calvinmazhindu.skitcam.review

import android.graphics.Bitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.calvinmazhindu.skitcam.recording.Segment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

sealed interface ThumbnailResult {
    data object Loading : ThumbnailResult
    data class Ready(val bitmap: Bitmap) : ThumbnailResult
    data object Unavailable : ThumbnailResult
}

class ThumbnailLoader {
    private val decoder = Semaphore(1)
    private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // Do not recycle evicted bitmaps: a visible Compose image may still reference them.
    }
    /** Cache-only lookup, so Review can show a still on its first composition. */
    fun cached(segment: Segment): Bitmap? {
        val file = File(segment.path)
        if (!file.isFile || !file.canRead() || file.length() == 0L) return null
        val range = segment.trim?.takeIf { it.valid() }
        return cache.get(cacheKey(file, range?.trimStartMs ?: 0L, range?.trimEndMs ?: segment.durationMs))
    }

    private fun cacheKey(file: File, start: Long, end: Long) =
        "${file.path}:${file.length()}:${file.lastModified()}:$start:$end"

    /** Thumbnail of the retained range, avoiding an empty-looking opening frame. */
    suspend fun load(segment: Segment): ThumbnailResult = withContext(Dispatchers.IO) {
        decoder.withPermit {
            coroutineContext.ensureActive()
            val file = File(segment.path)
            if (!file.isFile || !file.canRead() || file.length() == 0L) return@withPermit ThumbnailResult.Unavailable
            val range = segment.trim?.takeIf { it.valid() }
            val start = range?.trimStartMs ?: 0L
            val end = range?.trimEndMs ?: segment.durationMs
            val times = thumbnailSampleTimes(start, end)
            val key = cacheKey(file, start, end)
            cache.get(key)?.let { return@withPermit ThumbnailResult.Ready(it) }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(segment.path)
                var first: Bitmap? = null
                for (sampleMs in times) {
                    val bitmap = frameAt(retriever, sampleMs) ?: continue
                    coroutineContext.ensureActive()
                    if (first == null) first = bitmap
                    if (!looksEmpty(bitmap)) {
                        if (bitmap !== first) first.recycle()
                        cache.put(key, bitmap)
                        return@withPermit ThumbnailResult.Ready(bitmap)
                    }
                    if (bitmap !== first) bitmap.recycle()
                }
                coroutineContext.ensureActive()
                if (first == null) ThumbnailResult.Unavailable else {
                    cache.put(key, first)
                    ThumbnailResult.Ready(first)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ThumbnailResult.Unavailable }
            finally { try { retriever.release() } catch (_: Exception) { /* No media resource escapes this request. */ } }
        }
    }

    /**
     * Exact frame at [sampleMs]. OPTION_CLOSEST_SYNC would return the nearest
     * keyframe, which on long camera GOPs is the clip's dark opening frame.
     */
    private fun frameAt(retriever: MediaMetadataRetriever, sampleMs: Long): Bitmap? {
        val timeUs = sampleMs.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
        return try {
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 160, 160)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let(::scaled)
            }
        } catch (_: Exception) {
            // Fall back to the keyframe when this decoder cannot seek precisely.
            runCatching {
                if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::scaled)
                }
            }.getOrNull()
        }
    }

    private fun scaled(original: Bitmap): Bitmap {
        val scale = 160f / maxOf(original.width, original.height)
        val scaled = original.scale(
            (original.width * scale).toInt().coerceAtLeast(1),
            (original.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== original) original.recycle()
        return scaled
    }

    private fun looksEmpty(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 8).coerceAtLeast(1)
        val luma = ArrayList<Int>(64)
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val pixel = bitmap[x, y]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                luma += (r * 299 + g * 587 + b * 114) / 1000
                x += stepX
            }
            y += stepY
        }
        return isDarkImage(luma.toIntArray())
    }
}
