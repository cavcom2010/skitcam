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

/** Test-only controlled copies of the normal transform; never used by the app. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun probeTransform(context: Context, spec: ExportSpec, output: File, variant: String, onProgress: (Int?) -> Unit = {}) {
    val sizes=spec.clips.map { inspect(File(it.path)) }
    val first=sizes.first()
    require(sizes.all { kotlin.math.abs(it.width.toDouble()/it.height-first.width.toDouble()/first.height)<0.005 }) { "Segments have incompatible display aspect ratios." }
    val scale=minOf(1.0,720.0/minOf(first.width,first.height),1280.0/maxOf(first.width,first.height),
        sizes.minOf { minOf(it.width.toDouble()/first.width,it.height.toDouble()/first.height) })
    val width=((first.width*scale).toInt()/2*2).coerceAtLeast(2)
    val height=((first.height*scale).toInt()/2*2).coerceAtLeast(2)
    val items=spec.clips.map { clip ->
        val sonic=SonicAudioProcessor().apply { setOutputSampleRateHz(48000) }
        val channels=ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1,2))
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2,2))
        }
        require(!clip.range.isTrimmed) { "Diagnostic variants only accept full-source probes" }
        val builder=MediaItem.Builder().setUri(Uri.fromFile(File(clip.path)))
        if(variant!="no-clip") builder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clip.range.trimStartMs).setEndPositionMs(clip.range.trimEndMs).build())
        val item=builder.build()
        val videoEffects=mutableListOf<androidx.media3.common.Effect>(Presentation.createForWidthAndHeight(width,height,Presentation.LAYOUT_SCALE_TO_FIT))
        EditedMediaItem.Builder(item).setFrameRate(30).setEffects(Effects(if(variant=="no-processors") emptyList() else listOf(channels,sonic),videoEffects)).build()
    }
    val composition=Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(items))
        .setTransmuxAudio(false).setTransmuxVideo(false).build()
    val thread=HandlerThread("SkitCam export").apply { start() };val handler=Handler(thread.looper)
    val finished=CountDownLatch(1);var failure: Exception?=null;var transformer: Transformer?=null
    handler.post {
        try {
            val delegate=DefaultEncoderFactory.Builder(context).setEnableFallback(false)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(4_000_000).build())
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(128_000).setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectLC).build()).build()
            val factory=object : Codec.EncoderFactory by delegate {
                override fun audioNeedsEncoding()=true
                override fun videoNeedsEncoding()=true
            }
            val transformerBuilder=Transformer.Builder(context).setLooper(thread.looper).setEncoderFactory(factory)
            if(variant=="leading-gap") transformerBuilder.setAssetLoaderFactory(LeadingAudioGapFactory(context))
            transformer=transformerBuilder
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

