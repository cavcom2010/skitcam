package com.calvinmazhindu.skitcam.review

import android.graphics.Color
import androidx.core.graphics.get
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calvinmazhindu.skitcam.recording.Segment
import com.calvinmazhindu.skitcam.trim.TrimRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Run only in a disposable .thumbvalidation installation with
 * tools/thumbnail_fixtures.sh output staged in files/thumbnail-inputs.
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailReadabilityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source get() = File(context.filesDir, "thumbnail-inputs/darkstart.mp4")

    @Test fun darkOpeningTakeStillGetsAReadableThumbnail() {
        check(context.packageName.endsWith(".thumbvalidation")) { "Thumbnail validation package only." }
        check(source.isFile) { "Stage tools/thumbnail_fixtures.sh output in files/thumbnail-inputs first." }
        val full = segment("full", 0)
        val average = averageLuma(load(full))
        Log.i("ThumbnailValidation", "Full-range thumbnail average luma: $average")
        assertTrue("A dark opening must not become the thumbnail (average $average)", average >= 16)
    }

    @Test fun trimmedRangeUsesAFrameInsideTheRetainedClip() {
        check(context.packageName.endsWith(".thumbvalidation")) { "Thumbnail validation package only." }
        check(source.isFile) { "Stage tools/thumbnail_fixtures.sh output in files/thumbnail-inputs first." }
        val trimmed = segment("trimmed", 1000)
        val average = averageLuma(load(trimmed))
        Log.i("ThumbnailValidation", "Trimmed-range thumbnail average luma: $average")
        assertTrue("A trimmed clip must not fall back to its excluded dark opening (average $average)",
            average >= 16)
    }

    private fun segment(id: String, startMs: Long) = Segment(1, source.path, 3200, 0, 0, id = id,
        trim = TrimRange(3200, startMs, 3200))

    private fun load(segment: Segment) =
        runBlocking { ThumbnailLoader().load(segment) } as? ThumbnailResult.Ready
            ?: error("No thumbnail extracted for ${segment.id}")

    private fun averageLuma(ready: ThumbnailResult.Ready): Long {
        val bitmap = ready.bitmap
        val step = (minOf(bitmap.width, bitmap.height) / 10).coerceAtLeast(1)
        var total = 0L
        var samples = 0
        var y = step / 2
        while (y < bitmap.height) {
            var x = step / 2
            while (x < bitmap.width) {
                val pixel = bitmap[x, y]
                total += (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                samples++
                x += step
            }
            y += step
        }
        return if (samples == 0) 0 else total / samples
    }
}
