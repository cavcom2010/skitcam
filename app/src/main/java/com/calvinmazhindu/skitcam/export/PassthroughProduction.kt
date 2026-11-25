package com.calvinmazhindu.skitcam.export

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.ExportException
import java.io.IOException

internal class ExportPassthroughIncompatibility(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Runtime evidence from Media3's encoded asset loader, not just the earlier extractor probe. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PassthroughProduction {
    private var first: Format? = null

    @Synchronized fun checkVideo(format: Format) {
        if (ExportFaults.has(ExportFaults.Mode.CANCEL_AT_PASSTHROUGH)) ExportJournal.cancelRequested = true
        checkCancelled()
        val observed = if (ExportFaults.has(ExportFaults.Mode.FORMAT_WITHOUT_AVC_CONFIG))
            format.buildUpon().setInitializationData(emptyList()).build() else format
        val previous = first
        if (observed.sampleMimeType != MimeTypes.VIDEO_H264 || observed.width <= 0 ||
            observed.height <= 0 || observed.initializationData.isEmpty() ||
            observed.initializationData.any { it.isEmpty() } ||
            (previous != null && (!observed.initializationDataEquals(previous) ||
                observed.width != previous.width || observed.height != previous.height ||
                observed.rotationDegrees != previous.rotationDegrees || observed.colorInfo != previous.colorInfo ||
                observed.pixelWidthHeightRatio != previous.pixelWidthHeightRatio))) {
            throw ExportException.createForAssetLoader(
                ExportPassthroughIncompatibility("Media3 cannot use one compatible encoded AVC track for this skit."),
                ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK)
        }
        first = observed
    }

    /** Keep the stock muxer. Only rejection of encoded AVC track arguments is a fallback signal. */
    fun muxerFactory(delegate: Muxer.Factory = DefaultMuxer.Factory()): Muxer.Factory =
        object : Muxer.Factory by delegate {
            override fun create(path: String): Muxer {
                val muxer = delegate.create(path)
                return object : Muxer by muxer {
                    override fun addTrack(format: Format): Int = try {
                        muxer.addTrack(format)
                    } catch (failure: Exception) {
                        val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
                        if (format.sampleMimeType != MimeTypes.VIDEO_H264 || chain.any { it is IOException } ||
                            chain.none { it is IllegalArgumentException }) throw failure
                        throw MuxerException("Encoded AVC track rejected",
                            ExportPassthroughIncompatibility("The muxer rejected the passthrough AVC track.", failure))
                    }
                }
            }
        }
}
