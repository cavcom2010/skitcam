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
internal fun transform(context: Context, spec: ExportSpec, output: File, onProgress: (Int?) -> Unit = {}) {
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
        val videoEffects=mutableListOf<androidx.media3.common.Effect>(Presentation.createForWidthAndHeight(width,height,Presentation.LAYOUT_SCALE_TO_FIT))
        EditedMediaItem.Builder(item).apply { if(matching==null) setFrameRate(30) }.setEffects(Effects(listOf(channels,sonic),videoEffects)).build()
    }
    val composition=Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(items))
        .setTransmuxAudio(false).setTransmuxVideo(false).build()
    val thread=HandlerThread("SkitCam export").apply { start() };val handler=Handler(thread.looper)
    val finished=CountDownLatch(1);var failure: Exception?=null;var transformer: Transformer?=null
    handler.post {
        try {
            val delegate=DefaultEncoderFactory.Builder(context).setEnableFallback(false)
                .apply { if(matching==null) setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(4_000_000).build()) }
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(128_000).setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectLC).build()).build()
            val factory=object : Codec.EncoderFactory by delegate {
                override fun audioNeedsEncoding()=true
                override fun videoNeedsEncoding()=true
            }
            transformer=Transformer.Builder(context).setLooper(thread.looper).setEncoderFactory(factory)
                .setAssetLoaderFactory(LeadingAudioGapFactory(context))
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { finished.countDown() }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { failure=exception;finished.countDown() }
                }).build().also { it.start(composition,output.path) }
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

/** Full decode without speaker playback or retaining decoded frames. */
internal fun verify(file: File, expectedMs: Long, diagnostic: (JSONObject) -> Unit = {}): JSONObject {
    inspect(file)
    val spans=mutableListOf<Pair<Long,Long>>()
    val measured=JSONArray()
    for(type in listOf("video/","audio/")) {
        val extractor=MediaExtractor();var codec: MediaCodec?=null
        try {
            extractor.setDataSource(file.path)
            val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(type) }
            val format=extractor.getTrackFormat(track);val mime=format.getString(MediaFormat.KEY_MIME)!!
            require(mime==if(type=="video/") "video/avc" else "audio/mp4a-latm") { "Unexpected output codec $mime" }
            if(type=="audio/") require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE)==48000 && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==2) { "Unexpected audio output format." }
            extractor.selectTrack(track)
            codec=MediaCodec.createDecoderByType(mime);codec.configure(format,null,null,0);codec.start()
            var inputDone=false;var outputDone=false;val order=PresentationOrder();var count=0
            val info=MediaCodec.BufferInfo();var lastWork=System.nanoTime()
            var pcmEnd: Long? = null
            var pcmFrames = 0L
            var outputFormat: MediaFormat? = null
            while(!outputDone) {
                checkCancelled();check(System.nanoTime()-lastWork<TimeUnit.SECONDS.toNanos(30)) { "Output decoder stalled." }
                if(!inputDone) {
                    val index=codec.dequeueInputBuffer(1000)
                    if(index>=0) {
                        val size=extractor.readSampleData(codec.getInputBuffer(index)!!,0)
                        if(size<0) { codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true }
                        else { codec.queueInputBuffer(index,0,size,extractor.sampleTime,0);extractor.advance() }
                    }
                }
                val index=codec.dequeueOutputBuffer(info,1000)
                if(index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat=codec.outputFormat
                if(index>=0) {
                    lastWork=System.nanoTime()
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                        order.accept(info.presentationTimeUs);count++
                        if(type=="audio/") {
                            val decoded=outputFormat ?: codec.outputFormat
                            // Per MediaCodec documentation, raw decoder PCM defaults to signed 16-bit.
                            val encoding=if(decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            val bytes=when(encoding) { AudioFormat.ENCODING_PCM_16BIT -> 2; AudioFormat.ENCODING_PCM_FLOAT -> 4; else -> null }
                            if(bytes!=null) {
                                val frames=TimingMeasurement.pcmFrames(info.size, decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT), bytes)
                                pcmFrames=Math.addExact(pcmFrames,frames)
                                pcmEnd=TimingMeasurement.endUs(info.presentationTimeUs,frames,decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                            }
                        }
                    }
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    codec.releaseOutputBuffer(index,false)
                }
            }
            require(count>0 && order.first!=null && order.last!=null) { "Output $type contains no decoded samples." }
            spans+=order.first!! to order.last!!
            val trace=DecodedTiming(order.first!!,order.last!!,count.toLong(),pcmEnd)
            measured.put(JSONObject().put("track",type).put("firstPtsUs",trace.firstPtsUs).put("lastPtsUs",trace.lastPtsUs)
                .put("decodedBuffers",trace.buffers).put("decodedPcmFrames",if(type=="audio/") pcmFrames else JSONObject.NULL)
                .put("decodedPcmEndExclusiveUs",pcmEnd ?: JSONObject.NULL)
                .put("reportedTrackDurationUs",if(format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else JSONObject.NULL))
        } finally { codec?.release();extractor.release() }
    }
    val duration=inspect(file).duration
    val timing=JSONObject().put("expectedMs",expectedMs).put("containerMs",duration).put("tracks",measured)
        .put("lastPtsGapUs",kotlin.math.abs(spans[0].second-spans[1].second))
    diagnostic(timing)
    require(kotlin.math.abs(duration-expectedMs)<=100) { "Output duration differs: expected $expectedMs ms, got $duration ms." }
    require(kotlin.math.abs(spans[0].second-spans[1].second)<=100_000 && kotlin.math.abs(spans[0].first-spans[1].first)<=100_000) { "Output audio/video timing differs by over 100 ms." }
    return JSONObject().put("sha256",sha(file)).put("durationMs",duration).put("timing",timing)
}
