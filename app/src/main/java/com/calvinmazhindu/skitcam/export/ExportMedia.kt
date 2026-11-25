package com.calvinmazhindu.skitcam.export

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.*
import androidx.media3.common.audio.*
import androidx.media3.effect.*
import androidx.media3.transformer.*
import com.calvinmazhindu.skitcam.recording.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun sha(file: File): String = file.inputStream().use { input ->
    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
    while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun checkCancelled() { check(!ExportJournal.cancelRequested) { "Export cancelled." } }
internal data class Geometry(val width: Int,val height: Int,val duration: Long)
internal fun inspect(file: File): Geometry {
    require(file.isFile && file.canRead() && file.length()>0) { "Source unavailable: ${file.name}" }
    val extractor=MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        val formats=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }.forEach {
            require(!it.containsKey(MediaFormat.KEY_COLOR_TRANSFER) || it.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !in setOf(MediaFormat.COLOR_TRANSFER_ST2084,MediaFormat.COLOR_TRANSFER_HLG)) { "HDR input is not supported by this export profile." }
        }
        require(formats.count { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }==1 &&
            formats.count { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }==1) { "Exactly one audio and video track required: ${file.name}" }
    } finally { extractor.release() }
    val retriever=MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.path)
        val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
        require(width>0 && height>0 && duration>0)
        return if(rotation%180==0) Geometry(width,height,duration) else Geometry(height,width,duration)
    } finally { retriever.release() }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun transform(context: Context, spec: ExportSpec, output: File, traceId: String? = null,
    videoPassthrough: Boolean = false, decoderFactory: Codec.DecoderFactory? = null,
    onProgress: (Int?) -> Unit = {}) {
    val sizes=spec.clips.map { inspect(File(it.path)) }
    val first=sizes.first()
    require(sizes.all { kotlin.math.abs(it.width.toDouble()/it.height-first.width.toDouble()/first.height)<0.005 }) { "Segments have incompatible display aspect ratios." }
    val matching=spec.captureProfile?.takeUnless { it.legacy }
    matching?.let { profile -> require(sizes.all { maxOf(it.width,it.height)==maxOf(profile.width,profile.height) && minOf(it.width,it.height)==minOf(profile.width,profile.height) }) { "Sources do not match the immutable export profile." } }
    val scale=minOf(1.0,if(matching==null) 720.0/minOf(first.width,first.height) else 1.0,if(matching==null) 1280.0/maxOf(first.width,first.height) else 1.0,
        sizes.minOf { minOf(it.width.toDouble()/first.width,it.height.toDouble()/first.height) })
    val width=((first.width*scale).toInt()/2*2).coerceAtLeast(2)
    val height=((first.height*scale).toInt()/2*2).coerceAtLeast(2)
    val items=spec.clips.map { clip ->
        val sonic=SonicAudioProcessor().apply { setOutputSampleRateHz(48000) }
        val channels=ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1,2))
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2,2))
        }
        val item=MediaItem.Builder().setUri(Uri.fromFile(File(clip.path)))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.range.trimStartMs).setEndPositionMs(clip.range.trimEndMs).build()).build()
        val videoEffects=if(videoPassthrough) emptyList() else listOf<androidx.media3.common.Effect>(Presentation.createForWidthAndHeight(width,height,Presentation.LAYOUT_SCALE_TO_FIT))
        EditedMediaItem.Builder(item).apply { if(matching==null) setFrameRate(30) }.setEffects(Effects(listOf(channels,sonic),videoEffects)).build()
    }
    val composition=Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(items))
        .setTransmuxAudio(false).setTransmuxVideo(videoPassthrough).build()
    val passthrough = if (videoPassthrough) PassthroughProduction() else null
    val thread=HandlerThread("SkitCam export").apply { start() };val handler=Handler(thread.looper)
    val finished=CountDownLatch(1);var failure: Exception?=null;var transformer: Transformer?=null
    handler.post {
        try {
            val delegate=DefaultEncoderFactory.Builder(context).setEnableFallback(false)
                .apply { if(matching==null) setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(4_000_000).build()) }
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(128_000).setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectLC).build()).build()
            val factory=object : Codec.EncoderFactory by delegate {
                override fun audioNeedsEncoding()=true
                override fun videoNeedsEncoding()=!videoPassthrough
            }
            transformer=Transformer.Builder(context).setLooper(thread.looper).setEncoderFactory(factory)
                .apply { if (passthrough != null) setMuxerFactory(passthrough.muxerFactory()) }
                .setAssetLoaderFactory(LeadingAudioGapFactory(context, decoderFactory = decoderFactory,
                    passthroughVideo = passthrough?.let { it::checkVideo }))
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { ExportTrace.mark(traceId,"transformCompleted");finished.countDown() }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { failure=exception;finished.countDown() }
                }).build().also { ExportTrace.mark(traceId,"transformStarted");it.start(composition,output.path) }
        } catch(e: Exception) { failure=e;finished.countDown() }
    }
    try {
        val deadline=System.nanoTime()+TimeUnit.HOURS.toNanos(5)
        while(!finished.await(200,TimeUnit.MILLISECONDS)) {
            handler.post {
                if(finished.count>0) {
                    val progress=ProgressHolder()
                    val state=transformer?.getProgress(progress)
                    onProgress(if(state==Transformer.PROGRESS_STATE_AVAILABLE) progress.progress else null)
                }
            }
            checkCancelled();check(System.nanoTime()<deadline) { "Export timed out." }
            check(output.parentFile!!.usableSpace>50L*1024*1024) { "Storage is running low. Sources retained." }
        }
        failure?.let { throw it }
    } finally {
        val stopped=CountDownLatch(1)
        handler.post { transformer?.cancel();stopped.countDown() }
        stopped.await(10,TimeUnit.SECONDS);thread.quitSafely();thread.join(10000)
    }
}

/** The produced media contradicts the frozen export contract; a different production path may fix it. */
internal class ExportIntegrityException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** A platform decoder call failed. This is not evidence that the produced media is invalid. */
internal class ExportDecoderException(message: String, val codecErrorCode: Int?, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal fun classifyVerificationFailure(failure: Throwable): String {
    val chain = generateSequence(failure) { it.cause }.toList()
    return when {
        chain.any { it is ExportDecoderException } -> ExportCandidate.FAILURE_DECODER
        chain.any { it is IOException } -> ExportCandidate.FAILURE_OTHER
        chain.any { it is ExportIntegrityException } -> ExportCandidate.FAILURE_INTEGRITY
        else -> ExportCandidate.FAILURE_OTHER
    }
}

/**
 * Only exceptions raised by MediaCodec calls become [ExportDecoderException]. Our own
 * integrity checks and cancellation remain distinguishable.
 */
/** 0x80000000, the value the vendor decoder reported before releasing itself. */
private const val DECODER_UNKNOWN_ERROR = Int.MIN_VALUE

private inline fun <T> codecCall(type: String, block: () -> T): T = try {
    block()
} catch (failure: ExportDecoderException) {
    throw failure
} catch (failure: MediaCodec.CodecException) {
    throw ExportDecoderException("$type decoder error ${failure.errorCode}: " +
        (failure.diagnosticInfo?.takeIf { it.isNotBlank() } ?: failure.message ?: "no detail"),
        failure.errorCode, failure)
} catch (failure: IllegalStateException) {
    throw ExportDecoderException("$type decoder state error: ${failure.message}", null, failure)
}

/** MediaCodec declares IOException for creation only. Do not wrap extractor or file I/O. */
internal inline fun <T> openVerifierCodec(type: String, create: () -> T): T = try {
    create()
} catch (failure: IOException) {
    throw ExportDecoderException("$type decoder could not be opened: ${failure.message}", null, failure)
}

/** Full decode without speaker playback or retaining decoded frames. */
internal fun verify(file: File, expectedMs: Long, traceId: String? = null,
    videoDecoder: String? = null, onDecoderOpened: ((String, String) -> Unit)? = null,
    diagnostic: (JSONObject) -> Unit = {}): JSONObject {
    checkCancelled()
    ExportTrace.measure(traceId,"verifyInspectBefore") {
        try { inspect(file) } catch (failure: Exception) {
            throw ExportIntegrityException("Candidate metadata is unreadable: ${failure.message}", failure)
        }
    }
    val fault=ExportFaults.current()
    val spans=mutableListOf<Pair<Long,Long>>()
    val measured=JSONArray()
    for(type in listOf("video/","audio/")) {
        val decodeStart=ExportTrace.now()
        val extractor=MediaExtractor();var codec: MediaCodec?=null
        var inputWaitNs=0L;var outputWaitNs=0L;var sampleReadNs=0L
        var inputMisses=0;var outputMisses=0
        try {
            if (ExportFaults.has(ExportFaults.Mode.VERIFIER_EXTRACTOR_IO)) throw IOException("Injected extractor input I/O")
            extractor.setDataSource(file.path)
            val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(type) }
            val format=extractor.getTrackFormat(track);val mime=format.getString(MediaFormat.KEY_MIME)!!
            if(mime!=if(type=="video/") "video/avc" else "audio/mp4a-latm")
                throw ExportIntegrityException("Unexpected output codec $mime")
            if(type=="audio/" && (format.getInteger(MediaFormat.KEY_SAMPLE_RATE)!=48000 ||
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=2))
                throw ExportIntegrityException("Unexpected audio output format.")
            extractor.selectTrack(track)
            codecCall(type) {
                codec=openVerifierCodec(type) {
                    if (type=="video/" && ExportFaults.has(ExportFaults.Mode.VIDEO_INIT))
                        throw IOException("Injected codec creation I/O")
                    if(type=="video/" && videoDecoder!=null) MediaCodec.createByCodecName(videoDecoder)
                    else MediaCodec.createDecoderByType(mime)
                }
                codec.configure(format,null,null,0);codec.start()
            }
            onDecoderOpened?.invoke(type,codecCall(type) { codec!!.name })
            ExportTrace.duration(traceId,"decoderSetup_$type",decodeStart)
            var inputDone=false;var outputDone=false;val order=PresentationOrder();var count=0
            val info=MediaCodec.BufferInfo();var lastWork=System.nanoTime()
            var pcmEnd: Long? = null
            var pcmFrames = 0L
            var outputFormat: MediaFormat? = null
            var injectedRelease=false
            while(!outputDone) {
                checkCancelled()
                if (System.nanoTime()-lastWork>=TimeUnit.SECONDS.toNanos(30))
                    throw ExportDecoderException("$type output decoder stalled.", null)
                if(!inputDone) {
                    val waitStart=ExportTrace.now()
                    val index=codecCall(type) { codec!!.dequeueInputBuffer(1000) }
                    inputWaitNs+=ExportTrace.now()-waitStart
                    if(index==MediaCodec.INFO_TRY_AGAIN_LATER) inputMisses++
                    if(index>=0) {
                        val readStart=ExportTrace.now()
                        val size=extractor.readSampleData(codecCall(type) { codec!!.getInputBuffer(index)!! },0)
                        sampleReadNs+=ExportTrace.now()-readStart
                        if(size<0) codecCall(type) { codec!!.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true }
                        else {
                            val time=extractor.sampleTime
                            codecCall(type) { codec!!.queueInputBuffer(index,0,size,time,0) }
                            extractor.advance()
                        }
                    }
                }
                val waitStart=ExportTrace.now()
                val index=codecCall(type) { codec!!.dequeueOutputBuffer(info,1000) }
                outputWaitNs+=ExportTrace.now()-waitStart
                if(index==MediaCodec.INFO_TRY_AGAIN_LATER) outputMisses++
                if(index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat=codecCall(type) { codec!!.outputFormat }
                if(index>=0) {
                    lastWork=System.nanoTime()
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                        try { order.accept(info.presentationTimeUs) } catch (failure: IllegalArgumentException) {
                            throw ExportIntegrityException(failure.message ?: "Decoded timestamps moved backwards.", failure)
                        }
                        count++
                        if(type=="audio/") {
                            val decoded=codecCall(type) { outputFormat ?: codec!!.outputFormat }
                            // Per MediaCodec documentation, raw decoder PCM defaults to signed 16-bit.
                            val encoding=if(decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            val bytes=when(encoding) { AudioFormat.ENCODING_PCM_16BIT -> 2; AudioFormat.ENCODING_PCM_FLOAT -> 4; else -> null }
                            if(bytes!=null) {
                                val frames=TimingMeasurement.pcmFrames(info.size, decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT), bytes)
                                pcmFrames=Math.addExact(pcmFrames,frames)
                                pcmEnd=TimingMeasurement.endUs(info.presentationTimeUs,frames,decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                            }
                        }
                        if(count==ExportFaults.FAILURE_OUTPUT && !injectedRelease) when {
                            type=="video/" && (fault==ExportFaults.Mode.VIDEO_MID || fault==ExportFaults.Mode.PERSISTENT_VIDEO_MID) ->
                                throw ExportDecoderException("Injected video decoder failure after $count samples",
                                    DECODER_UNKNOWN_ERROR)
                            type=="audio/" && fault==ExportFaults.Mode.AUDIO_MID ->
                                throw ExportDecoderException("Injected audio decoder failure after $count samples",
                                    DECODER_UNKNOWN_ERROR)
                            type=="video/" && fault==ExportFaults.Mode.VIDEO_RELEASE -> {
                                // Reproduce the vendor decoder releasing itself mid-stream.
                                injectedRelease=true;codecCall(type) { codec!!.release() }
                            }
                            type=="video/" && fault==ExportFaults.Mode.KILL_DURING_RECHECK ->
                                ExportFaults.kill()
                        }
                    }
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    if(!injectedRelease) codecCall(type) { codec!!.releaseOutputBuffer(index,false) }
                }
            }
            if(count==0 || order.first==null || order.last==null)
                throw ExportIntegrityException("Output $type contains no decoded samples.")
            spans+=order.first!! to order.last!!
            val trace=DecodedTiming(order.first!!,order.last!!,count.toLong(),pcmEnd)
            measured.put(JSONObject().put("track",type).put("firstPtsUs",trace.firstPtsUs).put("lastPtsUs",trace.lastPtsUs)
                .put("decodedBuffers",trace.buffers).put("decodedPcmFrames",if(type=="audio/") pcmFrames else JSONObject.NULL)
                .put("decodedPcmEndExclusiveUs",pcmEnd ?: JSONObject.NULL)
                .put("reportedTrackDurationUs",if(format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else JSONObject.NULL))
        } finally {
            // A released or failed codec must never prevent the extractor from being freed,
            // and must not replace the original verification failure.
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            ExportTrace.duration(traceId,"decode_$type",decodeStart)
            ExportTrace.decoderTotals(traceId,type,JSONObject()
                .put("inputWaitMs",inputWaitNs/1_000_000.0).put("outputWaitMs",outputWaitNs/1_000_000.0)
                .put("sampleReadMs",sampleReadNs/1_000_000.0)
                .put("inputTryAgain",inputMisses).put("outputTryAgain",outputMisses))
        }
    }
    val duration=ExportTrace.measure(traceId,"verifyInspectAfter") {
        try { inspect(file).duration } catch (failure: Exception) {
            throw ExportIntegrityException("Candidate metadata is unreadable after decoding: ${failure.message}", failure)
        }
    }
    val timing=JSONObject().put("expectedMs",expectedMs).put("containerMs",duration).put("tracks",measured)
        .put("lastPtsGapUs",kotlin.math.abs(spans[0].second-spans[1].second))
    diagnostic(timing)
    if(kotlin.math.abs(duration-expectedMs)>100)
        throw ExportIntegrityException("Output duration differs: expected $expectedMs ms, got $duration ms.")
    if(kotlin.math.abs(spans[0].second-spans[1].second)>100_000 || kotlin.math.abs(spans[0].first-spans[1].first)>100_000)
        throw ExportIntegrityException("Output audio/video timing differs by over 100 ms.")
    val digest=ExportTrace.measure(traceId,"outputHash") { sha(file) }
    ExportTrace.bytes(traceId,"outputHashRead",file.length())
    return JSONObject().put("sha256",digest).put("durationMs",duration).put("timing",timing)
}
