package com.calvinmazhindu.skitcam.video

import android.media.*
import android.os.Build
import java.nio.ByteBuffer
import java.io.File

/** A streaming timestamp/size scan. No video frames or compressed payloads are retained. */
object VideoInspector {
    fun inspect(path: String): SegmentMediaFacts {
        require(File(path).isFile && File(path).length()>0) { "Source missing or empty." }
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(path)
            val tracks=(0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
            val video=tracks.single { it.second.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
            val audio=tracks.single { it.second.getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }.second
            val format=video.second
            if(format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) require(format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !in listOf(MediaFormat.COLOR_TRANSFER_ST2084,MediaFormat.COLOR_TRANSFER_HLG)) { "HDR capture is not supported." }
            extractor.selectTrack(video.first)
            // getSampleSize is API 28+. Older Android reads one compressed sample into a
            // reusable bounded buffer; it never retains decoded frames or the whole clip.
            var sampleBuffer: ByteBuffer?=if(Build.VERSION.SDK_INT<28) ByteBuffer.allocate(1024*1024) else null
            fun sampleBytes(): Long {
                if(Build.VERSION.SDK_INT>=28) return extractor.sampleSize
                while(true) {
                    val buffer=sampleBuffer!!
                    buffer.clear()
                    try { return extractor.readSampleData(buffer,0).toLong().also { require(it>0) } }
                    catch(e: IllegalArgumentException) {
                        check(buffer.capacity()<16*1024*1024) { "Compressed video sample is too large to inspect safely." }
                        sampleBuffer=ByteBuffer.allocate(buffer.capacity()*2)
                    }
                }
            }
            var frames=0L;var bytes=0L;var first=Long.MAX_VALUE;var last=Long.MIN_VALUE;var previous: Long?=null
            var minimum: Long?=null;var maximum: Long?=null;var reordered=false
            while(extractor.sampleTime>=0) {
                val time=extractor.sampleTime
                first=minOf(first,time);last=maxOf(last,time)
                previous?.let { val delta=time-it
                    if(delta<=0) reordered=true else { minimum=minimum?.let { minOf(it,delta) } ?: delta;maximum=maximum?.let { maxOf(it,delta) } ?: delta }
                }
                previous=time;frames++;bytes=Math.addExact(bytes,sampleBytes())
                if(!extractor.advance()) break
            }
            fun dimension(size: String,low: String,high: String): Int = if(format.containsKey(low) && format.containsKey(high)) format.getInteger(high)-format.getInteger(low)+1 else format.getInteger(size)
            return SegmentMediaFacts(dimension(MediaFormat.KEY_WIDTH,"crop-left","crop-right"),dimension(MediaFormat.KEY_HEIGHT,"crop-top","crop-bottom"),
                if(format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0,
                format.getString(MediaFormat.KEY_MIME)!!,audio.getString(MediaFormat.KEY_MIME)!!,
                audio.getInteger(MediaFormat.KEY_SAMPLE_RATE),audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT),frames,first,last,
                if(reordered) null else minimum,if(reordered) null else maximum,reordered,bytes,
                if(format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else error("Track duration unavailable."))
                .also { require(it.valid()) { "Capture must contain SDR AVC video and AAC audio with valid timestamps." } }
        } finally { extractor.release() }
    }
}
