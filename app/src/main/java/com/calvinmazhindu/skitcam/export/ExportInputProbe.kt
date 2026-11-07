package com.calvinmazhindu.skitcam.export

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.security.MessageDigest

internal object ExportInputProbe {
    fun inspect(file: File): ExportInputFacts {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            require(extractor.trackCount==2) { "Exactly two tracks are required" }
            val formats=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val videoIndex=formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)=="video/avc" }
            val audioIndex=formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm" }
            require(videoIndex>=0 && audioIndex>=0) { "AVC and AAC tracks are required" }
            val video=formats[videoIndex];val audio=formats[audioIndex]
            fun integer(f: MediaFormat,key: String,default: Int=-1) = if(f.containsKey(key)) f.getInteger(key) else default
            fun config(f: MediaFormat): List<String> = (0..2).mapNotNull { index ->
                f.getByteBuffer("csd-$index")?.duplicate()?.let { buffer ->
                    val bytes=ByteArray(buffer.remaining());buffer.get(bytes)
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                }
            }
            fun scan(index: Int): LongArray {
                extractor.selectTrack(index)
                extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var first: Long?=null;var last: Long?=null;var previous: Long?=null
                var reordered=0L;var encrypted=0L;var sync=0L
                // Negative AAC priming timestamps are valid; sampleTrackIndex, not the sign
                // of a presentation timestamp, distinguishes end of input.
                while(extractor.sampleTrackIndex>=0) {
                    checkCancelled()
                    val time=extractor.sampleTime;val flags=extractor.sampleFlags
                    if(first==null) { first=time;sync=if(flags and MediaExtractor.SAMPLE_FLAG_SYNC!=0) 1 else 0 }
                    previous?.let { if(time<=it) reordered=1 }
                    if(flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED!=0) encrypted=1
                    last=time;previous=time
                    if(!extractor.advance()) break
                }
                extractor.unselectTrack(index)
                val start=checkNotNull(first) { "Source track contains no samples" }
                val end=checkNotNull(last);require(end>=start)
                return longArrayOf(start,end,reordered,encrypted,sync)
            }
            val videoTimes=scan(videoIndex)
            val audioTimes=scan(audioIndex)
            val retriever=MediaMetadataRetriever()
            val metadata=try {
                retriever.setDataSource(file.path)
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) to
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
            } finally { retriever.release() }
            val asc=audio.getByteBuffer("csd-0")?.duplicate()
            val objectType=if(asc!=null && asc.hasRemaining()) (asc.get().toInt() and 255) shr 3 else -1
            return ExportInputFacts(integer(video,MediaFormat.KEY_WIDTH),integer(video,MediaFormat.KEY_HEIGHT),metadata.first,
                video.getString(MediaFormat.KEY_MIME)!!,config(video),listOf(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.KEY_COLOR_RANGE,MediaFormat.KEY_COLOR_TRANSFER).map { integer(video,it) },
                integer(video,"sar-width",1)==integer(video,"sar-height",1),
                integer(video,MediaFormat.KEY_COLOR_TRANSFER) !in listOf(MediaFormat.COLOR_TRANSFER_ST2084,MediaFormat.COLOR_TRANSFER_HLG),
                videoTimes[4]==1L,videoTimes[0],videoTimes[1],videoTimes[2]!=0L,
                audio.getString(MediaFormat.KEY_MIME)!!,integer(audio,MediaFormat.KEY_SAMPLE_RATE),integer(audio,MediaFormat.KEY_CHANNEL_COUNT),
                objectType==2,integer(audio,MediaFormat.KEY_BIT_RATE).takeIf { it>0 },audioTimes[0],audioTimes[1],metadata.second,
                videoTimes[3]!=0L || audioTimes[3]!=0L)
        } finally { extractor.release() }
    }
}
