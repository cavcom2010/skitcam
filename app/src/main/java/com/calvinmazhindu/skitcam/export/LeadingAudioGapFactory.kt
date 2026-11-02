package com.calvinmazhindu.skitcam.export

import android.content.Context
import android.media.MediaExtractor
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.transformer.*
import java.nio.ByteBuffer

/** Represents an existing leading presentation gap in PCM before Media3 counts/pads clip duration.
 * Does not change source files, remove samples, or normalise audio independently of video.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LeadingAudioGapFactory(private val context: Context, private val observed: (Long, Long) -> Unit = { _, _ -> }) : AssetLoader.Factory {
    private val delegate = DefaultAssetLoaderFactory(context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT, null)
    override fun createAssetLoader(item: EditedMediaItem, looper: Looper, listener: AssetLoader.Listener,
        settings: AssetLoader.CompositionSettings): AssetLoader {
        var durationUs = C.TIME_UNSET
        // A decoder may report a positive first PTS because of AAC priming. Only a
        // source-declared leading gap is eligible; zero-start/gapless sources stay unchanged.
        val extractor = MediaExtractor()
        val sourceGapUs = try {
            extractor.setDataSource(context, checkNotNull(item.mediaItem.localConfiguration).uri, null)
            val audio = (0 until extractor.trackCount).first { extractor.getTrackFormat(it)
                .getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(audio)
            TimingMeasurement.sourceGapUs(extractor.sampleTime, item.mediaItem.clippingConfiguration.startPositionUs)
        } finally { extractor.release() }
        return delegate.createAssetLoader(item, looper, object : AssetLoader.Listener by listener {
            override fun onDurationUs(duration: Long) { durationUs = duration;listener.onDurationUs(duration) }
            override fun onOutputFormat(format: Format): SampleConsumer? {
                val consumer = listener.onOutputFormat(format) ?: return null
                if(format.sampleMimeType != MimeTypes.AUDIO_RAW) return consumer
                return LeadingAudioGapConsumer(consumer, format, sourceGapUs, { durationUs }, observed)
            }
        }, settings)
    }
}

/** Explicit forwarding is required: Kotlin delegation does not override Java default methods. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LeadingAudioGapConsumer(
    private val delegate: SampleConsumer,
    private val format: Format,
    private val sourceGapUs: Long,
    private val durationUs: () -> Long,
    private val observed: (Long, Long) -> Unit = { _, _ -> }
) : SampleConsumer {
    private var prepared = false
    override fun getInputBuffer() = delegate.inputBuffer
    override fun queueInputBuffer(): Boolean {
        val buffer = checkNotNull(delegate.inputBuffer)
        if(!prepared && !buffer.isEndOfStream && buffer.data?.hasRemaining() == true) {
            prepared = true // Retrying a backpressured queue must not insert the gap twice.
            val pts = buffer.timeUs
            if(sourceGapUs > 0 && pts > 0) {
                require(format.channelCount in 1..2) { "Unsupported decoded audio channel layout" }
                val bytesPerSample = when(format.pcmEncoding) {
                    C.ENCODING_PCM_16BIT -> 2
                    C.ENCODING_PCM_FLOAT -> 4
                    else -> error("Unsupported decoded PCM format for leading gap")
                }
                // Never add more than the source-declared gap or the first decoded PTS.
                val gap = TimingMeasurement.leadingGapFrames(minOf(sourceGapUs,pts), format.sampleRate, durationUs())
                val gapBytes = Math.toIntExact(Math.multiplyExact(gap, (format.channelCount * bytesPerSample).toLong()))
                val original = checkNotNull(buffer.data)
                val expanded = ByteBuffer.allocateDirect(Math.addExact(gapBytes, original.remaining()))
                expanded.position(gapBytes);expanded.put(original.duplicate());expanded.flip()
                buffer.data = expanded
                buffer.timeUs = (pts - TimingMeasurement.endUs(0,gap,format.sampleRate)).coerceAtLeast(0)
                observed(pts,gap)
            }
        }
        return delegate.queueInputBuffer()
    }
}
