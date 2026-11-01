package com.calvinmazhindu.skitcam.review

import android.graphics.Bitmap
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
    suspend fun load(segment: Segment): ThumbnailResult = withContext(Dispatchers.IO) {
        decoder.withPermit {
            coroutineContext.ensureActive()
            val file = File(segment.path)
            if (!file.isFile || !file.canRead() || file.length() == 0L) return@withPermit ThumbnailResult.Unavailable
            val key = "${segment.path}:${file.length()}:${file.lastModified()}"
            cache.get(key)?.let { return@withPermit ThumbnailResult.Ready(it) }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(segment.path)
                val timeUs = minOf(500, segment.durationMs / 2) * 1000
                val bitmap = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                        val scale = 160f / maxOf(original.width, original.height)
                        val scaled = Bitmap.createScaledBitmap(original,
                            (original.width * scale).toInt().coerceAtLeast(1),
                            (original.height * scale).toInt().coerceAtLeast(1), true)
                        if (scaled !== original) original.recycle()
                        scaled
                    }
                }
                coroutineContext.ensureActive()
                if (bitmap == null) ThumbnailResult.Unavailable else {
                    cache.put(key, bitmap)
                    ThumbnailResult.Ready(bitmap)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ThumbnailResult.Unavailable }
            finally { try { retriever.release() } catch (_: Exception) { /* No media resource escapes this request. */ } }
        }
    }
}
