package com.calvinmazhindu.skitcam.export

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.SampleConsumer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LeadingGapConsumerDeviceTest {
    private class Sink : SampleConsumer {
        val buffer=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
        var calls=0
        override fun getInputBuffer()=buffer
        override fun queueInputBuffer(): Boolean { calls++;return calls>1 }
    }
    private val format=Format.Builder().setSampleMimeType("audio/raw").setSampleRate(48000)
        .setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
    private val original=byteArrayOf(1,2,3,4,5,6,7,8)
    private fun sink(pts: Long)=Sink().also { it.buffer.ensureSpaceForWrite(8);it.buffer.data!!.put(original).flip();it.buffer.timeUs=pts }
    @Test fun sourceGapPreservesPcmAndQueueRetryIsIdempotent() {
        val sink=sink(66104);val wrapper=LeadingAudioGapConsumer(sink,format,66104,{1805000})
        assertSame(sink.buffer,wrapper.inputBuffer)
        assertFalse(wrapper.queueInputBuffer())
        val once=sink.buffer.data!!
        assertEquals(3173*4+8,once.remaining());assertEquals(0L,sink.buffer.timeUs)
        assertTrue(wrapper.queueInputBuffer());assertSame(once,sink.buffer.data)
        val copy=once.duplicate();val padding=ByteArray(3173*4);copy.get(padding);assertTrue(padding.all { it==0.toByte() })
        val tail=ByteArray(8);copy.get(tail);assertArrayEquals(original,tail)
    }
    @Test fun zeroStartPrimingIsNotPadded() {
        val sink=sink(21333);val bytes=sink.buffer.data
        val wrapper=LeadingAudioGapConsumer(sink,format,0,{3000000})
        wrapper.queueInputBuffer();assertSame(bytes,sink.buffer.data);assertEquals(21333L,sink.buffer.timeUs)
    }
    @Test fun eosIsForwardedWithoutFabricatingSamples() {
        val sink=Sink();sink.buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        LeadingAudioGapConsumer(sink,format,1000,{3000000}).queueInputBuffer()
        assertNull(sink.buffer.data)
    }
    @Test fun onlyDeclaredGapIsRestored() {
        val sink=sink(128000)
        LeadingAudioGapConsumer(sink,format,66104,{3000000}).queueInputBuffer()
        assertEquals(3173*4+8,sink.buffer.data!!.remaining())
        assertEquals(61896L,sink.buffer.timeUs)
    }
}
