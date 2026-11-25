package com.calvinmazhindu.skitcam.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExportException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.trim.TrimRange
import com.calvinmazhindu.skitcam.video.CaptureProfile
import com.calvinmazhindu.skitcam.video.VideoSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Device-only experiments. A render trial always uses the default full verifier. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DecoderCompatibilityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val staged get() = File(context.filesDir, "decoder-compatibility")
    private val decoder get() = args.getString("decoder")?.takeUnless { it == "default" }
    private val trialId get() = args.getString("trialId") ?: "manual"

    @Before fun guard() {
        check(ExportFaults.allowed(context) && context.packageName == ExportFaults.COMPATIBILITY_PACKAGE)
        check(Build.VERSION.SDK_INT >= 29 && !ExportJournal.busy)
        ExportJournal.cancelRequested = false
    }

    private fun namedInput(argument: String, default: String): File {
        val name = args.getString(argument) ?: default
        require(name.matches(Regex("[a-zA-Z0-9_-]+\\.mp4")))
        return File(staged, name).also { require(it.isFile) { "Stage $name first" } }
    }

    @Test fun inventory() {
        val file = namedInput("candidate", "short.mp4")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .first { it.getString(MediaFormat.KEY_MIME) == MimeTypes.VIDEO_H264 }
            val regular = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val entries = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder && MimeTypes.VIDEO_H264 in it.supportedTypes }.map { info ->
                    val caps = info.getCapabilitiesForType(MimeTypes.VIDEO_H264)
                    JSONObject().put("name", info.name).put("canonical", info.canonicalName)
                        .put("hardware", info.isHardwareAccelerated).put("software", info.isSoftwareOnly)
                        .put("vendor", info.isVendor).put("alias", info.isAlias)
                        .put("regular", regular.codecInfos.any { it.name == info.name })
                        .put("secureRequired", caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback))
                        .put("formatSupported", caps.isFormatSupported(format))
                }
            Log.i(TAG, JSONObject().put("trialId",trialId).put("input", file.name).put("format", format.toString())
                .put("formatDefault", regular.findDecoderForFormat(format))
                .put("codecs", JSONArray(entries)).toString())
        } finally {
            extractor.release()
        }
    }

    @Test fun fullVerification() {
        val file = namedInput("candidate", "short.mp4")
        trial("verify", listOf(file)) { report ->
            val opened = JSONArray()
            report.put("opened", opened)
            val expected = args.getString("expectedMs")?.toLong() ?: inspect(file).duration
            val result = verify(file, expected, videoDecoder = decoder, onDecoderOpened = { track, name ->
                opened.put(JSONObject().put("track", track).put("codec", name))
            })
            report.put("verification", result)
            assertEquals(report.getString("inputSha256"), result.getString("sha256"))
        }
    }

    @Test fun trimmedRender() {
        val source = namedInput("candidate", "short.mp4")
        val geometry = inspect(source)
        val spec = ExportSpec("short", 270, listOf(ExportClip("short", source.path, TrimRange(geometry.duration, 0, 2500))),
            CaptureProfile("fixture", VideoSettings("FHD"), 1920, 1080))
        renderTrial(spec)
    }

    @Test fun fieldPrefixRender() {
        // The first three items, in frozen field order, with the original trim boundaries.
        val clips = listOf(Triple(3,2739L,13066L), Triple(1,0L,11697L), Triple(2,1353L,10461L)).map { (number,start,end) ->
            val source = File(staged, "field-$number.mp4")
            val duration = inspect(source).duration
            check(end <= duration)
            ExportClip("field-$number",source.path,TrimRange(duration,start,end))
        }
        renderTrial(ExportSpec("field-prefix",0,clips,CaptureProfile("0",VideoSettings("FHD"),1920,1080)))
    }

    private fun renderTrial(spec: ExportSpec) {
        val sources = spec.clips.map { File(it.path) }
        val output = File(context.cacheDir, "decoder-trial.mp4")
        try {
            trial("render", sources) { report ->
                val opened = JSONArray()
                report.put("opened", opened)
                val builder = DefaultDecoderFactory.Builder(context).setListener { name, _ -> opened.put(name) }
                decoder?.let { selected ->
                    builder.setMediaCodecSelector { mime, secure, tunneled ->
                        val defaults = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneled)
                        if (mime == MimeTypes.VIDEO_H264) defaults.filter { it.name == selected } else defaults
                    }
                }
                val start = SystemClock.elapsedRealtime()
                report.put("phase", "transform")
                transform(context, spec, output, decoderFactory = builder.build())
                report.put("transformMs", SystemClock.elapsedRealtime() - start).put("phase","verify")
                val inputGeometry = inspect(sources.first())
                val outputGeometry = inspect(output)
                assertEquals(inputGeometry.width to inputGeometry.height, outputGeometry.width to outputGeometry.height)
                report.put("bytes", output.length()).put("verification", verify(output, spec.durationMs))
                    .put("samplePayloads",samplePayloads(output))
            }
        } finally {
            output.delete()
        }
    }

    private fun samplePayloads(file: File): JSONObject {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            var payload=ByteBuffer.allocateDirect(1024)
            val fields=ByteBuffer.allocate(16)
            val digests=List(extractor.trackCount) { MessageDigest.getInstance("SHA-256") }
            val counts=IntArray(extractor.trackCount)
            for(track in digests.indices) extractor.selectTrack(track)
            while(extractor.sampleTrackIndex>=0) {
                val track=extractor.sampleTrackIndex
                val size=Math.toIntExact(extractor.sampleSize)
                if(size>payload.capacity()) payload=ByteBuffer.allocateDirect(maxOf(size,payload.capacity()*2))
                payload.clear()
                check(extractor.readSampleData(payload,0)==size)
                fields.clear()
                fields.putInt(size).putLong(extractor.sampleTime).putInt(extractor.sampleFlags)
                digests[track].update(fields.array())
                payload.position(0).limit(size)
                digests[track].update(payload)
                counts[track]++
                extractor.advance()
            }
            val result=JSONObject()
            for(track in digests.indices) {
                val mime=extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)!!
                result.put(mime, JSONObject().put("samples",counts[track])
                    .put("sha256",digests[track].digest().joinToString("") { "%02x".format(it) }))
            }
            return result
        } finally {
            extractor.release()
        }
    }

    private fun trial(stage: String, inputs: List<File>, block: (JSONObject) -> Unit) {
        val power = context.getSystemService(PowerManager::class.java)
        val original = inputs.map(::sha)
        val report = JSONObject().put("trialId",trialId).put("stage", stage).put("decoder", decoder ?: "default")
            .put("inputSha256", original.first()).put("thermalStart", power.currentThermalStatus)
            .put("inputs",JSONArray(inputs.zip(original).map { (file,hash) ->
                JSONObject().put("file",file.name).put("sha256",hash)
            }))
        val start = SystemClock.elapsedRealtime()
        try {
            block(report)
            report.put("result", "pass")
        } catch (failure: Throwable) {
            val causes = generateSequence(failure) { it.cause }.map { it.toString() }.toList()
            report.put("result", "fail").put("causes", JSONArray(causes))
            if(failure is ExportException) report.put("media3Code",failure.errorCodeName)
            throw failure
        } finally {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - start).put("thermalEnd", power.currentThermalStatus)
            val unchanged = original == inputs.map(::sha)
            report.put("inputUnchanged", unchanged)
            Log.i(TAG, report.toString())
            check(unchanged)
        }
    }

    private companion object { const val TAG = "SkitCamDecoderTrial" }
}
